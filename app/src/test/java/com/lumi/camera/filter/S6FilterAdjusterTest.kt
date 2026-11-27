package com.lumi.camera.filter

import com.lumi.camera.camera.LiveTelemetry
import com.lumi.camera.guidance.Uv
import com.lumi.camera.shotplan.ChatProtocol
import com.lumi.camera.shotplan.CompositionPreset
import com.lumi.camera.shotplan.PlanResult
import com.lumi.camera.shotplan.Recommendation
import com.lumi.camera.shotplan.SubjectCandidate
import com.lumi.camera.vision.FaceBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S6FilterAdjusterTest {

    @Test
    fun `all 10 presets (original plus 9 looks) exist with unique ids and valid bounds`() {
        assertEquals(10, FilterPreset.selectablePresets.size)
        val ids = FilterPreset.selectablePresets.map { it.id }.toSet()
        assertEquals(10, ids.size)
        assertTrue(setOf("original", "oxygen", "mist", "golden", "classic_film", "night_mood", "soft_portrait", "monochrome", "retro_ccd", "cool_film").all { it in ids })
        FilterPreset.selectablePresets.forEach { preset ->
            val clamped = preset.baseGrade.clamped()
            assertEquals(clamped, preset.baseGrade)
        }
    }

    @Test
    fun `original preset always resolves to identity grade`() {
        val state = FilterAdjuster.resolve(
            mode = FilterSelectionMode.MANUAL,
            selectedPreset = FilterPreset.ORIGINAL,
            intensity = 1.0f,
            aiAdaptiveEnabled = true,
            aiSuggestion = AiFilterSuggestion(FilterPreset.MIST, FilterDelta(shadows = 0.2f), "阴雨通透"),
            telemetry = LiveTelemetry(ambientLux = 500f, highlightClipPct = 20),
        )
        assertTrue(state.resolvedGrade.isIdentity)
        assertFalse(state.isActive)
    }

    @Test
    fun `ai smart mode uses local telemetry heuristic before cloud reply and switches to DS suggestion after`() {
        val sunny = FilterAdjuster.resolve(
            mode = FilterSelectionMode.AI_SMART,
            selectedPreset = FilterPreset.ORIGINAL,
            intensity = 0.85f,
            aiAdaptiveEnabled = true,
            aiSuggestion = null,
            telemetry = LiveTelemetry(ambientLux = 28000f),
        )
        assertEquals(FilterPreset.OXYGEN, sunny.resolvedPreset)
        assertEquals("AI·氧气", sunny.badgeLabel)

        val night = FilterAdjuster.resolve(
            mode = FilterSelectionMode.AI_SMART,
            selectedPreset = FilterPreset.ORIGINAL,
            intensity = 0.85f,
            aiAdaptiveEnabled = true,
            aiSuggestion = null,
            telemetry = LiveTelemetry(ambientLux = 15f),
        )
        assertEquals(FilterPreset.NIGHT_MOOD, night.resolvedPreset)

        val aiPicked = AiFilterSuggestion(FilterPreset.MIST, FilterDelta(shadows = 0.12f, temperature = -0.05f), "雨后清透")
        val withAi = FilterAdjuster.resolve(
            mode = FilterSelectionMode.AI_SMART,
            selectedPreset = FilterPreset.ORIGINAL,
            intensity = 0.85f,
            aiAdaptiveEnabled = true,
            aiSuggestion = aiPicked,
            telemetry = LiveTelemetry(ambientLux = 1200f),
        )
        assertEquals(FilterPreset.MIST, withAi.resolvedPreset)
        assertEquals("雨雾清新 · 雨后清透", withAi.reason)
    }

    @Test
    fun `telemetry adaptive delta lifts backlit shadows protects blown highlights and suppresses high-ISO grain`() {
        val backlit = LiveTelemetry(
            iso = 3200,
            ambientLux = 25f,
            highlightClipPct = 20,
            subjectVsBgDiff = -35,
        )
        val delta = FilterAdjuster.telemetryDelta(FilterPreset.CLASSIC_FILM, backlit)
        assertTrue("Expected negative highlight adjustment", delta.highlights < -0.10f)
        assertTrue("Expected positive shadow lift", delta.shadows > 0.10f)
        assertTrue("Expected grain suppression at high ISO", delta.grain < -0.05f)
    }

    @Test
    fun `ai filter suggestion parser clamps extreme deltas and rejects original or unknown ids`() {
        val parsed = AiFilterSuggestion.parse(
            mapOf(
                "preset" to "cool_film",
                "delta" to mapOf("temp" to -9.0, "shadows" to 5.0, "highlights" to -5.0),
                "reason" to "阴天清冷电影感",
            )
        )
        assertNotNull(parsed)
        assertEquals(FilterPreset.COOL_FILM, parsed!!.preset)
        assertEquals(-0.16f, parsed.delta.temperature, 0.001f)
        assertEquals(0.28f, parsed.delta.shadows, 0.001f)
        assertEquals(-0.25f, parsed.delta.highlights, 0.001f)

        assertNull(AiFilterSuggestion.parse(mapOf("preset" to "original")))
        assertNull(AiFilterSuggestion.parse(mapOf("preset" to "unknown_filter")))
    }

    @Test
    fun `monochrome keeps full desaturation even when AI delta requests saturation boost`() {
        val state = FilterAdjuster.resolve(
            mode = FilterSelectionMode.MANUAL,
            selectedPreset = FilterPreset.MONOCHROME,
            intensity = 1.0f,
            aiAdaptiveEnabled = true,
            aiSuggestion = AiFilterSuggestion(FilterPreset.MONOCHROME, FilterDelta(saturation = 0.25f), "黑白光影"),
            telemetry = LiveTelemetry(),
        )
        assertEquals(-1.0f, state.resolvedGrade.saturation, 0.0001f)
    }

    @Test
    fun `recommendation protocol parses optional filter suggestion and prompt includes filter guidance`() {
        val prompt = ChatProtocol.promptFor(
            preset = CompositionPreset.AUTO,
            candidates = listOf(SubjectCandidate(1, "女孩", person = true, box = FaceBox(0.3, 0.2, 0.7, 0.8), seed = Uv(0.5, 0.5))),
            requestFilterAdvice = true,
            activeFilterPreset = null,
        )
        assertTrue(prompt.contains("\"filter\""))
        assertTrue(prompt.contains("oxygen"))
        assertTrue(prompt.contains("retro_ccd"))
        assertTrue(prompt.contains("cool_film"))

        val candidates = listOf(SubjectCandidate(1, "女孩", person = true, box = FaceBox(0.3, 0.2, 0.7, 0.8), seed = Uv(0.5, 0.5)))
        val result = Recommendation.evaluateCandidate(
            fields = mapOf(
                "candidate" to 1,
                "name" to "女孩",
                "choice" to "left_third",
                "reason" to "人物左三分留白",
                "filter" to mapOf(
                    "preset" to "oxygen",
                    "delta" to mapOf("highlights" to -0.10, "shadows" to 0.12),
                    "reason" to "晴天压高光更通透",
                ),
            ),
            candidates = candidates,
        )
        assertTrue(result is PlanResult.Accepted)
        val rec = (result as PlanResult.Accepted).value
        assertNotNull(rec.filterSuggestion)
        assertEquals(FilterPreset.OXYGEN, rec.filterSuggestion!!.preset)
        assertEquals("晴天压高光更通透", rec.filterSuggestion!!.reason)
    }
}
