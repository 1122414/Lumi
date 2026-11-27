package com.lumi.camera.filter

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

// 17x17x17 3D Color Lookup Table packed into a 289x17 RGBA 2D strip texture for universal GLES2.0 support.
// Encodes filmic S-curve + soft highlight shoulder rolloff + per-hue cinematic color remapping
// + optional .cube LUT file parsing for artist packs.
internal class ColorLut3D(
    val size: Int,
    // Packed RGB floats in [0, 1], indexed as ((b * size + g) * size + r) * 3
    val rgb: FloatArray,
) {
    init {
        require(size in 2..65) { "Unsupported 3D LUT size: $size" }
        require(rgb.size == size * size * size * 3) { "Invalid LUT data size" }
    }

    val stripWidth: Int get() = size * size
    val stripHeight: Int get() = size

    // Packs into a 2D RGBA strip of width=(size*size), height=size where x = b * size + r, y = g.
    fun toRgbaStripBytes(): ByteArray {
        val out = ByteArray(stripWidth * stripHeight * 4)
        for (g in 0 until size) {
            for (b in 0 until size) {
                for (r in 0 until size) {
                    val srcIdx = ((b * size + g) * size + r) * 3
                    val x = b * size + r
                    val dstIdx = (g * stripWidth + x) * 4
                    out[dstIdx] = (rgb[srcIdx].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                    out[dstIdx + 1] = (rgb[srcIdx + 1].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                    out[dstIdx + 2] = (rgb[srcIdx + 2].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
                    out[dstIdx + 3] = 0xFF.toByte()
                }
            }
        }
        return out
    }

    fun toRgbaStripBuffer(): ByteBuffer = ByteBuffer.wrap(toRgbaStripBytes())

    // True trilinear interpolation in 3D RGB cube (identical math to GLSL sampleLut3D).
    fun sample(rIn: Float, gIn: Float, bIn: Float, outRgb: FloatArray) {
        val maxIdx = (size - 1).toFloat()
        val rf = rIn.coerceIn(0f, 1f) * maxIdx
        val gf = gIn.coerceIn(0f, 1f) * maxIdx
        val bf = bIn.coerceIn(0f, 1f) * maxIdx

        val r0 = floor(rf).toInt().coerceIn(0, size - 1)
        val g0 = floor(gf).toInt().coerceIn(0, size - 1)
        val b0 = floor(bf).toInt().coerceIn(0, size - 1)
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)

        val dr = rf - r0
        val dg = gf - g0
        val db = bf - b0

        fun fetch(r: Int, g: Int, b: Int, c: Int): Float = rgb[((b * size + g) * size + r) * 3 + c]

        for (c in 0..2) {
            val c000 = fetch(r0, g0, b0, c)
            val c100 = fetch(r1, g0, b0, c)
            val c010 = fetch(r0, g1, b0, c)
            val c110 = fetch(r1, g1, b0, c)
            val c001 = fetch(r0, g0, b1, c)
            val c101 = fetch(r1, g0, b1, c)
            val c011 = fetch(r0, g1, b1, c)
            val c111 = fetch(r1, g1, b1, c)

            val c00 = c000 + (c100 - c000) * dr
            val c10 = c010 + (c110 - c010) * dr
            val c01 = c001 + (c101 - c001) * dr
            val c11 = c011 + (c111 - c011) * dr

            val c0 = c00 + (c10 - c00) * dg
            val c1 = c01 + (c11 - c01) * dg
            outRgb[c] = (c0 + (c1 - c0) * db).coerceIn(0f, 1f)
        }
    }

    companion object {
        const val DEFAULT_SIZE = 17

        private val cache = HashMap<FilterPreset, ColorLut3D>()

        @Synchronized
        fun forPreset(preset: FilterPreset): ColorLut3D =
            cache.getOrPut(preset) { buildPresetLut(preset, DEFAULT_SIZE) }

        // Parses standard Adobe .cube 3D LUT text format (supports LUT_3D_SIZE and RGB triplets).
        fun parseCube(text: String): ColorLut3D? {
            var lutSize = -1
            val values = ArrayList<Float>()
            text.lineSequence().forEach { rawLine ->
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEach
                if (line.startsWith("LUT_3D_SIZE", ignoreCase = true)) {
                    lutSize = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: -1
                    return@forEach
                }
                if (line.first().isLetter()) return@forEach
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val r = parts[0].toFloatOrNull() ?: return@forEach
                    val g = parts[1].toFloatOrNull() ?: return@forEach
                    val b = parts[2].toFloatOrNull() ?: return@forEach
                    values.add(r.coerceIn(0f, 1f))
                    values.add(g.coerceIn(0f, 1f))
                    values.add(b.coerceIn(0f, 1f))
                }
            }
            if (lutSize !in 2..65 || values.size != lutSize * lutSize * lutSize * 3) return null
            return ColorLut3D(lutSize, values.toFloatArray())
        }

        // True YCbCr Chrominance Ellipse skin mask (BT.601 normalized).
        // Accurately isolates diverse real human skin tones while rejecting yellow walls / red signs.
        fun skinWeightYCbCr(r: Float, g: Float, b: Float): Float {
            val y = 0.299f * r + 0.587f * g + 0.114f * b
            val cb = 0.5f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 0.5f + 0.5f * r - 0.418688f * g - 0.081312f * b
            // Canonical human skin ellipse center in normalized [0..1] YCbCr: Cb≈0.415 (106/255), Cr≈0.600 (153/255)
            val dCb = (cb - 0.415f) / 0.085f
            val dCr = (cr - 0.600f) / 0.075f
            val ellipse = exp(-0.5f * (dCb * dCb + dCr * dCr))
            val lumaGuard = smoothstep(0.12f, 0.26f, y) * (1f - smoothstep(0.86f, 0.97f, y))
            val warmthGuard = smoothstep(0.015f, 0.08f, r - b)
            return (ellipse * lumaGuard * warmthGuard).coerceIn(0f, 1f)
        }

        private fun buildPresetLut(preset: FilterPreset, size: Int): ColorLut3D {
            val data = FloatArray(size * size * size * 3)
            val maxIdx = (size - 1).toFloat()
            val tmp = FloatArray(3)
            for (b in 0 until size) {
                val bf = b / maxIdx
                for (g in 0 until size) {
                    val gf = g / maxIdx
                    for (r in 0 until size) {
                        val rf = r / maxIdx
                        evaluatePresetColor(preset, rf, gf, bf, tmp)
                        val idx = ((b * size + g) * size + r) * 3
                        data[idx] = tmp[0].coerceIn(0f, 1f)
                        data[idx + 1] = tmp[1].coerceIn(0f, 1f)
                        data[idx + 2] = tmp[2].coerceIn(0f, 1f)
                    }
                }
            }
            return ColorLut3D(size, data)
        }

        private fun evaluatePresetColor(
            preset: FilterPreset,
            rIn: Float,
            gIn: Float,
            bIn: Float,
            out: FloatArray,
        ) {
            if (preset == FilterPreset.ORIGINAL) {
                out[0] = rIn; out[1] = gIn; out[2] = bIn
                return
            }

            // Convert RGB -> HSL for selective per-hue film color science
            var r = rIn
            var g = gIn
            var b = bIn

            when (preset) {
                FilterPreset.OXYGEN -> {
                    // Fuji Astia / sunlit airy look: soft highlight rolloff, luminous emerald greens, clean sky cyan
                    r = filmicCurve(r, contrast = 0.96f, toeLift = 0.025f, shoulder = 0.18f)
                    g = filmicCurve(g, contrast = 0.97f, toeLift = 0.028f, shoulder = 0.16f)
                    b = filmicCurve(b, contrast = 0.98f, toeLift = 0.036f, shoulder = 0.15f)
                    val hsl = rgbToHsl(r, g, b)
                    // Shift blues slightly toward bright azure-cyan; brighten lush greens
                    val skyW = hueBell(hsl[0], 215f, 38f)
                    val greenW = hueBell(hsl[0], 115f, 40f)
                    hsl[0] = wrapHue(hsl[0] - skyW * 8f + greenW * 6f)
                    hsl[1] = (hsl[1] * (1f + skyW * 0.16f + greenW * 0.12f)).coerceIn(0f, 1f)
                    hsl[2] = (hsl[2] + 0.035f + greenW * 0.025f).coerceIn(0f, 1f)
                    hslToRgb(hsl[0], hsl[1], hsl[2], out)
                }

                FilterPreset.MIST -> {
                    // Japanese airy overcast editorial: soft contrast, cool slate shadows, restrained greens
                    r = filmicCurve(r, contrast = 0.90f, toeLift = 0.042f, shoulder = 0.22f)
                    g = filmicCurve(g, contrast = 0.91f, toeLift = 0.048f, shoulder = 0.20f)
                    b = filmicCurve(b, contrast = 0.93f, toeLift = 0.064f, shoulder = 0.18f)
                    val hsl = rgbToHsl(r, g, b)
                    val greenW = hueBell(hsl[0], 110f, 42f)
                    val blueW = hueBell(hsl[0], 215f, 45f)
                    hsl[0] = wrapHue(hsl[0] + greenW * 12f - blueW * 6f)
                    hsl[1] = (hsl[1] * (0.86f - greenW * 0.16f + blueW * 0.08f)).coerceIn(0f, 1f)
                    hsl[2] = (hsl[2] + 0.022f).coerceIn(0f, 1f)
                    hslToRgb(hsl[0], hsl[1], hsl[2], out)
                }

                FilterPreset.GOLDEN -> {
                    // Sunset golden-hour emulsion: creamy amber highlights, warm sepia toe, controlled yellow
                    r = filmicCurve(r, contrast = 1.08f, toeLift = 0.035f, shoulder = 0.24f)
                    g = filmicCurve(g, contrast = 1.05f, toeLift = 0.024f, shoulder = 0.24f)
                    b = filmicCurve(b, contrast = 1.00f, toeLift = 0.012f, shoulder = 0.26f)
                    val l = luma(r, g, b)
                    r = (r + 0.065f * l + 0.020f * (1f - l)).coerceIn(0f, 1f)
                    g = (g + 0.028f * l + 0.008f * (1f - l)).coerceIn(0f, 1f)
                    b = (b - 0.055f * (0.35f + 0.65f * l)).coerceIn(0f, 1f)
                    val hsl = rgbToHsl(r, g, b)
                    val orangeW = hueBell(hsl[0], 34f, 34f)
                    hsl[1] = (hsl[1] * (1.06f + orangeW * 0.14f)).coerceIn(0f, 1f)
                    hslToRgb(hsl[0], hsl[1], hsl[2], out)
                }

                FilterPreset.CLASSIC_FILM -> {
                    // Fuji Classic Chrome inspired: teal skies, earthy muted greens, punchy crimson, lifted film toe
                    r = filmicCurve(r, contrast = 1.12f, toeLift = 0.048f, shoulder = 0.22f)
                    g = filmicCurve(g, contrast = 1.11f, toeLift = 0.052f, shoulder = 0.20f)
                    b = filmicCurve(b, contrast = 1.08f, toeLift = 0.058f, shoulder = 0.24f)
                    val hsl = rgbToHsl(r, g, b)
                    val blueW = hueBell(hsl[0], 215f, 40f)
                    val greenW = hueBell(hsl[0], 115f, 42f)
                    val redW = hueBell(hsl[0], 5f, 28f)
                    hsl[0] = wrapHue(hsl[0] - blueW * 16f - greenW * 10f)
                    hsl[1] = (hsl[1] * (0.94f - greenW * 0.24f - blueW * 0.08f + redW * 0.18f)).coerceIn(0f, 1f)
                    hslToRgb(hsl[0], hsl[1], hsl[2], out)
                    val l = luma(out[0], out[1], out[2])
                    // Subtle warm creamy highlight paper tone
                    out[0] = (out[0] + 0.028f * l * l).coerceIn(0f, 1f)
                    out[1] = (out[1] + 0.018f * l * l).coerceIn(0f, 1f)
                    out[2] = (out[2] - 0.014f * l * l + 0.024f * (1f - l) * (1f - l)).coerceIn(0f, 1f)
                }

                FilterPreset.NIGHT_MOOD -> {
                    // CineStill 800T inspired: deep cyan-teal shadows, tungsten amber highlights, vibrant neon separation
                    r = filmicCurve(r, contrast = 1.16f, toeLift = 0.018f, shoulder = 0.20f)
                    g = filmicCurve(g, contrast = 1.15f, toeLift = 0.026f, shoulder = 0.20f)
                    b = filmicCurve(b, contrast = 1.12f, toeLift = 0.048f, shoulder = 0.18f)
                    val l = luma(r, g, b)
                    val hi = l * l
                    val lo = (1f - l) * (1f - l)
                    r = (r - 0.042f * lo + 0.055f * hi).coerceIn(0f, 1f)
                    g = (g + 0.012f * lo + 0.016f * hi).coerceIn(0f, 1f)
                    b = (b + 0.072f * lo - 0.036f * hi).coerceIn(0f, 1f)
                    val hsl = rgbToHsl(r, g, b)
                    val neonW = hueBell(hsl[0], 325f, 35f) + hueBell(hsl[0], 190f, 35f)
                    hsl[1] = (hsl[1] * (1.06f + neonW * 0.18f)).coerceIn(0f, 1f)
                    hslToRgb(hsl[0], hsl[1], hsl[2], out)
                }

                FilterPreset.SOFT_PORTRAIT -> {
                    // Kodak Portra 400 inspired: velvety low-contrast midtones, peachy-ivory highlights, soft pastel background
                    r = filmicCurve(r, contrast = 0.93f, toeLift = 0.040f, shoulder = 0.22f)
                    g = filmicCurve(g, contrast = 0.92f, toeLift = 0.036f, shoulder = 0.22f)
                    b = filmicCurve(b, contrast = 0.92f, toeLift = 0.034f, shoulder = 0.23f)
                    val hsl = rgbToHsl(r, g, b)
                    val skinHue = hueBell(hsl[0], 24f, 26f)
                    hsl[1] = (hsl[1] * (0.94f + skinHue * 0.08f)).coerceIn(0f, 1f)
                    hsl[2] = (hsl[2] + 0.028f + skinHue * 0.020f).coerceIn(0f, 1f)
                    hslToRgb(hsl[0], hsl[1], hsl[2], out)
                    val l = luma(out[0], out[1], out[2])
                    out[0] = (out[0] + 0.024f * l).coerceIn(0f, 1f)
                    out[1] = (out[1] + 0.012f * l).coerceIn(0f, 1f)
                    out[2] = (out[2] + 0.004f * l).coerceIn(0f, 1f)
                }

                FilterPreset.MONOCHROME -> {
                    // Kodak Tri-X 400 silver-halide B&W: red-weighted panchromatic response (luminous skin, dramatic skies)
                    val panchro = (0.38f * r + 0.52f * g + 0.10f * b).coerceIn(0f, 1f)
                    val bw = filmicCurve(panchro, contrast = 1.24f, toeLift = 0.022f, shoulder = 0.18f)
                    // Subtle selenium silver split-tone (cool shadow, clean warm-neutral paper highlight)
                    out[0] = (bw + 0.010f * bw * bw - 0.006f * (1f - bw)).coerceIn(0f, 1f)
                    out[1] = (bw + 0.006f * bw * bw).coerceIn(0f, 1f)
                    out[2] = (bw + 0.014f * (1f - bw) * (1f - bw)).coerceIn(0f, 1f)
                }

                FilterPreset.RETRO_CCD -> {
                    // Early-2000s Sony/Canon CCD compact: crisp digital highlight bite, cool-cyan highlight cast, punchy primaries
                    r = filmicCurve(r, contrast = 1.14f, toeLift = 0.018f, shoulder = 0.10f)
                    g = filmicCurve(g, contrast = 1.15f, toeLift = 0.026f, shoulder = 0.10f)
                    b = filmicCurve(b, contrast = 1.16f, toeLift = 0.032f, shoulder = 0.08f)
                    val l = luma(r, g, b)
                    val lo = (1f - l) * (1f - l)
                    val hi = l * l
                    r = (r - 0.016f * lo - 0.012f * hi).coerceIn(0f, 1f)
                    g = (g + 0.024f * lo + 0.018f * hi).coerceIn(0f, 1f)
                    b = (b + 0.036f * lo + 0.044f * hi).coerceIn(0f, 1f)
                    val hsl = rgbToHsl(r, g, b)
                    hsl[1] = (hsl[1] * 1.12f).coerceIn(0f, 1f)
                    hslToRgb(hsl[0], hsl[1], hsl[2], out)
                }

                FilterPreset.COOL_FILM -> {
                    // Glacial blue-slate cinema stock: crisp whites, clean deep slate shadows, muted warm tones
                    r = filmicCurve(r, contrast = 1.07f, toeLift = 0.034f, shoulder = 0.20f)
                    g = filmicCurve(g, contrast = 1.07f, toeLift = 0.042f, shoulder = 0.18f)
                    b = filmicCurve(b, contrast = 1.08f, toeLift = 0.062f, shoulder = 0.16f)
                    val hsl = rgbToHsl(r, g, b)
                    val blueW = hueBell(hsl[0], 210f, 45f)
                    val yellowW = hueBell(hsl[0], 55f, 35f)
                    hsl[0] = wrapHue(hsl[0] - blueW * 6f)
                    hsl[1] = (hsl[1] * (0.88f - yellowW * 0.20f + blueW * 0.12f)).coerceIn(0f, 1f)
                    hslToRgb(hsl[0], hsl[1], hsl[2], out)
                }

                FilterPreset.ORIGINAL -> {
                    out[0] = rIn; out[1] = gIn; out[2] = bIn
                }
            }
        }

        // Smooth filmic S-curve with matte toe lift and soft highlight shoulder rolloff.
        private fun filmicCurve(xIn: Float, contrast: Float, toeLift: Float, shoulder: Float): Float {
            val x = xIn.coerceIn(0f, 1f)
            // Centered power S-curve
            val s = if (x < 0.5f) {
                0.5f * (2f * x).pow(contrast)
            } else {
                1f - 0.5f * (2f * (1f - x)).pow(contrast)
            }
            // Soft highlight shoulder compression above 0.65
            val rolled = if (s > 0.65f && shoulder > 0f) {
                val excess = (s - 0.65f) / 0.35f
                0.65f + 0.35f * (excess / (1f + shoulder * excess)) * (1f + shoulder * 0.55f)
            } else {
                s
            }
            // Matte black toe lift
            return (toeLift + rolled * (1f - toeLift)).coerceIn(0f, 1f)
        }

        private fun luma(r: Float, g: Float, b: Float): Float =
            0.299f * r + 0.587f * g + 0.114f * b

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun wrapHue(h: Float): Float {
            var v = h % 360f
            if (v < 0f) v += 360f
            return v
        }

        private fun hueBell(hue: Float, center: Float, width: Float): Float {
            val raw = abs(hue - center)
            val dist = min(raw, 360f - raw)
            val norm = dist / width
            return exp(-0.5f * norm * norm)
        }

        private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val d = maxC - minC
            val l = (maxC + minC) * 0.5f
            if (d <= 1e-5f) return floatArrayOf(0f, 0f, l)
            val s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
            val h = when (maxC) {
                r -> ((g - b) / d + (if (g < b) 6f else 0f)) * 60f
                g -> ((b - r) / d + 2f) * 60f
                else -> ((r - g) / d + 4f) * 60f
            }
            return floatArrayOf(wrapHue(h), s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        }

        private fun hslToRgb(h: Float, s: Float, l: Float, out: FloatArray) {
            if (s <= 1e-5f) {
                out[0] = l; out[1] = l; out[2] = l
                return
            }
            val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
            val p = 2f * l - q
            val hk = h / 360f
            fun hue2rgb(tIn: Float): Float {
                var t = tIn
                if (t < 0f) t += 1f
                if (t > 1f) t -= 1f
                return when {
                    t < 1f / 6f -> p + (q - p) * 6f * t
                    t < 0.5f -> q
                    t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                    else -> p
                }
            }
            out[0] = hue2rgb(hk + 1f / 3f)
            out[1] = hue2rgb(hk)
            out[2] = hue2rgb(hk - 1f / 3f)
        }
    }
}
