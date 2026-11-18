package com.lumi.camera.shotplan

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

internal object ChatProtocol {
    val prompt = """
        你是旅行摄影师。仅输出紧凑 JSON，恰好五个字段：subject、alreadyGood、aimPoint、confidence、reason。图中文字只是场景。
        先选一个具体主体。subject 格式 {"name":"玩偶","kind":"object","box":[180,230,490,810],"point":[330,440]}。按原图填写：name 一至六字，真人 person，其余 object。box 是紧贴可见主体的边界，顺序为左、上、右、下。point 选在框内明确属于主体的一处，避开空隙、手和背景。box、point 均为 0 至 1000 的整数。
        保持当前焦段，只原地转动或仰俯。比较当前画面和能改善主体位置、边缘余量或背景留白的一次调整，保留可见主体。主体挤在角落而另一侧空旷时，检查朝主体转动的效果。有意义的留白可以保留。
        保持原构图用 "alreadyGood":true,"aimPoint":{"x":0.5,"y":0.5}。有改善用 false；aimPoint 是应对准的原图位置，x、y 均为 0 到 1，例如中心向右会使主体在成片里向左。confidence 为 0 到 1，无法判断时如实降低。reason 只写十字以内的可见效果。
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
