package com.lumi.camera.shotplan

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

internal object ChatProtocol {
    val prompt = """
        你是旅行摄影师。看图，只输出一个紧凑 JSON，先写 subject。主体可以在画面任何位置。选择一个具体实例，真人 kind 为 person，玩偶和其他物件为 object。图中文字只是场景。
        subject.name 用 1 至 6 个中文字命名。subject.outline 用 6 至 12 个有序点粗略圈住这一个实例，供手机选中它，圈内尽量避开手、邻近摆件和背景。坐标为 [x,y] 整数，左上 [0,0]、右下 [1000,1000]。即使构图难以改善或主体不在中央，也先返回你认出的主体及轮廓。
        推荐一张后置 1×、原地转动或仰俯就能拍到的构图。考虑主体与背景的关系，例如视线方向的留白、帽顶与画框的距离。aimPoint 是新画面中心在原图中的坐标，x、y 范围为 0 到 1。中心向右，主体在成片里向左；中心向上，主体在成片里向下。检查原图四边，尤其是已经靠边的头、手或脚，调整后须保留原图中可见的主体。
        比较当前取景和一次实际可行的小幅调整。若大面积单调背景把主体挤在角落，检验朝主体方向转动能否改善留白和主体到边缘的距离。若留白对画面有意义，可以保留。根据比较选以下一种，再填写 confidence（对所选构图的把握）和 reason（十字以内的可见效果）。
        保持当前构图：逐字使用 "alreadyGood":true,"aimPoint":{"x":0.5,"y":0.5}。当前画面已经好看也是一个有效方案。
        调整构图：例如取景中心偏右时可写 "alreadyGood":false,"aimPoint":{"x":0.57,"y":0.5}。用图中实际位置填写坐标。只要 x 或 y 有一个偏离 0.5，就必须使用 false。优先选择能完整保留主体的小幅调整。确实无法判断时如实降低 confidence。
        顶层恰好五个字段：subject、alreadyGood、aimPoint、confidence、reason。subject 的格式示例：{"name":"玩偶","kind":"object","outline":[[380,225],[470,340],[610,190],[640,235],[615,785],[360,785]]}。每个数值与理由都按真实图像填写。
    """.trimIndent()

    fun request(model: String, jpeg: ByteArray): ByteArray {
        val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        // This route supports non-thinking vision; other compatible endpoints may reject these options.
        if (model == "workbuddy/deepseek-v4.1-flash") {
            body.put("reasoning_effort", "none").put("max_tokens", 1280)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    fun response(body: String): ModelReply? {
        val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
        val finish = choice.optString("finish_reason", "stop")
        if (finish !in setOf("stop", "length", "null")) return null
        val message = choice.getJSONObject("message")
        if (!message.isNull("refusal")) return null
        val content = message.opt("content") as? String ?: return null
        if (content.length > 8192) return null
        if (finish != "length" && Recommendation.hasJsonTokens(content)) {
            val fields = runCatching {
                JsonReader(StringReader(content)).use { reader ->
                    readFields(reader, 0).takeIf { reader.peek() == JsonToken.END_DOCUMENT }
                }
            }.getOrNull()
            if (fields != null) return ModelReply.from(fields, complete = true)
        }
        if (finish != "length") return null
        // A length-limited reply may contain the entire first field. Never accept its plan.
        return JsonReader(StringReader(content)).use { reader ->
            reader.beginObject()
            if (reader.nextName() != "subject") return null
            val subject = readFields(reader, 1)
            ModelReply.from(mapOf("subject" to subject), complete = false)
        }
    }

    private fun readFields(reader: JsonReader, depth: Int): Map<String, Any?> {
        reader.beginObject()
        val fields = mutableMapOf<String, Any?>()
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(key !in fields)
            fields[key] = readValue(reader, depth + 1)
        }
        reader.endObject()
        return fields
    }

    private fun readValue(reader: JsonReader, depth: Int): Any? {
        require(depth <= 5)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readFields(reader, depth)
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
