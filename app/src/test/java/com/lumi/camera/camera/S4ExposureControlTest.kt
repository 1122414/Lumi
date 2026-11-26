package com.lumi.camera.camera

import com.lumi.camera.guidance.Uv
import com.lumi.camera.shotplan.AiExposureSuggestion
import com.lumi.camera.shotplan.ChatProtocol
import com.lumi.camera.shotplan.CompositionPreset
import com.lumi.camera.shotplan.ModelReply
import com.lumi.camera.shotplan.PlanResult
import com.lumi.camera.shotplan.SubjectCandidate
import com.lumi.camera.vision.FaceBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S4ExposureControlTest {
    private val pkxCaps = CameraCapabilities(
        isoRange = 100..6400,
        shutterRangeNs = 78_125L..30_000_000_000L,
        evIndexRange = -18..18,
        evStep = 1f / 6f,
        apertures = listOf(1.88f),
        supportsManualSensor = true,
        supportsManualWb = true,
    )

    @Test fun perItemAutoExposureSolverHandlesAllFourPriorityModesSafely() {
        val baseline = ExposureBaseline(iso = 200, shutterNs = 16_666_667L, aperture = 1.88f, evIndex = 0)

        // 1. Both ISO & S AUTO -> Hardware AE ON with EV compensation (-1.0 EV = -6 steps).
        val evOnly = ExposureSolver.resolve(ManualExposureSettings(evSteps = -1.0f), pkxCaps, baseline)
        assertTrue(evOnly.aeAuto)
        assertEquals(-6, evOnly.evIndex)
        assertNull(evOnly.manualIso)
        assertNull(evOnly.manualShutterNs)

        // 2. Shutter Priority (lock S = 1/250s = 4ms, ISO = AUTO): shutter is ~4.17x faster -> ISO auto-boosts ~4.17x (~833).
        val shutterPriority = ExposureSolver.resolve(
            ManualExposureSettings(shutterNs = 4_000_000L, iso = null),
            pkxCaps,
            baseline,
        )
        assertFalse(shutterPriority.aeAuto)
        assertEquals(4_000_000L, shutterPriority.manualShutterNs)
        assertTrue("derived ISO should compensate faster shutter (~833), got ${shutterPriority.manualIso}",
            shutterPriority.manualIso in 780..890)

        // 3. ISO Priority (lock ISO = 800, S = AUTO): ISO is 4x higher -> shutter speeds up ~4x (~4.17ms).
        val isoPriority = ExposureSolver.resolve(
            ManualExposureSettings(iso = 800, shutterNs = null),
            pkxCaps,
            baseline,
        )
        assertFalse(isoPriority.aeAuto)
        assertEquals(800, isoPriority.manualIso)
        assertTrue("derived shutter should compensate higher ISO (~4.17ms), got ${isoPriority.manualShutterNs}",
            isoPriority.manualShutterNs in 3_800_000L..4_600_000L)

        // 4. Full manual clamps strictly to hardware bounds.
        val clamped = ExposureSolver.resolve(
            ManualExposureSettings(iso = 99999, shutterNs = 10L, wbKelvin = 12000),
            pkxCaps,
            baseline,
        )
        assertEquals(6400, clamped.manualIso)
        assertEquals(78_125L, clamped.manualShutterNs)
        assertEquals(ExposureSolver.MAX_KELVIN, clamped.wbKelvin)
        assertNull("fixed aperture phone must not emit aperture override", clamped.aperture)
    }

    @Test fun kelvinGainsProduceWarmForHighKelvinAndCoolForLowKelvin() {
        val (rNeutral, gNeutral, bNeutral) = ExposureSolver.kelvinToRgbGains(5200)
        assertEquals(1.0f, rNeutral, 0.02f)
        assertEquals(1.0f, gNeutral, 0.001f)
        assertEquals(1.0f, bNeutral, 0.02f)

        val (rWarm, _, bWarm) = ExposureSolver.kelvinToRgbGains(6800)
        assertTrue("6800K should warm image (R > B), got R=$rWarm B=$bWarm", rWarm > bWarm)

        val (rCool, _, bCool) = ExposureSolver.kelvinToRgbGains(3200)
        assertTrue("3200K should cool image (B > R), got R=$rCool B=$bCool", bCool > rCool)
    }

    @Test fun telemetryPromptAndAiExposureJsonParseEndToEnd() {
        val telemetry = LiveTelemetry(
            iso = 400,
            shutterNs = 16_666_667L,
            aperture = 1.88f,
            ambientLux = 120f,
            meanLuma = 96,
            highlightClipPct = 11,
            shadowClipPct = 18,
            subjectVsBgDiff = -30,
        )
        val summary = telemetry.promptSummary(pkxCaps)!!
        assertTrue("telemetry summary should include lux", "120lux" in summary)
        assertTrue("telemetry summary should flag backlight", "偏逆光" in summary)
        assertTrue("telemetry summary should include hardware ISO bounds", "ISO[100-6400]" in summary)

        val candidates = listOf(SubjectCandidate(1, "人物", true, FaceBox(0.3, 0.2, 0.7, 0.8), Uv(0.5, 0.5)))
        val prompt = ChatProtocol.promptFor(
            preset = CompositionPreset.AUTO,
            candidates = candidates,
            telemetrySummary = summary,
            requestExposureAdvice = true,
        )
        assertTrue("prompt must include live telemetry", "偏逆光" in prompt)
        assertTrue("prompt must request exposure object when AI exposure is active", "\"exposure\"" in prompt)

        val reply = ModelReply.from(
            fields = mapOf(
                "candidate" to 1,
                "name" to "女孩",
                "choice" to "left_third",
                "reason" to "逆光轮廓柔和",
                "exposure" to mapOf(
                    "ev" to 0.7,
                    "wb" to 5800,
                    "reason" to "逆光提亮人脸微暖调",
                ),
            ),
            complete = true,
            candidates = candidates,
        )!!
        val plan = (reply.decision as PlanResult.Accepted).value
        assertNotNull(plan.exposureSuggestion)
        assertEquals(0.7f, plan.exposureSuggestion!!.evOffset!!, 0.01f)
        assertEquals(5800, plan.exposureSuggestion!!.wbKelvin)
        assertEquals("EV+0.7 · 5800K", plan.exposureSuggestion!!.summaryLabel())

        val mapped = ExposureSolver.fromAiSuggestion(plan.exposureSuggestion!!, pkxCaps)
        assertEquals(0.7f, mapped.evSteps, 0.01f)
        assertEquals(5800, mapped.wbKelvin)
        assertNull(mapped.iso)
        assertNull(mapped.shutterNs)
    }
}
