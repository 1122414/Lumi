package com.lumi.camera.filter

import com.lumi.camera.camera.LiveTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2ColorLut3DTest {

    @Test
    fun `all 10 presets build valid 17x17x17 3D LUTs with expected strip layout and monotonic gray ramp`() {
        val out = FloatArray(3)
        FilterPreset.selectablePresets.forEach { preset ->
            val lut = ColorLut3D.forPreset(preset)
            assertEquals(17, lut.size)
            assertEquals(289, lut.stripWidth)
            assertEquals(17, lut.stripHeight)
            assertEquals(289 * 17 * 4, lut.toRgbaStripBytes().size)

            // Verify monotonic luminance along neutral gray diagonal
            var prevLuma = -1f
            for (step in 0..8) {
                val g = step / 8f
                lut.sample(g, g, g, out)
                val luma = 0.299f * out[0] + 0.587f * out[1] + 0.114f * out[2]
                assertTrue("${preset.id} gray ramp should be monotonic at $g", luma >= prevLuma - 0.01f)
                prevLuma = luma
            }
        }

        // ORIGINAL must remain exact identity
        val idLut = ColorLut3D.forPreset(FilterPreset.ORIGINAL)
        idLut.sample(0.25f, 0.55f, 0.80f, out)
        assertEquals(0.25f, out[0], 0.002f)
        assertEquals(0.55f, out[1], 0.002f)
        assertEquals(0.80f, out[2], 0.002f)

        // GOLDEN must shift neutral mid-gray warmer (R > B), COOL_FILM cooler (B > R)
        val golden = ColorLut3D.forPreset(FilterPreset.GOLDEN)
        golden.sample(0.5f, 0.5f, 0.5f, out)
        assertTrue("Expected GOLDEN midtone warmer (R > B)", out[0] > out[2] + 0.04f)

        val cool = ColorLut3D.forPreset(FilterPreset.COOL_FILM)
        cool.sample(0.5f, 0.5f, 0.5f, out)
        assertTrue("Expected COOL_FILM midtone cooler (B > R)", out[2] > out[0] + 0.01f)
    }

    @Test
    fun `cube parser loads valid 3D LUT text and rejects mismatched entries`() {
        val identity2x2x2 = """
            # Test 2x2x2 identity .cube LUT
            TITLE "Lumi Test"
            LUT_3D_SIZE 2
            0.0 0.0 0.0
            1.0 0.0 0.0
            0.0 1.0 0.0
            1.0 1.0 0.0
            0.0 0.0 1.0
            1.0 0.0 1.0
            0.0 1.0 1.0
            1.0 1.0 1.0
        """.trimIndent()
        val parsed = ColorLut3D.parseCube(identity2x2x2)
        assertNotNull(parsed)
        val out = FloatArray(3)
        parsed!!.sample(0.3f, 0.6f, 0.9f, out)
        assertEquals(0.3f, out[0], 0.001f)
        assertEquals(0.6f, out[1], 0.001f)
        assertEquals(0.9f, out[2], 0.001f)

        assertNull(ColorLut3D.parseCube("LUT_3D_SIZE 3\n0 0 0\n1 1 1"))
    }

    @Test
    fun `YCbCr chrominance ellipse isolates diverse human skin tones and rejects non-skin colors`() {
        // Fair, warm, and tan human skin RGB samples
        val fairSkin = ColorLut3D.skinWeightYCbCr(0.90f, 0.74f, 0.64f)
        val warmSkin = ColorLut3D.skinWeightYCbCr(0.78f, 0.56f, 0.44f)
        val tanSkin = ColorLut3D.skinWeightYCbCr(0.58f, 0.40f, 0.30f)
        assertTrue("Fair skin weight=$fairSkin", fairSkin > 0.45f)
        assertTrue("Warm skin weight=$warmSkin", warmSkin > 0.60f)
        assertTrue("Tan skin weight=$tanSkin", tanSkin > 0.50f)

        // Non-skin scene colors: blue sky, green foliage, neutral gray, saturated red sign
        val skyBlue = ColorLut3D.skinWeightYCbCr(0.32f, 0.60f, 0.92f)
        val foliageGreen = ColorLut3D.skinWeightYCbCr(0.24f, 0.64f, 0.28f)
        val neutralGray = ColorLut3D.skinWeightYCbCr(0.50f, 0.50f, 0.50f)
        val pureRed = ColorLut3D.skinWeightYCbCr(0.95f, 0.12f, 0.10f)
        assertTrue("Sky blue should not match skin ($skyBlue)", skyBlue < 0.05f)
        assertTrue("Foliage green should not match skin ($foliageGreen)", foliageGreen < 0.05f)
        assertTrue("Neutral gray should not match skin ($neutralGray)", neutralGray < 0.05f)
        assertTrue("Pure red should not match skin ($pureRed)", pureRed < 0.15f)
    }

    @Test
    fun `filter adjuster binds active 3D LUT preset and keeps original at zero strength`() {
        val film = FilterAdjuster.resolve(
            mode = FilterSelectionMode.MANUAL,
            selectedPreset = FilterPreset.CLASSIC_FILM,
            intensity = 0.85f,
            aiAdaptiveEnabled = true,
            aiSuggestion = null,
            telemetry = LiveTelemetry(ambientLux = 1200f),
        )
        assertEquals(FilterPreset.CLASSIC_FILM.id, film.resolvedGrade.lutPresetId)
        assertTrue(film.resolvedGrade.lutStrength >= 0.7f)

        val orig = FilterAdjuster.resolve(
            mode = FilterSelectionMode.MANUAL,
            selectedPreset = FilterPreset.ORIGINAL,
            intensity = 1.0f,
            aiAdaptiveEnabled = true,
            aiSuggestion = null,
            telemetry = LiveTelemetry(),
        )
        assertEquals(0f, orig.resolvedGrade.lutStrength, 0.001f)
    }
}
