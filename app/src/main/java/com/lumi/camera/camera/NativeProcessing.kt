package com.lumi.camera.camera

import android.hardware.camera2.CameraMetadata
import androidx.camera.extensions.ExtensionMode

internal enum class NativeProcessingMode(val wire: String, val label: String) {
    NATIVE_AI("native_ai", "本机优化"),
    PURE("pure", "纯净原片");

    fun toggled(): NativeProcessingMode = when (this) {
        NATIVE_AI -> PURE
        PURE -> NATIVE_AI
    }

    companion object {
        fun fromWire(raw: String?): NativeProcessingMode =
            entries.firstOrNull { it.wire == raw } ?: NATIVE_AI
    }
}

internal data class IspProcessingModes(
    val edgeModes: Set<Int> = emptySet(),
    val noiseReductionModes: Set<Int> = emptySet(),
    val sceneModes: Set<Int> = emptySet(),
)

internal data class NativeProcessingCapability(
    val supportedExtensions: Set<Int> = emptySet(),
    val isp: IspProcessingModes = IspProcessingModes(),
) {
    val preferredExtensionMode: Int?
        get() = when {
            ExtensionMode.AUTO in supportedExtensions -> ExtensionMode.AUTO
            ExtensionMode.HDR in supportedExtensions -> ExtensionMode.HDR
            else -> null
        }

    val hasVendorExtension: Boolean
        get() = preferredExtensionMode != null
}

internal data class ResolvedProcessingPlan(
    val extensionMode: Int? = null,
    val bypassedForManual: Boolean = false,
    val minimizeCaptureLatency: Boolean = false,
    val sceneMode: Int? = null,
    val edgeMode: Int? = null,
    val noiseReductionMode: Int? = null,
)

internal data class NativeProcessingUiState(
    val mode: NativeProcessingMode = NativeProcessingMode.NATIVE_AI,
    val hasVendorExtension: Boolean = false,
    val activeExtensionLabel: String? = null,
    val bypassedForManual: Boolean = false,
    val statusSummary: String = "使用系统默认成像管线",
)

internal object NativeProcessingPolicy {
    fun resolve(
        mode: NativeProcessingMode,
        exposureMode: ExposureControlMode,
        capability: NativeProcessingCapability,
    ): ResolvedProcessingPlan {
        if (mode == NativeProcessingMode.NATIVE_AI) {
            val preferred = capability.preferredExtensionMode
            if (preferred != null && exposureMode == ExposureControlMode.MANUAL) {
                return ResolvedProcessingPlan(
                    extensionMode = null,
                    bypassedForManual = true,
                    minimizeCaptureLatency = false,
                )
            }
            return ResolvedProcessingPlan(
                extensionMode = preferred,
                bypassedForManual = false,
                minimizeCaptureLatency = false,
            )
        }

        // PURE mode: disable vendor extensions and dial back aggressive ISP sharpening / smoothing.
        val sceneMode = CameraMetadata.CONTROL_SCENE_MODE_DISABLED
            .takeIf { it in capability.isp.sceneModes || capability.isp.sceneModes.isEmpty() }
        val edgeMode = when {
            CameraMetadata.EDGE_MODE_FAST in capability.isp.edgeModes -> CameraMetadata.EDGE_MODE_FAST
            CameraMetadata.EDGE_MODE_OFF in capability.isp.edgeModes -> CameraMetadata.EDGE_MODE_OFF
            else -> null
        }
        val noiseReductionMode = when {
            CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL in capability.isp.noiseReductionModes ->
                CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL
            CameraMetadata.NOISE_REDUCTION_MODE_FAST in capability.isp.noiseReductionModes ->
                CameraMetadata.NOISE_REDUCTION_MODE_FAST
            else -> null
        }
        return ResolvedProcessingPlan(
            extensionMode = null,
            bypassedForManual = false,
            minimizeCaptureLatency = true,
            sceneMode = sceneMode,
            edgeMode = edgeMode,
            noiseReductionMode = noiseReductionMode,
        )
    }

    fun uiState(
        mode: NativeProcessingMode,
        exposureMode: ExposureControlMode,
        capability: NativeProcessingCapability,
    ): NativeProcessingUiState {
        val plan = resolve(mode, exposureMode, capability)
        val extLabel = plan.extensionMode?.let(::extensionLabel)
        val summary = when {
            mode == NativeProcessingMode.PURE ->
                "已关闭厂商 AI 扩展并弱化锐化涂抹，适合搭配 Lumi 滤镜"
            plan.bypassedForManual ->
                "M 手动挡生效中，已临时避让厂商扩展以保留手动参数"
            extLabel != null ->
                "已开启本机厂商优化（$extLabel）"
            else ->
                "当前镜头未向第三方开放厂商扩展，使用系统默认成像"
        }
        return NativeProcessingUiState(
            mode = mode,
            hasVendorExtension = capability.hasVendorExtension,
            activeExtensionLabel = extLabel,
            bypassedForManual = plan.bypassedForManual,
            statusSummary = summary,
        )
    }

    fun extensionLabel(mode: Int): String = when (mode) {
        ExtensionMode.AUTO -> "智能优化"
        ExtensionMode.HDR -> "HDR"
        ExtensionMode.NIGHT -> "夜景"
        else -> "扩展"
    }
}
