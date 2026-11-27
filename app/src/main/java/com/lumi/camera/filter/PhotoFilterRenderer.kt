package com.lumi.camera.filter

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

internal object PhotoFilterRenderer {
    private val IDENTITY_MATRIX = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    private val PRESERVED_EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER,
        ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_FLASH,
    )

    fun saveFilteredJpeg(
        context: Context,
        jpegBytes: ByteArray,
        grade: FilterGrade,
        mirrorHorizontal: Boolean,
        fallbackRotationDegrees: Int = 0,
        filePrefix: String = "Lumi",
    ): Uri? {
        val exifMap = readExifTags(jpegBytes)
        val rawOrientation = exifMap[ExifInterface.TAG_ORIENTATION]?.toIntOrNull()
            ?: ExifInterface.ORIENTATION_UNDEFINED
        val orientation = if (rawOrientation == ExifInterface.ORIENTATION_UNDEFINED ||
            (rawOrientation == ExifInterface.ORIENTATION_NORMAL && fallbackRotationDegrees != 0)) {
            when ((fallbackRotationDegrees % 360 + 360) % 360) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
        } else {
            rawOrientation
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_RENDER_EDGE) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            jpegBytes,
            0,
            jpegBytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null

        val upright = applyOrientationAndMirror(decoded, orientation, mirrorHorizontal)
        if (upright !== decoded) decoded.recycle()

        val outputBitmap = try {
            if (grade.isIdentity) upright else renderWithOffscreenGl(upright, grade) ?: upright
        } catch (e: RuntimeException) {
            Log.w("LumiFilter", "Offscreen GL photo filter failed, saving ungraded photo", e)
            upright
        }

        return try {
            writeToGallery(context, outputBitmap, exifMap, filePrefix)
        } finally {
            if (outputBitmap !== upright) outputBitmap.recycle()
            upright.recycle()
        }
    }

    private fun renderWithOffscreenGl(source: Bitmap, grade: FilterGrade): Bitmap? {
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null

        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        var program: GlFilterProgram? = null
        var texId = 0

        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val versions = IntArray(2)
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) return null

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
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
                return null
            }
            val chosen = configs[0]!!
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, chosen, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == null || context == EGL14.EGL_NO_CONTEXT) return null

            val pbufAttribs = intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE,
            )
            surface = EGL14.eglCreatePbufferSurface(display, chosen, pbufAttribs, 0)
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val texIds = IntArray(1)
            GLES20.glGenTextures(1, texIds, 0)
            texId = texIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, source, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

            program = GlFilterProgram(externalOes = false)
            GLES20.glViewport(0, 0, width, height)
            program.draw(
                textureId = texId,
                texMatrix = IDENTITY_MATRIX,
                grade = grade,
                width = width,
                height = height,
                seed = 7f,
            )
            GLES20.glFinish()

            val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)
            pixelBuffer.rewind()
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                copyPixelsFromBuffer(pixelBuffer)
            }
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                program?.release()
                if (texId != 0) GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    private fun readExifTags(jpegBytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        runCatching {
            ExifInterface(ByteArrayInputStream(jpegBytes)).let { exif ->
                result[ExifInterface.TAG_ORIENTATION] = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                ).toString()
                for (tag in PRESERVED_EXIF_TAGS) {
                    exif.getAttribute(tag)?.let { value -> result[tag] = value }
                }
            }
        }
        return result
    }

    private fun applyOrientationAndMirror(source: Bitmap, orientation: Int, mirrorHorizontal: Boolean): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        }
        if (mirrorHorizontal) {
            matrix.postScale(-1f, 1f)
        }
        if (matrix.isIdentity) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun writeToGallery(
        context: Context,
        bitmap: Bitmap,
        exifMap: Map<String, String>,
        filePrefix: String,
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${filePrefix}_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lumi")
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val ok = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
        } == true
        if (!ok) {
            resolver.delete(uri, null, null)
            return null
        }
        runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                for (tag in PRESERVED_EXIF_TAGS) {
                    exifMap[tag]?.let { value -> exif.setAttribute(tag, value) }
                }
                exif.saveAttributes()
            }
        }
        return uri
    }

    private const val MAX_RENDER_EDGE = 4096
}
