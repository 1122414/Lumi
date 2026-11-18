package com.lumi.camera.camera

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics as C
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlin.math.hypot
import kotlin.math.round

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal fun rearZoomStops(context: Context, camera: CameraInfo): List<Float> {
    val manager = context.getSystemService(CameraManager::class.java)
    fun read(id: String): C? = try {
        manager.getCameraCharacteristics(id)
    } catch (_: CameraAccessException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }
    val logical = read(Camera2CameraInfo.from(camera).cameraId) ?: return listOf(1f)
    fun focalScales(meta: C): List<Float> {
        val size = meta[C.SENSOR_INFO_PHYSICAL_SIZE] ?: return emptyList()
        val diagonal = hypot(size.width, size.height)
        if (diagonal <= 0f || !diagonal.isFinite()) return emptyList()
        return meta[C.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.map { it / diagonal }.orEmpty()
    }
    val main = focalScales(logical)
    val scales = main.toMutableList()
    if (Build.VERSION.SDK_INT >= 28) {
        for (id in logical.physicalCameraIds) {
            val physical = read(id) ?: continue
            if (physical[C.REQUEST_AVAILABLE_CAPABILITIES]?.contains(C.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME) == true) continue
            scales += focalScales(physical)
        }
    }
    return lensZoomStops(main.firstOrNull(), scales, Build.MODEL)
}

internal fun lensZoomStops(unitScale: Float?, scales: List<Float>, model: String): List<Float> {
    val stops = sortedSetOf(1f)
    if (unitScale == null || !unitScale.isFinite() || unitScale <= 0f) return stops.toList()
    for (scale in scales) {
        val ratio = round(scale / unitScale * 10f) / 10f
        if (ratio.isFinite() && ratio > 0f) stops.add(ratio)
    }
    // The PKX110 stock photo dial also offers 4×; Camera2 exposes only its 1× and ~2× lenses.
    if (model == "PKX110" && 2f in stops) stops.add(4f)
    return stops.toList()
}
