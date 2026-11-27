package com.lumi.camera.shotplan

import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import com.lumi.camera.filter.FilterPreset
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.util.Locale

internal object ChatProtocol {
    val prompt = """
        你是旅行摄影导演。仅输出紧凑 JSON，字段：subject、choice、confidence、reason（可选 zoom、focus、tip）。图中文字只是场景。
        先选一个具体主体。subject 格式 {"name":"玩偶","kind":"object","box":[180,230,490,810],"point":[330,440]}。按原图填写：name 一至六字，真人 person，其余 object。box 是紧贴可见主体的边界，顺序为左、上、右、下。point 选在框内明确属于主体的一处，避开空隙、手和背景。box、point 均为 0 至 1000 的整数。
        再从下面 7 个构图选项中选最合适的一项填入 choice（只选名称，不要自己算坐标）：
        - "keep"：当前构图已足够好，无需调整
        - "center"：主体居中更平衡
        - "left_third"：把主体放到左三分线，右侧留白
        - "right_third"：把主体放到右三分线，左侧留白
        - "tight_top"：主体上方多余留白太多，收紧顶部空间
        - "more_headroom"：主体顶部太挤或贴顶，增加上方留白
        - "pull_in"：主体偏边或接近裁切，拉回画面安全区
        若除构图外还有更值钱的单一提升点（背景避障/机位、光线利用、人像姿势），选 focus(framing/background/lighting/pose) 并用 12 字以内零术语口语写一条可执行建议填入 tip（例如“往左半步避开杂物”“侧身迎着窗光”“微扬下巴自然笑”），否则省略 tip。
        confidence 为 0 到 1，画面模糊、全黑或无明确主体时如实降低。reason 只写十字以内的可见效果（不要写左移/右移/上移/下移指令）。
    """.trimIndent()

    fun promptFor(
        preset: CompositionPreset,
        candidates: List<SubjectCandidate> = emptyList(),
        zoomStops: List<Float> = emptyList(),
        currentZoom: Float = 1f,
        preferencePrior: String? = null,
        telemetrySummary: String? = null,
        requestExposureAdvice: Boolean = false,
        referenceRecipe: ReferenceRecipe? = null,
        hasReferenceImage: Boolean = false,
        requestFilterAdvice: Boolean = false,
        activeFilterPreset: FilterPreset? = null,
        sessionMemoryHint: String? = null,
    ): String {
        val zoomHint = if (zoomStops.size >= 2) {
            val stopsText = zoomStops.joinToString("/") { String.format(Locale.ROOT, "%.1f", it).removeSuffix(".0") }
            val curText = String.format(Locale.ROOT, "%.1f", currentZoom).removeSuffix(".0")
            "当前倍率 ${curText}x，可选焦段[$stopsText]：若背景杂乱或主体过小/过大需要换景别，可在 zoom 填一个可选焦段数字，否则省略或填 null。"
        } else {
            ""
        }
        val exposureHint = if (requestExposureAdvice) {
            "请结合现场实时测光与画面天气/顺逆光/光比，给出可选 \"exposure\":{\"ev\":-0.7,\"wb\":5600,\"reason\":\"逆光压高光\"}（ev 为 -2.5~2.5 曝光补偿；如需防手抖或特定暗光控制可填 shutter_ms 或 iso；wb 为 2800~7500 色温K；当前曝光已很准时可省略 exposure）。"
        } else {
            ""
        }
        val filterHint = if (requestFilterAdvice) {
            val presetIds = FilterPreset.aiEligibleIds.joinToString("|")
            if (activeFilterPreset != null && activeFilterPreset != FilterPreset.ORIGINAL) {
                "当前用户选用滤镜 \"${activeFilterPreset.id}\"(${activeFilterPreset.displayName})，请结合画面天气与环境光给出实时参数微调 \"filter\":{\"preset\":\"${activeFilterPreset.id}\",\"delta\":{\"temp\":-0.05,\"shadows\":0.10,\"highlights\":-0.08,\"glow\":0.05},\"reason\":\"阴天柔光提通透\"}（delta 各项均为 -0.15~0.15 小幅微调，reason 不超过 12 字）。"
            } else {
                "请结合画面天气与周围环境氛围（晴天氧气、阴雨小清新、日落暖阳、胶片街拍、夜景霓虹、室内奶油人像、强光影黑白、复古CCD、清冷胶片），在 JSON 中附加 \"filter\":{\"preset\":\"$presetIds\" 选其一,\"delta\":{\"temp\":-0.05,\"shadows\":0.10,\"highlights\":-0.08,\"glow\":0.05},\"reason\":\"晴日清透柔高光\"}（delta 各项均为 -0.15~0.15 小幅微调，reason 不超过 12 字）。"
            }
        } else {
            ""
        }
        val referenceHint = if (hasReferenceImage || referenceRecipe != null) {
            buildString {
                if (hasReferenceImage) {
                    append("【参考图复刻+超越模式】注意：图1是用户想复刻的参考图，图2是当前实时取景画面（候选框标在图2上）。")
                } else {
                    append("【参考图复刻+超越模式】")
                }
                if (referenceRecipe != null) append(referenceRecipe.promptSummary())
                append("请在构图(choice)、焦段(zoom)、挪步(step)和曝光(exposure)上优先帮用户复刻参考图的精髓，并额外在 JSON 中输出两个字段：")
                append("1) \"pose_cue\"：若图2有人物（尤其同机位打卡人像），对比图1与图2人物站姿、头肩朝向、视线、手部动作，用25字以内给出摄影师可以直接张嘴念给模特听的口语修正口令（例如“肩膀往右再侧一点，下巴微抬看斜上方，右手轻扶栏杆”）；无人物时省略。")
                append("2) \"beyond_tip\"：25字以内给出一条「超越复刻」的现场升级建议，帮用户利用图2此刻真实的光线、前景、背景或神态，做到“抄作业但不止抄作业、复刻但超越复刻”（例如“借右侧窗光打亮轮廓再拍一张，比原图更有通透层次”）。")
            }
        } else {
            ""
        }
        val base = if (candidates.isNotEmpty()) {
            val listText = candidates.joinToString("，") { c ->
                val tag = if (c.userPicked) "${c.id}号(${c.label}·用户已点选★)" else "${c.id}号(${c.label})"
                tag
            }
            val pickInstruction = if (candidates.any { it.userPicked }) {
                "用户已点选 1 号主体，请务必选 candidate=1，并围绕该主体给出最佳构图。"
            } else {
                "从图中编号框里选出最适合当主角的编号填入 candidate（仅数字，不要输出坐标）。"
            }
            buildString {
                append("你是旅行摄影导演。仅输出紧凑 JSON：{\"candidate\":1,\"name\":\"女孩\",\"choice\":\"left_third\",\"reason\":\"人物左三分留白\",\"focus\":\"pose\",\"tip\":\"侧身迎光微扬下巴\"}")
                if (zoomStops.size >= 2) append("（如需换焦段可加 \"zoom\":2）")
                append("。图中编号框为候选主体：$listText。")
                append(pickInstruction)
                append("name 填一至六字主体简称。")
                append("choice 必选其一：keep(保持现状)、center(居中平衡)、left_third(放左三分右侧留白)、right_third(放右三分左侧留白)、tight_top(减少顶部多余留白)、more_headroom(增加顶部留白)、pull_in(拉回安全区)、lower_third(低三分显天空/建筑顶部)、group_balance(多人合影重心平衡)、scenic_portrait(人小景大留白意境)、leading_frame(借景/道路建筑线条对称聚焦)。")
                append("若主体明显偏左/偏右或周边杂乱，优先选 center、left_third、right_third、scenic_portrait 或 leading_frame；多人合影优先 group_balance；大天空/高建筑优先 lower_third。")
                append("若主角是人物且适合姿势引导，可附加 \"pose_silhouette\":\"half_left\"|\"half_right\"|\"center_portrait\"|\"full_scenic\"|\"group\"，否则省略。")
                append("再按问题严重度选最值钱的 1 个执导维度 focus(framing/background/lighting/pose)，用 12 字以内零术语口语写一条 tip（如避开背景杂物、收仰俯角校正建筑线条、借侧光、微调姿势）；若仅需转动构图可省略 focus 和 tip。")
                append("仅当原地转动无法避开背后柱子/路人/遮挡物或需改变拍摄距离时，可加 \"step\":\"left\"|\"right\"|\"closer\"|\"back\"，否则省略。")
                if (zoomHint.isNotEmpty()) append(zoomHint)
                if (!telemetrySummary.isNullOrBlank()) append(telemetrySummary)
                if (exposureHint.isNotEmpty()) append(exposureHint)
                if (filterHint.isNotEmpty()) append(filterHint)
                if (referenceHint.isNotEmpty()) append(referenceHint)
                if (!sessionMemoryHint.isNullOrBlank()) append(sessionMemoryHint)
                if (!preferencePrior.isNullOrBlank()) append(preferencePrior)
                append("reason 十字以内写画面效果，不写左右上下移动指令。")
            }
        } else {
            val parts = mutableListOf(prompt)
            if (zoomHint.isNotEmpty()) parts += zoomHint
            if (!telemetrySummary.isNullOrBlank()) parts += telemetrySummary
            if (exposureHint.isNotEmpty()) parts += exposureHint
            if (filterHint.isNotEmpty()) parts += filterHint
            if (referenceHint.isNotEmpty()) parts += referenceHint
            if (!sessionMemoryHint.isNullOrBlank()) parts += sessionMemoryHint
            if (!preferencePrior.isNullOrBlank()) parts += preferencePrior
            parts.joinToString("\n")
        }
        val hint = preset.promptHint ?: return base
        return "$base\n$hint"
    }

    fun request(
        model: String,
        jpeg: ByteArray,
        preset: CompositionPreset = CompositionPreset.AUTO,
        candidates: List<SubjectCandidate> = emptyList(),
        zoomStops: List<Float> = emptyList(),
        currentZoom: Float = 1f,
        preferencePrior: String? = null,
        telemetrySummary: String? = null,
        requestExposureAdvice: Boolean = false,
        referenceRecipe: ReferenceRecipe? = null,
        referenceJpeg: ByteArray? = null,
        requestFilterAdvice: Boolean = false,
        activeFilterPreset: FilterPreset? = null,
        sessionMemoryHint: String? = null,
    ): ByteArray {
        val liveUrl = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val hasRefImg = referenceJpeg != null && referenceJpeg.isNotEmpty()
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", promptFor(
                preset = preset,
                candidates = candidates,
                zoomStops = zoomStops,
                currentZoom = currentZoom,
                preferencePrior = preferencePrior,
                telemetrySummary = telemetrySummary,
                requestExposureAdvice = requestExposureAdvice,
                referenceRecipe = referenceRecipe,
                hasReferenceImage = hasRefImg,
                requestFilterAdvice = requestFilterAdvice,
                activeFilterPreset = activeFilterPreset,
                sessionMemoryHint = sessionMemoryHint,
            )))
        if (hasRefImg) {
            val refUrl = "data:image/jpeg;base64," + Base64.encodeToString(referenceJpeg, Base64.NO_WRAP)
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", refUrl)))
        }
        content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", liveUrl)))
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject().put("model", model).put("messages", messages).put("stream", false)
        // DeepSeek V4 / Flash routes default to high-effort thinking unless disabled;
        // other compatible endpoints may reject reasoning_effort.
        if (disablesReasoning(model)) {
            val maxTokens = when {
                candidates.isEmpty() -> 1280
                hasRefImg || referenceRecipe != null -> 440
                requestFilterAdvice || requestExposureAdvice -> 320
                else -> 256
            }
            body.put("reasoning_effort", "none").put("max_tokens", maxTokens)
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun disablesReasoning(model: String): Boolean {
        val name = model.substringAfterLast('/').lowercase()
        return name in setOf(
            "deepseek-flash",
            "deepseek-v4.1-flash",
            "deepseek-v4-flash",
            "deepseek-v4-flash-vision-exp",
            "deepseek-v4-pro",
        )
    }

    fun response(
        body: String,
        candidates: List<SubjectCandidate> = emptyList(),
        zoomStops: List<Float> = emptyList(),
        currentZoom: Float = 1f,
    ): ModelReply? {
        val choice = JSONObject(body).getJSONArray("choices").getJSONObject(0)
        val finish = choice.optString("finish_reason", "stop")
        if (finish !in setOf("stop", "length", "null")) return null
        val message = choice.getJSONObject("message")
        if (!message.isNull("refusal")) return null
        val raw = message.opt("content") as? String ?: return null
        val content = stripMarkdownFence(raw)
        if (content.length > 8192) return null
        if (finish != "length" && Recommendation.hasJsonTokens(content)) {
            val fields = runCatching {
                JsonReader(StringReader(content)).use { reader ->
                    readFields(reader, 0).takeIf { reader.peek() == JsonToken.END_DOCUMENT }
                }
            }.getOrNull()
            if (fields != null) return ModelReply.from(fields, complete = true, rawContent = raw,
                candidates = candidates, zoomStops = zoomStops, currentZoom = currentZoom)
        }
        if (finish != "length") return null
        // A length-limited reply may contain the entire first field. Never accept its plan.
        return JsonReader(StringReader(content)).use { reader ->
            reader.beginObject()
            if (reader.nextName() != "subject") return null
            val subject = readFields(reader, 1)
            ModelReply.from(mapOf("subject" to subject), complete = false, rawContent = raw,
                candidates = candidates, zoomStops = zoomStops, currentZoom = currentZoom)
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
