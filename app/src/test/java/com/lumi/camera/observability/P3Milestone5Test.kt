package com.lumi.camera.observability

import androidx.camera.extensions.ExtensionMode
import com.lumi.camera.camera.DeviceQuirks
import com.lumi.camera.camera.ExposureControlMode
import com.lumi.camera.camera.NativeProcessingCapability
import com.lumi.camera.camera.NativeProcessingMode
import com.lumi.camera.camera.NativeProcessingPolicy
import com.lumi.camera.camera.OemFamily
import com.lumi.camera.shotplan.ModelConfig
import com.lumi.camera.shotplan.initialModelConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P3Milestone5Test {

    @Test
    fun deviceQuirksClassifyMajorOemsAndGateLegacyExtensions() {
        val onePlus = DeviceQuirks.resolve("OnePlus", "OnePlus", "PKX110", sdkInt = 35)
        assertEquals(OemFamily.ONEPLUS_OPPO, onePlus.oem)
        assertTrue(onePlus.allowVendorExtensions)

        val legacyXiaomi = DeviceQuirks.resolve("Xiaomi", "Redmi", "M2007J20C", sdkInt = 30)
        assertEquals(OemFamily.XIAOMI, legacyXiaomi.oem)
        assertFalse(legacyXiaomi.allowVendorExtensions)

        val pixel = DeviceQuirks.resolve("Google", "google", "Pixel 6", sdkInt = 30)
        assertEquals(OemFamily.GOOGLE, pixel.oem)
        assertTrue(pixel.allowVendorExtensions)

        val huaweiLegacy = DeviceQuirks.resolve("HUAWEI", "HUAWEI", "NOH-AN00", sdkInt = 31)
        assertEquals(OemFamily.HUAWEI_HONOR, huaweiLegacy.oem)
        assertFalse(huaweiLegacy.allowVendorExtensions)

        // NativeProcessingPolicy respects DeviceQuirks.allowVendorExtensions = false
        val cap = NativeProcessingCapability(supportedExtensions = setOf(ExtensionMode.AUTO))
        val blockedPlan = NativeProcessingPolicy.resolve(
            mode = NativeProcessingMode.NATIVE_AI,
            exposureMode = ExposureControlMode.AUTO,
            capability = cap,
            quirks = legacyXiaomi,
        )
        assertNull(blockedPlan.extensionMode)

        val allowedPlan = NativeProcessingPolicy.resolve(
            mode = NativeProcessingMode.NATIVE_AI,
            exposureMode = ExposureControlMode.AUTO,
            capability = cap,
            quirks = onePlus,
        )
        assertEquals(ExtensionMode.AUTO, allowedPlan.extensionMode)
    }

    @Test
    fun telemetryRecordsNorthStarMetricsAndPercentiles() {
        LumiTelemetry.reset()
        LumiTelemetry.markProcessStart(nowElapsedMs = 1_000L)
        LumiTelemetry.markFirstViewfinderFrame(nowElapsedMs = 1_520L)

        LumiTelemetry.recordAiTriggered(launchedOfflineBaseline = true)
        LumiTelemetry.recordAiTriggered(launchedOfflineBaseline = true)
        LumiTelemetry.recordCloudOutcome(succeeded = true, latencyMs = 800L)
        LumiTelemetry.recordCloudOutcome(succeeded = true, latencyMs = 1_200L)
        LumiTelemetry.recordCloudOutcome(succeeded = true, latencyMs = 2_400L)
        LumiTelemetry.recordCloudOutcome(succeeded = false)

        LumiTelemetry.recordGuidanceReady()
        LumiTelemetry.recordGuidanceReady()
        LumiTelemetry.recordCapture(wasReady = true, shutterLatencyMs = 180L, filterSaveMs = 260L)
        LumiTelemetry.recordCapture(wasReady = false, shutterLatencyMs = 120L, filterSaveMs = null)

        val snap = LumiTelemetry.snapshot()
        assertEquals(520L, snap.coldStartToFirstFrameMs)
        assertEquals(150L, snap.avgShutterLatencyMs)
        assertEquals(260L, snap.avgFilterSaveMs)
        assertEquals(2, snap.aiTriggeredCount)
        assertEquals(2, snap.offlineBaselineCount)
        assertEquals(75, snap.cloudSuccessRatePct)
        assertEquals(1_200L, snap.cloudLatencyP50Ms)
        assertEquals(2_400L, snap.cloudLatencyP95Ms)
        assertEquals(50, snap.readyConversionPct)
        assertTrue(snap.compactSummary().contains("首帧 520ms"))
        assertTrue(snap.detailedReport().contains("云端导演"))

        LumiTelemetry.reset()
    }

    @Test
    fun crashGuardRedactsSecretsAndCredentials() {
        val leaked = """
            java.io.IOException: failed calling https://user:pass123@api.example.com/v1
            Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.secretPayload
            key=sk-proj-1234567890abcdef
            api_key=my_super_secret_token
        """.trimIndent()
        val sanitized = LumiCrashGuard.sanitize(leaked)
        assertFalse(sanitized.contains("pass123"))
        assertFalse(sanitized.contains("secretPayload"))
        assertFalse(sanitized.contains("sk-proj-1234567890abcdef"))
        assertFalse(sanitized.contains("my_super_secret_token"))
        assertTrue(sanitized.contains("[REDACTED]"))
        assertTrue(sanitized.contains("java.io.IOException"))
    }

    @Test
    fun managedGatewayConfigWorksWithoutClientApiKeyInRelease() {
        val gateway = ModelConfig(
            address = "https://gateway.lumi.camera/v1",
            model = "lumi-director",
            apiKey = "",
            managedGateway = true,
        )
        assertNull(gateway.validationError(debug = false))

        val insecureGateway = gateway.copy(address = "http://gateway.lumi.camera/v1")
        assertNotNull(insecureGateway.validationError(debug = false))

        val resolved = initialModelConfig(
            hasSaved = false,
            debug = false,
            saved = { error("no saved") },
            defaults = { error("no debug defaults") },
            managedGateway = { gateway },
        )
        assertEquals(gateway, resolved)
    }
}
