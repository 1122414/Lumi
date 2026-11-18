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
        subject.name 用 1 至 6 个中文字命名。subject.outline 沿这个实例的可见外轮廓依次取 10 至 16 个关键点，保留突起及凹口，不用包围框，不重复起点。坐标为 [x,y] 整数，左上 [0,0]、右下 [1000,1000]。即使构图难以改善或主体不在中央，也先返回你认出的主体及轮廓。
        再推荐原地转动或仰俯就能拍到的后置 1× 构图，保持主体完整。aimPoint 是新画面中心在本图上的位置，两个坐标均为 0 到 1。已经很好时 alreadyGood=true 且 aimPoint 为中心。若没有可靠构图，降低 confidence，仍完整返回 subject。reason 不超过十字，点出主体名字和构图效果，不给动作指令。
        格式示例（按真实图像填写）：{"subject":{"name":"玩偶","kind":"object","outline":[[380,225],[410,200],[470,340],[550,310],[610,190],[640,235],[605,430],[665,620],[615,785],[360,785],[320,605],[375,430]]},"aimPoint":{"x":0.5,"y":0.5},"confidence":0.86,"alreadyGood":true,"reason":"玩偶耳边留白"}
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
