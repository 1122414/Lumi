package com.lumi.camera.camera

import android.hardware.camera2.CameraMetadata
import androidx.camera.extensions.ExtensionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S6NativeProcessingTest {

    @Test
    fun `native ai picks AUTO over HDR when supported`() {
        val cap = NativeProcessingCapability(
            supportedExtensions = setOf(ExtensionMode.HDR, ExtensionMode.AUTO, ExtensionMode.NIGHT),
        )
        val plan = NativeProcessingPolicy.resolve(
            mode = NativeProcessingMode.NATIVE_AI,
            exposureMode = ExposureControlMode.AUTO,
            capability = cap,
        )
        assertEquals(ExtensionMode.AUTO, plan.extensionMode)
        assertFalse(plan.bypassedForManual)
        assertFalse(plan.minimizeCaptureLatency)
        assertNull(plan.edgeMode)
        assertNull(plan.noiseReductionMode)
    }

    @Test
    fun `native ai falls back to HDR when AUTO unavailable`() {
        val cap = NativeProcessingCapability(supportedExtensions = setOf(ExtensionMode.HDR))
        val plan = NativeProcessingPolicy.resolve(
            mode = NativeProcessingMode.NATIVE_AI,
            exposureMode = ExposureControlMode.AI_AUTO,
            capability = cap,
        )
        assertEquals(ExtensionMode.HDR, plan.extensionMode)
        assertFalse(plan.bypassedForManual)
    }

    @Test
    fun `manual exposure bypasses vendor extension to protect manual ISO and shutter`() {
        val cap = NativeProcessingCapability(supportedExtensions = setOf(ExtensionMode.AUTO))
        val plan = NativeProcessingPolicy.resolve(
            mode = NativeProcessingMode.NATIVE_AI,
            exposureMode = ExposureControlMode.MANUAL,
            capability = cap,
        )
        assertNull(plan.extensionMode)
        assertTrue(plan.bypassedForManual)
        val ui = NativeProcessingPolicy.uiState(
            mode = NativeProcessingMode.NATIVE_AI,
            exposureMode = ExposureControlMode.MANUAL,
            capability = cap,
        )
        assertTrue(ui.bypassedForManual)
        assertTrue(ui.statusSummary.contains("M 手动挡"))
    }

    @Test
    fun `pure mode disables vendor extensions and selects hardware-supported softening modes`() {
        val cap = NativeProcessingCapability(
            supportedExtensions = setOf(ExtensionMode.AUTO, ExtensionMode.HDR),
            isp = IspProcessingModes(
                edgeModes = setOf(CameraMetadata.EDGE_MODE_OFF, CameraMetadata.EDGE_MODE_FAST, CameraMetadata.EDGE_MODE_HIGH_QUALITY),
                noiseReductionModes = setOf(CameraMetadata.NOISE_REDUCTION_MODE_FAST, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL),
                sceneModes = setOf(CameraMetadata.CONTROL_SCENE_MODE_DISABLED, CameraMetadata.CONTROL_SCENE_MODE_HDR),
            ),
        )
        val plan = NativeProcessingPolicy.resolve(
            mode = NativeProcessingMode.PURE,
            exposureMode = ExposureControlMode.AUTO,
            capability = cap,
        )
        assertNull(plan.extensionMode)
        assertTrue(plan.minimizeCaptureLatency)
        assertEquals(CameraMetadata.CONTROL_SCENE_MODE_DISABLED, plan.sceneMode)
        assertEquals(CameraMetadata.EDGE_MODE_FAST, plan.edgeMode)
        assertEquals(CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL, plan.noiseReductionMode)
    }

    @Test
    fun `pure mode omits unsupported Camera2 edge and noise reduction values`() {
        val cap = NativeProcessingCapability(
            isp = IspProcessingModes(
                edgeModes = setOf(CameraMetadata.EDGE_MODE_HIGH_QUALITY),
                noiseReductionModes = setOf(CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY),
                sceneModes = setOf(CameraMetadata.CONTROL_SCENE_MODE_HDR),
            ),
        )
        val plan = NativeProcessingPolicy.resolve(
            mode = NativeProcessingMode.PURE,
            exposureMode = ExposureControlMode.AUTO,
            capability = cap,
        )
        assertTrue(plan.minimizeCaptureLatency)
        assertNull(plan.edgeMode)
        assertNull(plan.noiseReductionMode)
        assertNull(plan.sceneMode)
    }

    @Test
    fun `wire serialization round trips and defaults safely`() {
        assertEquals(NativeProcessingMode.PURE, NativeProcessingMode.fromWire("pure"))
        assertEquals(NativeProcessingMode.NATIVE_AI, NativeProcessingMode.fromWire("native_ai"))
        assertEquals(NativeProcessingMode.NATIVE_AI, NativeProcessingMode.fromWire("unknown"))
        assertEquals(NativeProcessingMode.PURE, NativeProcessingMode.NATIVE_AI.toggled())
    }
}
