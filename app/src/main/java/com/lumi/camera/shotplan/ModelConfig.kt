package com.lumi.camera.shotplan

import java.net.URI
import java.net.URL

internal fun chatEndpoint(value: String, debug: Boolean): URL? = runCatching {
    val base = URI(value)
    require(base.scheme.equals("https", true) || (debug && base.scheme.equals("http", true)))
    require(!base.host.isNullOrBlank() && base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null)
    require(!base.rawAuthority.endsWith(':') && (base.port == -1 || base.port in 1..65535))
    val root = base.toASCIIString().trimEnd('/')
    when {
        base.rawPath.trim('/').isEmpty() -> URL("$root/v1/chat/completions")
        root.endsWith("/chat/completions") -> URL(root)
        else -> URL("$root/chat/completions")
    }
}.getOrNull()

internal data class ModelConfig(val address: String = "", val model: String = "", val apiKey: String = "") {
    fun validationError(debug: Boolean): String? = when {
        address.isBlank() -> "请填写接口地址。"
        chatEndpoint(address, debug) == null -> if (debug) "请填写有效的 HTTP 或 HTTPS 接口地址。" else "请填写有效的 HTTPS 接口地址。"
        model.isBlank() || model.any(Char::isISOControl) -> "请填写有效的模型名。"
        apiKey.isBlank() || apiKey.any { it.code !in 33..126 } -> "请填写有效的 API key。"
        else -> null
    }

    fun safeText(text: String): String = if (apiKey.isEmpty()) text else text.replace(apiKey, "［已隐藏］")

    fun recordText(details: String, debug: Boolean): String =
        safeText("model=$model host=${chatEndpoint(address, debug)?.host.orEmpty()} $details")

    override fun toString() = "ModelConfig(已隐藏)"
}

internal fun initialModelConfig(hasSaved: Boolean, debug: Boolean,
    saved: () -> ModelConfig, defaults: () -> ModelConfig): ModelConfig = when {
    hasSaved -> saved()
    debug -> defaults()
    else -> ModelConfig()
}
