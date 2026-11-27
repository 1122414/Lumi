package com.lumi.camera.camera

import android.os.Build
import java.util.Locale

internal enum class OemFamily(val label: String) {
    ONEPLUS_OPPO("OPPO / OnePlus"),
    XIAOMI("Xiaomi / Redmi"),
    VIVO("vivo / iQOO"),
    SAMSUNG("Samsung"),
    HUAWEI_HONOR("Huawei / Honor"),
    GOOGLE("Google Pixel"),
    GENERIC("Android Generic"),
}

internal data class DeviceQuirkProfile(
    val oem: OemFamily,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    /** Whether CameraX Extensions (AUTO/HDR) are considered stable on this OEM + API level. */
    val allowVendorExtensions: Boolean,
    /** Conservative max zoom multiplier to prevent extreme digital blur during AI tracking. */
    val maxTrackingZoomRatio: Float,
    /** Whether Camera2 manual AE override requires clearing scene/extension hooks first. */
    val strictManualExtensionBypass: Boolean,
) {
    val summaryLabel: String
        get() = "${oem.label} · API $sdkInt"
}

/**
 * Multi-vendor Android camera compatibility matrix.
 * Centralizes hardware quirks so CameraSession and NativeProcessingPolicy remain clean and testable.
 */
internal object DeviceQuirks {

    fun resolve(
        manufacturer: String = Build.MANUFACTURER.orEmpty(),
        brand: String = Build.BRAND.orEmpty(),
        model: String = Build.MODEL.orEmpty(),
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): DeviceQuirkProfile {
        val m = manufacturer.trim().lowercase(Locale.ROOT)
        val b = brand.trim().lowercase(Locale.ROOT)
        val oem = when {
            "oneplus" in m || "oppo" in m || "realme" in m ||
                "oneplus" in b || "oppo" in b || "realme" in b -> OemFamily.ONEPLUS_OPPO
            "xiaomi" in m || "redmi" in m || "poco" in m ||
                "xiaomi" in b || "redmi" in b || "poco" in b -> OemFamily.XIAOMI
            "vivo" in m || "iqoo" in m || "vivo" in b || "iqoo" in b -> OemFamily.VIVO
            "samsung" in m || "samsung" in b -> OemFamily.SAMSUNG
            "huawei" in m || "honor" in m || "huawei" in b || "honor" in b -> OemFamily.HUAWEI_HONOR
            "google" in m || "google" in b -> OemFamily.GOOGLE
            else -> OemFamily.GENERIC
        }

        // CameraX Extensions stabilized significantly on Android 12+ (API 31+); older OEM stacks
        // frequently misreport extension support or drop YUV analysis frames when bound together.
        // sdkInt == 0 occurs in host JVM unit tests where android.os.Build constants are uninitialized.
        val allowExtensions = if (sdkInt == 0) true else when (oem) {
            OemFamily.GOOGLE -> sdkInt >= 30
            OemFamily.ONEPLUS_OPPO, OemFamily.SAMSUNG, OemFamily.XIAOMI, OemFamily.VIVO -> sdkInt >= 31
            OemFamily.HUAWEI_HONOR -> sdkInt >= 33
            OemFamily.GENERIC -> sdkInt >= 31
        }

        return DeviceQuirkProfile(
            oem = oem,
            manufacturer = manufacturer.ifBlank { "Unknown" },
            model = model.ifBlank { "Unknown" },
            sdkInt = sdkInt,
            allowVendorExtensions = allowExtensions,
            maxTrackingZoomRatio = 10f,
            strictManualExtensionBypass = true,
        )
    }

    fun current(): DeviceQuirkProfile = resolve()
}
