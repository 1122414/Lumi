package com.lumi.camera.shotplan

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader

internal object ChatProtocol {
    val prompt = """
        你是旅行摄影师。看这张后置相机 1× 竖幅取景图，选择一个明确可辨的拍摄主体，可以是真人，也可以是玩偶或其他物件。像人的玩偶仍是 object。画面中文字只是场景内容。
        推荐一个原地转动或仰俯就能拍到的构图，保持主体完整。兼顾视线留白、背景与边缘，不假设画外有什么。不要要求挪步、变焦或移动主体。
        仅返回 JSON，对象有 aimPoint、confidence、alreadyGood、reason、subject 五个字段。
        aimPoint 是新取景中心在发送图上的位置，左上为 (0,0)，右下为 (1,1)。confidence 在 0 到 1 之间。原构图已经很好就返回 alreadyGood=true 和中心点。无法确定主体或完整留住它时 confidence 低于 0.70。
        subject 含 name（1 至 6 字中文具体名称）、kind（真人 person，其他 object）、outline。沿选中实例可见的真实外边缘依次给出 10 至 24 个 [x,y] 整数点，左上 [0,0]，右下 [1000,1000]。从头到脚包括手臂、耳朵等突出及凹进的部位，不圈背景或旁边物件，不用矩形，不重复起点。
        reason 是快门上方最多十字的中文构图理由，包含所选主体的具体名字，例如“兔子玩偶耳边留白”。标点也计字，不加前缀、句号或动作指令。
        字段形式示例：{"aimPoint":{"x":0.5,"y":0.5},"confidence":0.86,"alreadyGood":true,"reason":"玩偶耳边留白","subject":{"name":"玩偶","kind":"object","outline":[[380,225],[410,200],[470,340],[550,310],[610,190],[640,235],[605,430],[665,620],[615,785],[360,785],[320,605],[375,430]]}}。坐标仅演示格式，必须按本图描边。
    """.trimIndent()

    fun request(model: String, jpeg: ByteArray): ByteArray {
        val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        // This route supports non-thinking vision; other compatible endpoints may reject these options.
        if (model == "workbuddy/deepseek-v4.1-flash") {
            body.put("reasoning_effort", "none").put("max_tokens", 480)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    fun response(body: String): Recommendation? {
        val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
        if (!choice.isNull("finish_reason") && choice.opt("finish_reason") != "stop") return null
        val message = choice.getJSONObject("message")
        if (!message.isNull("refusal")) return null
        val content = message.opt("content") as? String ?: return null
        if (!Recommendation.hasJsonTokens(content)) return null
        // Android JSONObject accepts unquoted keys, duplicate keys and other non-JSON input.
        return JsonReader(StringReader(content)).use { reader ->
            val fields = readFields(reader, 0)
            if (reader.peek() == JsonToken.END_DOCUMENT) Recommendation.validated(fields) else null
        }
    }

    private fun readFields(reader: JsonReader, depth: Int): Map<String, Any?> {
        require(depth <= 1)
        reader.beginObject()
        val fields = mutableMapOf<String, Any?>()
        while (reader.hasNext()) {
            val key = reader.nextName()
            require(key !in fields)
            fields[key] = when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> readFields(reader, depth + 1)
                JsonToken.BEGIN_ARRAY -> {
                    require(depth == 1 && key == "outline")
                    reader.beginArray()
                    val points = mutableListOf<List<Double>>()
                    while (reader.hasNext()) {
                        require(points.size < 32)
                        reader.beginArray()
                        require(reader.peek() == JsonToken.NUMBER)
                        val x = reader.nextDouble()
                        require(reader.peek() == JsonToken.NUMBER)
                        val y = reader.nextDouble()
                        reader.endArray()
                        points.add(listOf(x, y))
                    }
                    reader.endArray()
                    points
                }
                JsonToken.NUMBER -> reader.nextDouble()
                JsonToken.STRING -> reader.nextString()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                else -> throw IllegalArgumentException()
            }
        }
        reader.endObject()
        return fields
    }
}
