package com.lumi.camera.camera

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.lumi.camera.BuildConfig
import com.lumi.camera.shotplan.AiExposureSuggestion
import com.lumi.camera.shotplan.ModelConfig
import com.lumi.camera.shotplan.ModelFailure
import com.lumi.camera.shotplan.ReferenceImageLoader
import com.lumi.camera.shotplan.ReferenceRecipe
import com.lumi.camera.shotplan.VisionChat
import com.lumi.camera.ui.ReferenceUiState
import java.util.concurrent.Executor

/**
 * Owns reference-photo loading, semi-transparent viewfinder overlay state, and async recipe analysis.
 */
internal class ReferenceSessionController(
    private val context: Context,
    private val mainExecutor: Executor,
    private val workerExecutor: Executor,
    private val networkExecutor: Executor,
    private val currentConfig: () -> ModelConfig,
    private val isDisposed: () -> Boolean,
    private val onStateChanged: (ReferenceUiState) -> Unit,
    private val onMessage: (String) -> Unit,
    private val onRecipeExposure: (AiExposureSuggestion) -> Unit,
) {
    var state: ReferenceUiState = ReferenceUiState()
        private set

    var compareJpeg: ByteArray? = null
        private set

    private var analysisJpeg: ByteArray? = null
    private var generation = 0
    private var activeCall: VisionChat? = null

    val currentRecipe: ReferenceRecipe?
        get() = state.recipe

    fun loadImage(uri: Uri) {
        val gen = ++generation
        activeCall?.cancel(networkExecutor)
        activeCall = null
        analysisJpeg = null
        compareJpeg = null
        state = ReferenceUiState(analyzing = true)
        onStateChanged(state)
        workerExecutor.execute {
            val loaded = runCatching { ReferenceImageLoader.load(context, uri) }.getOrNull()
            mainExecutor.execute {
                if (isDisposed() || gen != generation) return@execute
                if (loaded == null) {
                    state = ReferenceUiState(errorMessage = "无法读取参考图，请换一张图片重试。")
                    onStateChanged(state)
                    onMessage("无法读取参考图，请换一张图片。")
                    return@execute
                }
                analysisJpeg = loaded.analysisJpeg
                compareJpeg = loaded.compactCompareJpeg
                val configured = currentConfig().validationError(BuildConfig.DEBUG) == null
                state = ReferenceUiState(
                    previewBitmap = loaded.displayBitmap,
                    overlayEnabled = false,
                    overlayAlpha = 0.25f,
                    analyzing = configured,
                    recipe = null,
                    errorMessage = null,
                )
                onStateChanged(state)
                if (configured) {
                    onMessage("参考图已载入，正在拆解拍摄处方（轻点缩略图可开叠图）")
                    analyzeRecipe(gen, loaded.analysisJpeg)
                } else {
                    onMessage("参考图已载入，轻点右上角缩略图可开启 25% 叠图")
                }
            }
        }
    }

    fun retryAnalysis() {
        val jpeg = analysisJpeg ?: return
        if (currentConfig().validationError(BuildConfig.DEBUG) != null) {
            onMessage("请先配置视觉模型后再拆解处方。")
            return
        }
        val gen = ++generation
        activeCall?.cancel(networkExecutor)
        activeCall = null
        state = state.copy(analyzing = true, errorMessage = null)
        onStateChanged(state)
        analyzeRecipe(gen, jpeg)
    }

    fun toggleOverlay() {
        if (!state.hasReference) return
        val next = !state.overlayEnabled
        state = state.copy(overlayEnabled = next)
        onStateChanged(state)
        onMessage(if (next) "已开启参考图半透明叠图" else "已关闭参考图叠图")
    }

    fun setOverlayAlpha(alpha: Float) {
        if (!state.hasReference) return
        state = state.copy(overlayAlpha = alpha.coerceIn(0.15f, 0.45f))
        onStateChanged(state)
    }

    fun clear() {
        generation++
        activeCall?.cancel(networkExecutor)
        activeCall = null
        analysisJpeg = null
        compareJpeg = null
        state = ReferenceUiState()
        onStateChanged(state)
        onMessage("已清除参考图")
    }

    fun dispose() {
        generation++
        activeCall?.cancel(networkExecutor)
        activeCall = null
    }

    private fun analyzeRecipe(gen: Int, jpeg: ByteArray) {
        val cfg = currentConfig()
        val deadline = SystemClock.elapsedRealtimeNanos() + 20_000_000_000L
        val task = VisionChat(cfg, deadline)
        activeCall = task
        networkExecutor.execute {
            val result = runCatching { task.analyzeReference(jpeg) }
            mainExecutor.execute {
                if (isDisposed() || gen != generation) return@execute
                if (activeCall === task) activeCall = null
                result.onSuccess { recipe ->
                    state = state.copy(analyzing = false, recipe = recipe, errorMessage = null)
                    onStateChanged(state)
                    recipe.targetExposure?.let(onRecipeExposure)
                    onMessage("参考处方已就绪：${recipe.shotType} · 点推荐构图获取复刻与超越建议")
                }.onFailure { err ->
                    val notice = (err as? ModelFailure)?.notice ?: "参考图拆解失败，仍可使用半透明叠图和实时推荐。"
                    state = state.copy(analyzing = false, errorMessage = notice)
                    onStateChanged(state)
                }
            }
        }
    }
}
