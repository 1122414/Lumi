package com.lumi.camera.camera

import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import com.lumi.camera.shotplan.AiExposureSuggestion
import com.lumi.camera.shotplan.ProactivityMode
import com.lumi.camera.ui.ExposureUiState
import com.lumi.camera.vision.SceneFrame

/**
 * Owns Camera2 exposure, white-balance, quick-EV, AI-exposure, and native ISP/extension state.
 */
internal class ExposureHardwareController(
    initialExposureMode: ExposureControlMode = ExposureControlMode.AUTO,
    initialNativeProcessing: NativeProcessingMode = NativeProcessingMode.NATIVE_AI,
    private val onExposureState: (ExposureUiState) -> Unit,
    private val onNativeProcessingState: (NativeProcessingUiState) -> Unit,
    private val onMessage: (String) -> Unit,
) {
    var exposureMode: ExposureControlMode = initialExposureMode
        private set
    var nativeProcessingMode: NativeProcessingMode = initialNativeProcessing
        private set
    var capabilities: CameraCapabilities = CameraCapabilities()
    var nativeCapability: NativeProcessingCapability = NativeProcessingCapability()
    var manualExposure: ManualExposureSettings = ManualExposureSettings()
        private set
    var latestAiExposure: AiExposureSuggestion? = null
        private set
    var pendingAiExposure: AiExposureSuggestion? = null
        private set
    var appliedAiExposure: AiExposureSuggestion? = null
        private set
    var quickEvSteps: Float = 0f
        private set

    fun resolveProcessingPlan(): ResolvedProcessingPlan =
        NativeProcessingPolicy.resolve(nativeProcessingMode, exposureMode, nativeCapability)

    fun publishNativeProcessing() {
        onNativeProcessingState(
            NativeProcessingPolicy.uiState(nativeProcessingMode, exposureMode, nativeCapability),
        )
    }

    fun buildTelemetry(
        readings: FocalReadings,
        ambientLux: Float?,
        frame: SceneFrame?,
    ): LiveTelemetry {
        val base = readings.latestExposure()
        val evStep = capabilities.evStep.takeIf { it > 0f } ?: (1f / 6f)
        val stats = frame?.lumaStats
        return LiveTelemetry(
            iso = base.iso,
            shutterNs = base.shutterNs,
            aperture = base.aperture ?: capabilities.primaryAperture,
            evSteps = base.evIndex * evStep,
            ambientLux = ambientLux,
            meanLuma = stats?.meanLuma ?: frame?.meanLuma,
            highlightClipPct = stats?.highlightClipPct,
            shadowClipPct = stats?.shadowClipPct,
            subjectVsBgDiff = stats?.subjectVsBgDiff,
            lightGuidance = stats?.lightGuidance,
        )
    }

    fun publishExposure(telemetry: LiveTelemetry) {
        onExposureState(
            ExposureUiState(
                mode = exposureMode,
                capabilities = capabilities,
                telemetry = telemetry,
                manual = manualExposure,
                pendingAiSuggestion = pendingAiExposure,
                appliedAiSuggestion = appliedAiExposure,
                latestAiSuggestion = latestAiExposure,
            ),
        )
    }

    /** Returns true if CameraX use-cases need rebinding due to extension or latency policy change. */
    fun setNativeProcessingMode(mode: NativeProcessingMode): Boolean {
        if (nativeProcessingMode == mode) return false
        val previousPlan = resolveProcessingPlan()
        nativeProcessingMode = mode
        val nextPlan = resolveProcessingPlan()
        publishNativeProcessing()
        onMessage(
            if (mode == NativeProcessingMode.PURE) "已切换至纯净原片（关闭本机 AI 优化）"
            else "已开启本机 AI 成像优化",
        )
        return previousPlan.extensionMode != nextPlan.extensionMode ||
            previousPlan.minimizeCaptureLatency != nextPlan.minimizeCaptureLatency
    }

    /** Returns true if CameraX needs rebinding (e.g. Manual mode bypassing vendor extension). */
    fun setExposureMode(mode: ExposureControlMode): Boolean {
        if (exposureMode == mode) return false
        val prevProc = resolveProcessingPlan()
        exposureMode = mode
        if (mode == ExposureControlMode.AUTO || mode == ExposureControlMode.MANUAL) {
            pendingAiExposure = null
            appliedAiExposure = null
        }
        val nextProc = resolveProcessingPlan()
        publishNativeProcessing()
        if (prevProc.extensionMode != nextProc.extensionMode) {
            if (nextProc.bypassedForManual) {
                onMessage("M 手动挡已临时避让本机厂商扩展，确保手动参数生效")
            }
            return true
        }
        return false
    }

    /** Returns true if CameraX needs rebinding. */
    fun updateManualExposure(settings: ManualExposureSettings): Boolean {
        manualExposure = settings
        val prevProc = resolveProcessingPlan()
        if (exposureMode != ExposureControlMode.MANUAL) {
            exposureMode = ExposureControlMode.MANUAL
        }
        appliedAiExposure = null
        pendingAiExposure = null
        val nextProc = resolveProcessingPlan()
        publishNativeProcessing()
        return prevProc.extensionMode != nextProc.extensionMode
    }

    fun resetManualExposure() {
        manualExposure = ManualExposureSettings()
        appliedAiExposure = null
        pendingAiExposure = null
    }

    fun setQuickEv(evSteps: Float): Boolean {
        if (exposureMode == ExposureControlMode.MANUAL) return false
        val minEv = capabilities.minEv.coerceAtLeast(-2.5f)
        val maxEv = capabilities.maxEv.coerceAtMost(2.5f)
        val clamped = evSteps.coerceIn(minEv, maxEv)
        if (kotlin.math.abs(clamped - quickEvSteps) < 0.01f) return false
        quickEvSteps = clamped
        return true
    }

    fun resetQuickEv() {
        quickEvSteps = 0f
    }

    fun onAiExposureReceived(suggestion: AiExposureSuggestion?, proactivityMode: ProactivityMode): Boolean {
        if (suggestion != null) {
            latestAiExposure = suggestion
            if (exposureMode == ExposureControlMode.AI_AUTO) {
                if (proactivityMode == ProactivityMode.PROACTIVE) {
                    appliedAiExposure = suggestion
                    pendingAiExposure = null
                    return true
                } else if (proactivityMode == ProactivityMode.MANUAL) {
                    pendingAiExposure = suggestion
                }
            }
        } else if (pendingAiExposure != null) {
            pendingAiExposure = null
        }
        return false
    }

    fun onReferenceExposureReceived(suggestion: AiExposureSuggestion, proactivityMode: ProactivityMode) {
        latestAiExposure = suggestion
        if (exposureMode == ExposureControlMode.AI_AUTO && proactivityMode == ProactivityMode.MANUAL) {
            pendingAiExposure = suggestion
        }
    }

    fun applyPendingAiExposure(): AiExposureSuggestion? {
        val suggestion = pendingAiExposure ?: return null
        pendingAiExposure = null
        appliedAiExposure = suggestion
        onMessage("已应用 AI 光影参数：${suggestion.summaryLabel()}")
        return suggestion
    }

    fun revertAiExposure() {
        appliedAiExposure = null
        pendingAiExposure = null
        onMessage("已还原为自动测光")
    }

    /** Returns Pair(applied, needsRebind). */
    fun applyLatestAiToManual(): Pair<Boolean, Boolean> {
        val suggestion = latestAiExposure ?: return (false to false)
        val prevProc = resolveProcessingPlan()
        manualExposure = ExposureSolver.fromAiSuggestion(suggestion, capabilities)
        exposureMode = ExposureControlMode.MANUAL
        pendingAiExposure = null
        appliedAiExposure = null
        val nextProc = resolveProcessingPlan()
        publishNativeProcessing()
        onMessage("已将 AI 建议填入 M 手动挡，可继续微调")
        return true to (prevProc.extensionMode != nextProc.extensionMode)
    }

    fun clearPendingAiExposure() {
        pendingAiExposure = null
    }

    fun clearTransientAiState() {
        pendingAiExposure = null
        appliedAiExposure = null
        quickEvSteps = 0f
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun applyToHardware(camera: Camera?, readings: FocalReadings) {
        val cam = camera ?: return
        val effectiveSettings = when (exposureMode) {
            ExposureControlMode.AUTO -> ManualExposureSettings(evSteps = quickEvSteps)
            ExposureControlMode.MANUAL -> manualExposure
            ExposureControlMode.AI_AUTO -> appliedAiExposure?.let { ExposureSolver.fromAiSuggestion(it, capabilities) }
                ?: ManualExposureSettings(evSteps = quickEvSteps)
        }
        val baseline = readings.lastAutoExposure().takeIf { it.iso != null && it.shutterNs != null }
            ?: readings.latestExposure()
        val resolved = ExposureSolver.resolve(effectiveSettings, capabilities, baseline)

        val options = CaptureRequestOptions.Builder()
        if (resolved.aeAuto) {
            // Clear AE_MODE override so CameraX ImageCapture owns ON / ON_AUTO_FLASH / ON_ALWAYS_FLASH.
            options.clearCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE)
            options.clearCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY)
            options.clearCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME)
            runCatching { cam.cameraControl.setExposureCompensationIndex(resolved.evIndex) }
        } else {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            resolved.manualIso?.let { options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it) }
            resolved.manualShutterNs?.let { options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        }

        if (resolved.wbKelvin != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            options.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX,
            )
            options.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                ExposureSolver.kelvinToRggbVector(resolved.wbKelvin),
            )
        } else {
            options.clearCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE)
            options.clearCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE)
            options.clearCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS)
        }

        if (resolved.aperture != null) {
            options.setCaptureRequestOption(CaptureRequest.LENS_APERTURE, resolved.aperture)
        } else {
            options.clearCaptureRequestOption(CaptureRequest.LENS_APERTURE)
        }

        val processing = resolveProcessingPlan()
        if (processing.sceneMode != null) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, processing.sceneMode)
        } else {
            options.clearCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE)
        }
        if (processing.edgeMode != null) {
            options.setCaptureRequestOption(CaptureRequest.EDGE_MODE, processing.edgeMode)
        } else {
            options.clearCaptureRequestOption(CaptureRequest.EDGE_MODE)
        }
        if (processing.noiseReductionMode != null) {
            options.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, processing.noiseReductionMode)
        } else {
            options.clearCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE)
        }

        runCatching {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(options.build())
        }.onFailure { e ->
            Log.w("LumiCamera", "Unable to apply Camera2 exposure options", e)
        }
    }
}
