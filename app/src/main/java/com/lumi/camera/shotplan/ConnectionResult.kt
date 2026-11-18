package com.lumi.camera.shotplan

internal enum class ConnectionResult(val label: String) {
    SUCCESS("成功"),
    UNREACHABLE("地址不可达或超时"),
    AUTH("鉴权失败"),
    FORMAT("模型不支持图片或返回格式不对"),
    SERVICE("接口请求失败");

    companion object {
        fun httpFailure(code: Int) = when (code) {
            401, 403 -> AUTH
            408, 504 -> UNREACHABLE
            400, 415, 422 -> FORMAT
            else -> SERVICE
        }

        fun fromReply(reply: ModelReply?) = when (reply?.decision) {
            is PlanResult.Accepted, PlanResult.Rejected(PlanProblem.LOW_CONFIDENCE) -> SUCCESS
            else -> FORMAT
        }

        fun fromFailure(error: Exception) = when (error) {
            is ModelFailure -> error.kind
            is java.io.IOException -> UNREACHABLE
            else -> SERVICE
        }
    }
}

internal class ModelFailure(val kind: ConnectionResult, val notice: String, val httpCode: Int? = null) : Exception()
