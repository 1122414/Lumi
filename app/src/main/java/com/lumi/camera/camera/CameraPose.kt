package com.lumi.camera.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageProxy
import com.lumi.camera.guidance.Lens
import com.lumi.camera.guidance.Pose
import kotlin.math.abs
import kotlin.math.hypot

internal class CameraPose(context: Context, private val displayRotation: Int) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val samples = ArrayDeque<Pair<Long, Pose>>()

    fun start(): Boolean {
        // A shot needs relative rotation. Magnetic heading can jump as an indoor compass recalibrates.
        val relative = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (relative != null && manager.registerListener(this, relative, SensorManager.SENSOR_DELAY_GAME)) return true
        val absolute = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return false
        return manager.registerListener(this, absolute, SensorManager.SENSOR_DELAY_GAME)
    }
    fun close() = manager.unregisterListener(this)

    @Synchronized override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR && e.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            samples.clear()
            return
        }
        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, e.values)
        if (q.any { !it.isFinite() } || q.sumOf { it.toDouble()*it } !in 0.99..1.01) return
        val pose = Pose(q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble())
        samples.addLast(e.timestamp to pose.forDisplay(displayRotation))
        if (samples.size > 64) samples.removeFirst()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Synchronized fun at(timeNs: Long): Pose? = samples.minByOrNull { abs(it.first - timeNs) }
        ?.takeIf { abs(it.first - timeNs) <= 60_000_000L }?.second

    @Synchronized fun latestAt(nowNs: Long): Pose? =
        samples.lastOrNull()?.takeIf { nowNs - it.first in 0..60_000_000L }?.second
}

internal class AmbientLightSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    @Volatile var latestLux: Float? = null
        private set

    fun start(): Boolean {
        val light = manager.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return false
        return manager.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun close() {
        manager.unregisterListener(this)
        latestLux = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val lux = event.values.firstOrNull() ?: return
        if (lux.isFinite() && lux >= 0f) latestLux = lux
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal class CameraOptics(info: CameraInfo, private val readings: FocalReadings) {
    private val front = info.lensFacing == CameraSelector.LENS_FACING_FRONT
    private val meta = Camera2CameraInfo.from(info)
    private val focals = meta.getCameraCharacteristic(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
    private val physical = meta.getCameraCharacteristic(C.SENSOR_INFO_PHYSICAL_SIZE)
    private val pixels = meta.getCameraCharacteristic(C.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    private val active = meta.getCameraCharacteristic(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    private val ratioControl = Build.VERSION.SDK_INT >= 30 && meta.getCameraCharacteristic(C.CONTROL_ZOOM_RATIO_RANGE) != null
    private val realtime = meta.getCameraCharacteristic(C.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        C.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    fun capabilities(): CameraCapabilities {
        val isoRange = meta.getCameraCharacteristic(C.SENSOR_INFO_SENSITIVITY_RANGE)
            ?.let { it.lower..it.upper } ?: (100..3200)
        val shutterRange = meta.getCameraCharacteristic(C.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?.let { it.lower..it.upper } ?: (100_000L..1_000_000_000L)
        val evRange = meta.getCameraCharacteristic(C.CONTROL_AE_COMPENSATION_RANGE)
            ?.let { it.lower..it.upper } ?: (-12..12)
        val evStep = meta.getCameraCharacteristic(C.CONTROL_AE_COMPENSATION_STEP)
            ?.toFloat()?.takeIf { it.isFinite() && it > 0f } ?: (1f / 6f)
        val apertures = meta.getCameraCharacteristic(C.LENS_INFO_AVAILABLE_APERTURES)
            ?.filter { it.isFinite() && it > 0f }?.sorted() ?: emptyList()
        val caps = meta.getCameraCharacteristic(C.REQUEST_AVAILABLE_CAPABILITIES)
        val hwLevel = meta.getCameraCharacteristic(C.INFO_SUPPORTED_HARDWARE_LEVEL)
        val manualSensor = caps?.contains(C.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true ||
            hwLevel == C.INFO_SUPPORTED_HARDWARE_LEVEL_3 ||
            hwLevel == C.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
        val awbModes = meta.getCameraCharacteristic(C.CONTROL_AWB_AVAILABLE_MODES)
        val manualWb = awbModes?.contains(C.CONTROL_AWB_MODE_OFF) == true
        return CameraCapabilities(
            isoRange = isoRange,
            shutterRangeNs = shutterRange,
            evIndexRange = evRange,
            evStep = evStep,
            apertures = apertures,
            supportsManualSensor = manualSensor,
            supportsManualWb = manualWb,
        )
    }

    fun processingModes(): IspProcessingModes {
        val edges = meta.getCameraCharacteristic(C.EDGE_AVAILABLE_EDGE_MODES)?.toSet() ?: emptySet()
        val nr = meta.getCameraCharacteristic(C.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet() ?: emptySet()
        val scenes = meta.getCameraCharacteristic(C.CONTROL_AVAILABLE_SCENE_MODES)?.toSet() ?: emptySet()
        return IspProcessingModes(edgeModes = edges, noiseReductionModes = nr, sceneModes = scenes)
    }

    fun captureTimeNs(timestamp: Long): Long {
        // Some cameras use the uptime timebase. Never re-date a frame at detector completion.
        return if (realtime) timestamp else timestamp + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
    }

    fun lens(image: ImageProxy, geometry: FrameGeometry, zoom: Float): Lens? {
        val sensor = physical ?: return null
        val px = pixels ?: return null
        val area = active ?: return null
        val mm = readings.effectiveFocal(image.imageInfo.timestamp, zoom, area, ratioControl, focals?.singleOrNull()) ?: return null
        if (mm <= 0 || sensor.width <= 0 || sensor.height <= 0) return null
        val m = image.imageInfo.sensorToBufferTransformMatrix
        val axes = floatArrayOf(mm * px.width / sensor.width, 0f, 0f, mm * px.height / sensor.height)
        m.mapVectors(axes)
        val fx = hypot(axes[0], axes[1])
        val fy = hypot(axes[2], axes[3])
        val center = floatArrayOf(area.exactCenterX(), area.exactCenterY())
        m.mapPoints(center)
        val ref = geometry.referenceSize
        val principal = FrameMapping(geometry, ref).toReference(Point(center[0], center[1]))
        val swap = geometry.rotation % 180 != 0
        return Lens((if (swap) fy else fx).toDouble() / ref.width,
            (if (swap) fx else fy).toDouble() / ref.height,
            principal.x.toDouble() / ref.width, principal.y.toDouble() / ref.height, front)
            .takeIf { it.fx > 0 && it.fy > 0 && it.cx in 0.0..1.0 && it.cy in 0.0..1.0 }
    }
}

// CameraX's buffer transform describes the bound stream. HAL zoom changes the
// field of view within that stream, including across physical-lens switches.
internal class FocalReadings : CameraCaptureSession.CaptureCallback() {
    private data class Reading(val mm: Float?, val zoom: Float?, val crop: Rect?)
    private val values = LinkedHashMap<Long, Reading>()
    private var unitFocal: Float? = null
    @Volatile private var latestExposure = ExposureBaseline()
    @Volatile private var lastAutoExposure = ExposureBaseline()

    @Synchronized fun reset() {
        values.clear()
        unitFocal = null
        latestExposure = ExposureBaseline()
        lastAutoExposure = ExposureBaseline()
    }

    fun latestExposure(): ExposureBaseline = latestExposure
    fun lastAutoExposure(): ExposureBaseline = lastAutoExposure

    @Synchronized override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest,
        result: TotalCaptureResult) {
        val time = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val ratio = if (Build.VERSION.SDK_INT >= 30) result.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
        values[time] = Reading(result.get(CaptureResult.LENS_FOCAL_LENGTH), ratio, result.get(CaptureResult.SCALER_CROP_REGION))
        if (values.size > 32) values.remove(values.keys.first())
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
        val shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val aperture = result.get(CaptureResult.LENS_APERTURE)
        val evIndex = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION) ?: 0
        val current = ExposureBaseline(iso, shutterNs, aperture, evIndex)
        latestExposure = current
        val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
        if (aeMode != CaptureResult.CONTROL_AE_MODE_OFF && iso != null && shutterNs != null) {
            lastAutoExposure = current
        }
    }

    @Synchronized fun effectiveFocal(time: Long, requested: Float, active: Rect, ratioControl: Boolean, singleFocal: Float?): Float? {
        val frame = values[time] ?: return null
        val applied = frame.zoom?.takeIf { ratioControl } ?: frame.crop?.let { crop ->
            minOf(active.width().toFloat()/crop.width(), active.height().toFloat()/crop.height())
        } ?: 1f
        if (abs(applied-1f) < 0.01f && frame.mm != null) unitFocal = frame.mm
        return zoomedFocal(unitFocal ?: singleFocal, applied, requested)
    }
}

internal fun zoomedFocal(unitFocal: Float?, applied: Float, requested: Float): Float? {
    if (unitFocal == null || !unitFocal.isFinite() || unitFocal <= 0 || !requested.isFinite() || requested <= 0 ||
        !applied.isFinite() || applied <= 0 ||
        abs(applied-requested) > maxOf(requested*0.03f, 0.02f)) return null
    return unitFocal * applied
}
