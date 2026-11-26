package com.lumi.camera.shotplan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.StringReader
import kotlin.math.max
import kotlin.math.roundToInt

internal data class ReferenceRecipe(
    val isPortrait: Boolean,
    val shotType: String,
    val composition: String,
    val cameraAngle: String,
    val lightingMood: String,
    val poseSummary: String? = null,
    val targetChoice: CompositionChoice? = null,
    val targetExposure: AiExposureSuggestion? = null,
) {
    fun promptSummary(): String = buildString {
        append("【参考图处方】类型：")
        append(if (isPortrait) "人像打卡" else "非人像场景")
        append("·")
        append(shotType)
        append("；构图：")
        append(composition)
        append("；机位：")
        append(cameraAngle)
        append("；光影：")
        append(lightingMood)
        if (!poseSummary.isNullOrBlank()) {
            append("；动作要点：")
            append(poseSummary)
        }
        append("。")
    }
}

internal data class ReferenceGuidance(
    val poseCue: String? = null,
    val beyondTip: String? = null,
) {
    val isEmpty: Boolean get() = poseCue.isNullOrBlank() && beyondTip.isNullOrBlank()

    companion object {
        const val MAX_POSE_CHARS = 30
        const val MAX_BEYOND_CHARS = 30

        fun parse(poseRaw: Any?, beyondRaw: Any?): ReferenceGuidance? {
            val pose = sanitize(poseRaw, MAX_POSE_CHARS)
            val beyond = sanitize(beyondRaw, MAX_BEYOND_CHARS)
            if (pose == null && beyond == null) return null
            return ReferenceGuidance(poseCue = pose, beyondTip = beyond)
        }

        private fun sanitize(raw: Any?, maxChars: Int): String? {
            val text = (raw as? String)
                ?.trim()
                ?.trim('「', '」', '“', '”', '"')
                ?.trim()
                .orEmpty()
            if (text.isEmpty() || text.length > 100) return null
            if (text.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
            val cleaned = text.trimEnd('。', '.', '；', ';')
            if (cleaned.isEmpty()) return null
            return if (cleaned.codePointCount(0, cleaned.length) <= maxChars) {
                cleaned
            } else {
                val end = cleaned.offsetByCodePoints(0, maxChars - 1)
                cleaned.substring(0, end) + "…"
            }
        }
    }
}

internal object ReferenceRecipeProtocol {
    val prompt: String = """
        你是专业摄影教练。请分析用户提供的参考图，拆解出一份精炼的拍摄处方，仅输出紧凑 JSON：
        {"is_portrait":true,"shot_type":"半身人像","composition":"人物居左三分右侧留建筑","camera_angle":"微仰拍显身高","lighting":"侧逆光暖调柔光","pose":"侧身45度回头微扬下巴，右手轻搭栏杆","choice":"left_third","exposure":{"ev":0.3,"wb":5600,"reason":"提亮肤色保留暖调"}}
        字段要求：
        - is_portrait：布尔值，图中以真人打卡/人像为主则 true，否则 false。
        - shot_type：4至8字景别/题材（如“半身人像”“全身打卡”“静物特写”）。
        - composition：16字以内构图位置与留白特点。
        - camera_angle：12字以内机位与仰俯角度（如“平视近景”“低机位微仰拍”）。
        - lighting：14字以内光线方向与色调氛围。
        - pose：若是人像，24字以内总结人物站姿/头肩/手部/视线要点；非人像省略或 null。
        - choice：可选，取 keep/center/left_third/right_third/tight_top/more_headroom/pull_in 之一。
        - exposure：可选，参考该图光影基调给出建议 ev(-2.5~2.5)、wb(2800~7500) 与简短 reason。
    """.trimIndent()

    fun request(model: String, referenceJpeg: ByteArray): ByteArray {
        val url = "data:image/jpeg;base64," + Base64.encodeToString(referenceJpeg, Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        if (ChatProtocol.disablesReasoning(model)) {
            body.put("reasoning_effort", "none").put("max_tokens", 320)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    fun response(body: String): ReferenceRecipe? {
        val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
        val finish = choice.optString("finish_reason", "stop")
        if (finish !in setOf("stop", "length", "null")) return null
        val message = choice.getJSONObject("message")
        if (!message.isNull("refusal")) return null
        val raw = message.opt("content") as? String ?: return null
        val content = stripMarkdownFence(raw)
        if (content.length > 4096 || !Recommendation.hasJsonTokens(content)) return null
        val fields = runCatching {
            JsonReader(StringReader(content)).use { reader ->
                readObject(reader, 0).takeIf { reader.peek() == JsonToken.END_DOCUMENT }
            }
        }.getOrNull() ?: return null
        return parseFields(fields)
    }

    internal fun parseFields(fields: Map<String, Any?>): ReferenceRecipe? {
        val shotType = cleanField(fields["shot_type"], 16) ?: return null
        val composition = cleanField(fields["composition"], 28) ?: return null
        val cameraAngle = cleanField(fields["camera_angle"], 20) ?: "平视自然"
        val lighting = cleanField(fields["lighting"], 24) ?: "自然光"
        val isPortrait = (fields["is_portrait"] as? Boolean)
            ?: (listOf("人像", "人物", "合影", "打卡", "自拍", "肖像").any { it in shotType })
        val pose = cleanField(fields["pose"], 40)
        val targetChoice = CompositionChoice.parse(fields["choice"])
        val targetExposure = AiExposureSuggestion.parse(fields["exposure"])
        return ReferenceRecipe(
            isPortrait = isPortrait,
            shotType = shotType,
            composition = composition,
            cameraAngle = cameraAngle,
            lightingMood = lighting,
            poseSummary = if (isPortrait) pose else null,
            targetChoice = targetChoice,
            targetExposure = targetExposure,
        )
    }

    private fun cleanField(raw: Any?, maxChars: Int): String? {
        val s = (raw as? String)?.trim().orEmpty()
        if (s.isEmpty() || s.length > 120) return null
        if (s.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
        return if (s.codePointCount(0, s.length) <= maxChars) {
            s
        } else {
            val end = s.offsetByCodePoints(0, maxChars - 1)
            s.substring(0, end) + "…"
        }
    }

    private fun stripMarkdownFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return text
        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline < 0) return text
        val inner = trimmed.substring(firstNewline + 1)
        val endFence = inner.lastIndexOf("```")
        return if (endFence >= 0) inner.substring(0, endFence).trim() else text
    }

    private fun readObject(reader: JsonReader, depth: Int): Map<String, Any?> {
        require(depth <= 5)
        reader.beginObject()
        val map = mutableMapOf<String, Any?>()
        while (reader.hasNext()) {
            val k = reader.nextName()
            map[k] = readValue(reader, depth + 1)
        }
        reader.endObject()
        return map
    }

    private fun readValue(reader: JsonReader, depth: Int): Any? {
        require(depth <= 5)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readObject(reader, depth)
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val list = mutableListOf<Any?>()
                while (reader.hasNext()) list.add(readValue(reader, depth + 1))
                reader.endArray()
                list
            }
            JsonToken.NULL -> { reader.nextNull(); null }
            JsonToken.NUMBER -> reader.nextDouble()
            JsonToken.STRING -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            else -> throw IllegalArgumentException()
        }
    }
}

internal data class LoadedReferenceImage(
    val displayBitmap: Bitmap,
    val analysisJpeg: ByteArray,
    val compactCompareJpeg: ByteArray,
)

internal object ReferenceImageLoader {
    fun load(context: Context, uri: Uri): LoadedReferenceImage? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxRaw = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxRaw / (sample * 2) >= 960) sample *= 2

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val upright = applyExifOrientation(decoded, orientation)
        val display = scaleDown(upright, maxSide = 960)
        if (display !== upright) upright.recycle()

        val analysisBmp = scaleDown(display, maxSide = 512)
        val analysisJpeg = compressJpeg(analysisBmp, quality = 76)
        if (analysisBmp !== display) analysisBmp.recycle()

        val compareBmp = scaleDown(display, maxSide = 384)
        val compareJpeg = compressJpeg(compareBmp, quality = 70)
        if (compareBmp !== display) compareBmp.recycle()

        return LoadedReferenceImage(
            displayBitmap = display,
            analysisJpeg = analysisJpeg,
            compactCompareJpeg = compareJpeg,
        )
    }

    private fun applyExifOrientation(src: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return src
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        return rotated
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest.toFloat()
        val w = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (src.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun compressJpeg(bmp: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }
}
