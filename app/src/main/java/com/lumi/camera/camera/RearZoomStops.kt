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
    val rear = logical[C.LENS_FACING] == C.LENS_FACING_BACK
    return lensZoomStops(main.firstOrNull(), scales, Build.MODEL, rear)
}

internal fun lensZoomStops(unitScale: Float?, scales: List<Float>, model: String, rear: Boolean = true): List<Float> {
    val stops = sortedSetOf(1f)
    if (unitScale == null || !unitScale.isFinite() || unitScale <= 0f) return stops.toList()
    for (scale in scales) {
        val ratio = round(scale / unitScale * 10f) / 10f
        if (ratio.isFinite() && ratio > 0f) stops.add(ratio)
    }
    // The PKX110 stock photo dial also offers 4×; Camera2 exposes only its 1× and ~2× lenses.
    if (rear && model == "PKX110" && 2f in stops) stops.add(4f)
    return stops.toList()
}

/**
 * Combines discovered physical lens ratios with standard focal stops supported by the camera's
 * actual ZoomState range. Many Android OEM HALs (e.g. OnePlus Ace 2 / Ace 2 Pro, Xiaomi, vivo)
 * expose only a single logical camera without physicalCameraIds while still supporting 0.6×..10×
 * zoom via ZoomState — synthesizing standard stops prevents the dial from collapsing to just [1×].
 */
internal fun resolveZoomStops(
    physicalStops: List<Float>,
    minZoom: Float,
    maxZoom: Float,
    rear: Boolean = true,
): List<Float> {
    if (!minZoom.isFinite() || !maxZoom.isFinite() || maxZoom <= minZoom) return listOf(1f)
    val range = minZoom..maxZoom
    val stops = sortedSetOf<Float>()
    for (s in physicalStops) {
        if (s.isFinite() && s in range) stops.add(s)
    }
    if (1f in range) stops.add(1f)
    if (stops.isEmpty()) stops.add(minZoom)

    // Ultrawide shortcut whenever the camera supports wide-angle zoom (< 0.95×).
    if (minZoom <= 0.92f && stops.none { it < 0.95f }) {
        val wide = (round(minZoom * 10f) / 10f).coerceIn(minZoom, 0.9f)
        stops.add(wide)
    }

    if (rear) {
        // Standard 2× portrait/in-sensor crop stop.
        if (maxZoom >= 2f && stops.none { it in 1.7f..2.4f }) {
            stops.add(2f)
        }
        // Mid-telephoto 3× stop when no native telephoto >= 2.7× is exposed.
        if (stops.size < 4 && maxZoom >= 3f && stops.none { it >= 2.7f }) {
            stops.add(3f)
        }
        // Longer 5× shortcut when room remains and no >= 4× stop is already present.
        if (stops.size < 4 && maxZoom >= 5f && stops.none { it >= 4f }) {
            stops.add(5f)
        }
    }

    return stops.toList().take(4)
}
