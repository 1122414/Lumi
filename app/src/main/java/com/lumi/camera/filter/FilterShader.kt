package com.lumi.camera.filter

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object FilterShader {
    const val VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        uniform mat4 uTexMatrix;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = (uTexMatrix * aTexCoord).xy;
        }
    """

    fun fragmentShader(externalOes: Boolean): String {
        val header = if (externalOes) {
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            """.trimIndent()
        } else {
            """
            precision mediump float;
            uniform sampler2D uTexture;
            """.trimIndent()
        }
        return """
            $header
            varying vec2 vTexCoord;

            uniform float uIntensity;
            uniform float uExposure;
            uniform float uContrast;
            uniform float uHighlights;
            uniform float uShadows;
            uniform float uFade;
            uniform float uTemperature;
            uniform float uTint;
            uniform float uSaturation;
            uniform float uVibrance;
            uniform float uSkinProtect;
            uniform vec3 uShadowTint;
            uniform vec3 uHighlightTint;
            uniform float uGlow;
            uniform float uGrain;
            uniform float uVignette;
            uniform vec2 uTexelStep;
            uniform float uSeed;

            float lumaOf(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            float skinMask(vec3 c) {
                float mx = max(c.r, max(c.g, c.b));
                float mn = min(c.r, min(c.g, c.b));
                float sat = mx - mn;
                float warm = smoothstep(0.02, 0.16, c.r - c.g) * smoothstep(0.02, 0.18, c.g - c.b);
                float lum = lumaOf(c);
                float lumOk = smoothstep(0.14, 0.30, lum) * (1.0 - smoothstep(0.84, 0.96, lum));
                float satOk = smoothstep(0.04, 0.14, sat) * (1.0 - smoothstep(0.55, 0.78, sat));
                return clamp(warm * lumOk * satOk, 0.0, 1.0);
            }

            float hashNoise(vec2 p) {
                vec3 p3 = fract(vec3(p.xyx) * 0.1031);
                p3 += dot(p3, p3.yzx + 33.33);
                return fract((p3.x + p3.y) * p3.z) - 0.5;
            }

            void main() {
                vec4 src = texture2D(uTexture, vTexCoord);
                if (uIntensity <= 0.005) {
                    gl_FragColor = src;
                    return;
                }

                vec3 base = src.rgb;
                vec3 c = base;
                float skin = skinMask(base) * uSkinProtect;

                // 1. Exposure
                c *= pow(2.0, uExposure);

                // 2. White balance temperature & tint (softened on skin to prevent cool/green cast)
                float tempEff = uTemperature * (1.0 - 0.45 * skin);
                float tintEff = uTint * (1.0 - 0.55 * skin);
                c.r += tempEff * 0.18 + tintEff * 0.06;
                c.g -= tintEff * 0.10;
                c.b -= tempEff * 0.20 - tintEff * 0.05;
                c = clamp(c, 0.0, 1.0);

                // 3. Selective shadows & highlights recovery
                float l = lumaOf(c);
                float shadowW = pow(1.0 - l, 2.0);
                float highlightW = pow(l, 2.0);
                float toneDelta = uShadows * shadowW * 0.34 + uHighlights * highlightW * 0.34;
                c = clamp(c + vec3(toneDelta), 0.0, 1.0);

                // 4. Contrast & matte black-point fade
                c = clamp((c - 0.5) * (1.0 + uContrast) + 0.5, 0.0, 1.0);
                c = mix(vec3(uFade * 0.55), vec3(1.0), c);

                // 5. Split toning (protected on skin tones)
                float splitL = lumaOf(c);
                float splitScale = 1.0 - 0.50 * skin;
                c += uShadowTint * pow(1.0 - splitL, 1.7) * splitScale;
                c += uHighlightTint * pow(splitL, 1.7) * splitScale;
                c = clamp(c, 0.0, 1.0);

                // 6. Saturation & skin-protected vibrance (or film monochrome)
                float gray = dot(c, vec3(0.30, 0.59, 0.11));
                if (uSaturation <= -0.99) {
                    c = vec3(gray) + uShadowTint * (1.0 - gray) * 0.5 + uHighlightTint * gray * 0.5;
                } else {
                    float mx = max(c.r, max(c.g, c.b));
                    float mn = min(c.r, min(c.g, c.b));
                    float curSat = mx - mn;
                    float vibBoost = uVibrance * (1.0 - curSat) * (1.0 - 0.55 * skin);
                    float satFactor = max(0.0, 1.0 + uSaturation + vibBoost);
                    c = mix(vec3(gray), c, satFactor);
                }
                c = clamp(c, 0.0, 1.0);

                // 7. Airy highlight bloom / CCD glow
                if (uGlow > 0.005) {
                    vec2 stepUv = uTexelStep * 2.5;
                    vec3 n1 = texture2D(uTexture, clamp(vTexCoord + vec2(stepUv.x, 0.0), 0.0, 1.0)).rgb;
                    vec3 n2 = texture2D(uTexture, clamp(vTexCoord - vec2(stepUv.x, 0.0), 0.0, 1.0)).rgb;
                    vec3 n3 = texture2D(uTexture, clamp(vTexCoord + vec2(0.0, stepUv.y), 0.0, 1.0)).rgb;
                    vec3 n4 = texture2D(uTexture, clamp(vTexCoord - vec2(0.0, stepUv.y), 0.0, 1.0)).rgb;
                    vec3 blur = (base + n1 + n2 + n3 + n4) * 0.2;
                    float bloomMask = smoothstep(0.58, 0.96, lumaOf(blur));
                    c = clamp(c + blur * bloomMask * uGlow * 0.55, 0.0, 1.0);
                }

                // 8. Optical vignette
                if (uVignette > 0.005) {
                    vec2 centered = vTexCoord - vec2(0.5, 0.5);
                    float dist = dot(centered, centered) * 2.4;
                    float vig = clamp(1.0 - dist * uVignette, 0.62, 1.0);
                    c *= vig;
                }

                // 9. Midtone-weighted subtle film grain
                if (uGrain > 0.005) {
                    float gl = lumaOf(c);
                    float midW = 1.0 - abs(gl * 2.0 - 1.0);
                    float n = hashNoise(gl_FragCoord.xy + vec2(uSeed * 17.3, uSeed * 31.7));
                    c = clamp(c + vec3(n * uGrain * (0.35 + 0.65 * midW)), 0.0, 1.0);
                }

                gl_FragColor = vec4(mix(base, c, uIntensity), src.a);
            }
        """.trimIndent()
    }
}

internal class GlFilterProgram(private val externalOes: Boolean) {
    private val program: Int
    private val aPosition: Int
    private val aTexCoord: Int
    private val uTexMatrix: Int
    private val uTexture: Int
    private val uIntensity: Int
    private val uExposure: Int
    private val uContrast: Int
    private val uHighlights: Int
    private val uShadows: Int
    private val uFade: Int
    private val uTemperature: Int
    private val uTint: Int
    private val uSaturation: Int
    private val uVibrance: Int
    private val uSkinProtect: Int
    private val uShadowTint: Int
    private val uHighlightTint: Int
    private val uGlow: Int
    private val uGrain: Int
    private val uVignette: Int
    private val uTexelStep: Int
    private val uSeed: Int

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_VERTICES); position(0) }

    private val texCoordBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_TEX_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_TEX_COORDS); position(0) }

    init {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, FilterShader.VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FilterShader.fragmentShader(externalOes))
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("GL filter program link failed: $log")
        }

        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        uIntensity = GLES20.glGetUniformLocation(program, "uIntensity")
        uExposure = GLES20.glGetUniformLocation(program, "uExposure")
        uContrast = GLES20.glGetUniformLocation(program, "uContrast")
        uHighlights = GLES20.glGetUniformLocation(program, "uHighlights")
        uShadows = GLES20.glGetUniformLocation(program, "uShadows")
        uFade = GLES20.glGetUniformLocation(program, "uFade")
        uTemperature = GLES20.glGetUniformLocation(program, "uTemperature")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")
        uVibrance = GLES20.glGetUniformLocation(program, "uVibrance")
        uSkinProtect = GLES20.glGetUniformLocation(program, "uSkinProtect")
        uShadowTint = GLES20.glGetUniformLocation(program, "uShadowTint")
        uHighlightTint = GLES20.glGetUniformLocation(program, "uHighlightTint")
        uGlow = GLES20.glGetUniformLocation(program, "uGlow")
        uGrain = GLES20.glGetUniformLocation(program, "uGrain")
        uVignette = GLES20.glGetUniformLocation(program, "uVignette")
        uTexelStep = GLES20.glGetUniformLocation(program, "uTexelStep")
        uSeed = GLES20.glGetUniformLocation(program, "uSeed")
    }

    fun draw(
        textureId: Int,
        texMatrix: FloatArray,
        grade: FilterGrade,
        width: Int,
        height: Int,
        seed: Float,
    ) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val target = if (externalOes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        GLES20.glBindTexture(target, textureId)
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)

        GLES20.glUniform1f(uIntensity, grade.intensity)
        GLES20.glUniform1f(uExposure, grade.exposure)
        GLES20.glUniform1f(uContrast, grade.contrast)
        GLES20.glUniform1f(uHighlights, grade.highlights)
        GLES20.glUniform1f(uShadows, grade.shadows)
        GLES20.glUniform1f(uFade, grade.fade)
        GLES20.glUniform1f(uTemperature, grade.temperature)
        GLES20.glUniform1f(uTint, grade.tint)
        GLES20.glUniform1f(uSaturation, grade.saturation)
        GLES20.glUniform1f(uVibrance, grade.vibrance)
        GLES20.glUniform1f(uSkinProtect, grade.skinProtect)
        GLES20.glUniform3f(uShadowTint, grade.shadowTint.r, grade.shadowTint.g, grade.shadowTint.b)
        GLES20.glUniform3f(uHighlightTint, grade.highlightTint.r, grade.highlightTint.g, grade.highlightTint.b)
        GLES20.glUniform1f(uGlow, grade.glow)
        GLES20.glUniform1f(uGrain, grade.grain)
        GLES20.glUniform1f(uVignette, grade.vignette)
        GLES20.glUniform2f(uTexelStep, 1f / width.coerceAtLeast(1), 1f / height.coerceAtLeast(1))
        GLES20.glUniform1f(uSeed, seed)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glBindTexture(target, 0)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    companion object {
        private val QUAD_VERTICES = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f,
        )
        private val QUAD_TEX_COORDS = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f,
        )

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] != GLES20.GL_TRUE) {
                val log = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("GL shader compile failed ($type): $log")
            }
            return shader
        }
    }
}
