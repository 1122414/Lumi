package com.lumi.camera.filter

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

internal class PreviewFilterEffect : SurfaceProcessor {
    private val glThread = HandlerThread("LumiFilterGL").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { command ->
        if (!released.get()) {
            glHandler.post(command)
        }
    }
    private val released = AtomicBoolean(false)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program: GlFilterProgram? = null
    private var externalTextureId: Int = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val outputSurfaces = LinkedHashMap<SurfaceOutput, EGLSurface>()
    private val rawMatrix = FloatArray(16)
    private val outputMatrix = FloatArray(16)

    @Volatile private var targetGrade: FilterGrade = FilterGrade.IDENTITY
    private var startGrade: FilterGrade = FilterGrade.IDENTITY
    private var currentGrade: FilterGrade = FilterGrade.IDENTITY
    private var lastTarget: FilterGrade = FilterGrade.IDENTITY
    private var transitionStartMs: Long = 0L
    private var frameCounter: Int = 0

    val cameraEffect: CameraEffect = object : CameraEffect(
        PREVIEW,
        glExecutor,
        this,
        { err -> Log.w("LumiFilter", "Preview filter effect error", err) },
    ) {}

    fun updateGrade(next: FilterGrade) {
        targetGrade = next.clamped()
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (released.get()) {
            request.willNotProvideSurface()
            return
        }
        try {
            ensureGlReady()
            releaseInputSurface()
            val res = request.resolution
            val tex = SurfaceTexture(externalTextureId).apply {
                setDefaultBufferSize(res.width, res.height)
                setOnFrameAvailableListener({ renderAvailableFrame() }, glHandler)
            }
            val surface = Surface(tex)
            inputSurfaceTexture = tex
            inputSurface = surface
            request.provideSurface(surface, glExecutor) {
                if (inputSurfaceTexture === tex) {
                    releaseInputSurface()
                } else {
                    runCatching { surface.release() }
                    runCatching { tex.release() }
                }
            }
        } catch (e: RuntimeException) {
            Log.e("LumiFilter", "Failed to prepare GL input surface", e)
            request.willNotProvideSurface()
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released.get()) {
            surfaceOutput.close()
            return
        }
        try {
            ensureGlReady()
            val surface = surfaceOutput.getSurface(glExecutor) {
                removeOutputSurface(surfaceOutput)
            }
            val config = eglConfig ?: throw IllegalStateException("Missing EGLConfig")
            val attrs = intArrayOf(EGL14.EGL_NONE)
            val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, attrs, 0)
            if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreateWindowSurface failed")
            }
            outputSurfaces[surfaceOutput] = eglSurface
        } catch (e: RuntimeException) {
            Log.e("LumiFilter", "Failed to bind GL output surface", e)
            surfaceOutput.close()
        }
    }

    private fun renderAvailableFrame() {
        if (released.get()) return
        val tex = inputSurfaceTexture ?: return
        val prog = program ?: return
        try {
            makeCurrent(pbufferSurface)
            tex.updateTexImage()
            tex.getTransformMatrix(rawMatrix)
            val timestampNs = tex.timestamp
            if (outputSurfaces.isEmpty()) return

            val grade = stepGrade()
            frameCounter = (frameCounter + 1) and 0x3FFF
            val seed = (frameCounter % 60).toFloat()

            for ((output, eglSurface) in outputSurfaces.entries.toList()) {
                output.updateTransformMatrix(outputMatrix, rawMatrix)
                if (!makeCurrent(eglSurface)) continue
                val size = output.size
                GLES20.glViewport(0, 0, size.width, size.height)
                prog.draw(
                    textureId = externalTextureId,
                    texMatrix = outputMatrix,
                    grade = grade,
                    width = size.width,
                    height = size.height,
                    seed = seed,
                )
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs)
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
        } catch (e: RuntimeException) {
            Log.w("LumiFilter", "GL preview draw error", e)
        }
    }

    private fun stepGrade(): FilterGrade {
        val next = targetGrade
        val nowMs = SystemClock.elapsedRealtime()
        if (next != lastTarget) {
            startGrade = currentGrade
            lastTarget = next
            transitionStartMs = nowMs
        }
        val t = ((nowMs - transitionStartMs).toFloat() / TRANSITION_MS).coerceIn(0f, 1f)
        currentGrade = if (t >= 1f) next else startGrade.lerp(next, t)
        return currentGrade
    }

    private fun ensureGlReady() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) return
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("No EGL display")
        val versions = IntArray(2)
        if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            throw IllegalStateException("eglInitialize failed")
        }
        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] <= 0 || configs[0] == null) {
            throw IllegalStateException("eglChooseConfig failed")
        }
        val chosen = configs[0]!!
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, chosen, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("eglCreateContext failed")
        }
        val pbufAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val pbuf = EGL14.eglCreatePbufferSurface(display, chosen, pbufAttribs, 0)
        if (pbuf == null || pbuf == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreatePbufferSurface failed")
        }
        eglDisplay = display
        eglConfig = chosen
        eglContext = context
        pbufferSurface = pbuf
        makeCurrent(pbuf)

        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        externalTextureId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        program = GlFilterProgram(externalOes = true)
    }

    private fun makeCurrent(surface: EGLSurface): Boolean {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) {
            return false
        }
        return EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
    }

    private fun removeOutputSurface(output: SurfaceOutput) {
        val eglSurface = outputSurfaces.remove(output)
        if (eglSurface != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (pbufferSurface != EGL14.EGL_NO_SURFACE) makeCurrent(pbufferSurface)
            EGL14.eglDestroySurface(eglDisplay, eglSurface)
        }
        runCatching { output.close() }
    }

    private fun releaseInputSurface() {
        inputSurfaceTexture?.setOnFrameAvailableListener(null)
        runCatching { inputSurface?.release() }
        runCatching { inputSurfaceTexture?.release() }
        inputSurface = null
        inputSurfaceTexture = null
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        glHandler.post {
            releaseInputSurface()
            for (output in outputSurfaces.keys.toList()) {
                removeOutputSurface(output)
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                makeCurrent(pbufferSurface)
                program?.release()
                program = null
                if (externalTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(externalTextureId), 0)
                    externalTextureId = 0
                }
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, pbufferSurface)
                    pbufferSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
            glThread.quitSafely()
        }
    }

    companion object {
        private const val TRANSITION_MS = 220f
    }
}
