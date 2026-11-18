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

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal class CameraOptics(info: CameraInfo, private val readings: FocalReadings) {
    private val meta = Camera2CameraInfo.from(info)
    private val focals = meta.getCameraCharacteristic(C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
    private val physical = meta.getCameraCharacteristic(C.SENSOR_INFO_PHYSICAL_SIZE)
    private val pixels = meta.getCameraCharacteristic(C.SENSOR_INFO_PIXEL_ARRAY_SIZE)
    private val active = meta.getCameraCharacteristic(C.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    private val ratioControl = Build.VERSION.SDK_INT >= 30 && meta.getCameraCharacteristic(C.CONTROL_ZOOM_RATIO_RANGE) != null
    private val realtime = meta.getCameraCharacteristic(C.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        C.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

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
            principal.x.toDouble() / ref.width, principal.y.toDouble() / ref.height)
            .takeIf { it.fx > 0 && it.fy > 0 && it.cx in 0.0..1.0 && it.cy in 0.0..1.0 }
    }
}

// CameraX's buffer transform describes the bound stream. HAL zoom changes the
// field of view within that stream, including across physical-lens switches.
internal class FocalReadings : CameraCaptureSession.CaptureCallback() {
    private data class Reading(val mm: Float?, val zoom: Float?, val crop: Rect?)
    private val values = LinkedHashMap<Long, Reading>()
    private var unitFocal: Float? = null

    @Synchronized override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest,
        result: TotalCaptureResult) {
        val time = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val ratio = if (Build.VERSION.SDK_INT >= 30) result.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
        values[time] = Reading(result.get(CaptureResult.LENS_FOCAL_LENGTH), ratio, result.get(CaptureResult.SCALER_CROP_REGION))
        if (values.size > 32) values.remove(values.keys.first())
    }

    @Synchronized fun effectiveFocal(time: Long, requested: Float, active: Rect, ratioControl: Boolean, singleFocal: Float?): Float? {
        val frame = values[time] ?: return null
        val applied = frame.zoom?.takeIf { ratioControl } ?: frame.crop?.let { crop ->
            minOf(active.width().toFloat()/crop.width(), active.height().toFloat()/crop.height())
        } ?: 1f
        if (abs(applied-1f) < 0.001f && frame.mm != null) unitFocal = frame.mm
        return zoomedFocal(unitFocal ?: singleFocal, applied, requested)
    }
}

internal fun zoomedFocal(unitFocal: Float?, applied: Float, requested: Float): Float? {
    if (unitFocal == null || !unitFocal.isFinite() || unitFocal <= 0 || !requested.isFinite() || requested <= 0 ||
        !applied.isFinite() || applied <= 0 ||
        abs(applied-requested) > requested*0.005f) return null
    return unitFocal * applied
}
