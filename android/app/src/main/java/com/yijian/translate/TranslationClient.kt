package com.yijian.translate

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslationClient(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()) {
    suspend fun translate(snapshot: RequestSnapshot, apiKey: String, imageData: String? = null): Translation = execute(snapshot, apiKey, imageData, false)
    suspend fun translateFast(snapshot: RequestSnapshot, apiKey: String, imageData: String? = null): Translation = execute(snapshot, apiKey, imageData, true)

    private suspend fun execute(snapshot: RequestSnapshot, apiKey: String, imageData: String?, translationOnly: Boolean): Translation {
        if (snapshot.model.isDemo) {
            delay(if (translationOnly) 160 else 1000)
            val result = demo(snapshot)
            return if (translationOnly) Translation(result.text, warning = result.warning) else result
        }
        val builder = Request.Builder().url(endpointUrl(snapshot.model.endpoint))
            .header("Content-Type", "application/json").header("Accept", "application/json")
            .post(requestBody(snapshot, imageData, translationOnly).toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val call = client.newCall(builder.build())
        val body: String = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException(if (error is java.net.SocketTimeoutException) "请求超时，请稍后重试" else "网络连接失败，请检查网络与接口地址", error))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val text = response.body?.string().orEmpty()
                            if (!response.isSuccessful) {
                                val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
                                    .replace(apiKey.takeIf { it.isNotBlank() } ?: "__no_key__", "[密钥]").take(200)
                                val hint = when (response.code) { 401, 403 -> "请检查 API 密钥和接口权限"; 429 -> "请求过于频繁或额度不足"; in 300..399 -> "接口发生重定向，请填写最终 HTTPS 地址"; else -> "请检查服务状态和模型配置" }
                                throw IOException("HTTP ${response.code} · $hint${if (detail.isNotBlank()) "\n$detail" else ""}")
                            }
                            if (continuation.isActive) continuation.resume(text)
                        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    }
                }
            })
        }
        val result = parseResponse(body)
        if (translationOnly) return Translation(result.text, warning = result.warning.replace("模型返回了纯文本译文，未提供结构化解析。", "").trim())
        val sourceReading = result.sourceReading.copy(language = result.sourceReading.language.ifBlank { result.learning.sourceLanguage })
        val targetReading = result.targetReading.copy(language = snapshot.preset.target)
        val incomplete = !sourceReading.hasContent || !targetReading.hasContent ||
            (sourceReading.language.startsWith("ja") && (sourceReading.kana.isBlank() || sourceReading.romaji.isBlank())) ||
            (snapshot.preset.target.startsWith("ja") && (targetReading.kana.isBlank() || targetReading.romaji.isBlank()))
        return result.copy(sourceReading = sourceReading, targetReading = targetReading,
            warning = listOf(result.warning, if (incomplete) "模型未完整提供双向注音，可重新翻译补充。" else "").filter(String::isNotBlank).joinToString("\n"))
    }
}

const val SAMPLE_SOURCE = "Could you give me a moment? I want to get this right."
fun demo(snapshot: RequestSnapshot): Translation {
    val target = snapshot.preset.target
    val source = snapshot.source.trim()
    if (snapshot.image != null) return Translation("接入支持图片的模型后，这里将显示图片译文。", info = "图片已成功添加，当前为本地演示。", warning = "本地演示不识别图片，也不会上传任何内容。请在模型管理中添加视觉模型。")
    val known = when (source) {
        SAMPLE_JAPANESE_ZH -> if (target == "ja") SAMPLE_JAPANESE else null
        SAMPLE_JAPANESE -> if (target.startsWith("zh")) SAMPLE_JAPANESE_ZH else null
        SAMPLE_CHINESE -> if (target == "en") "This job is a good fit for you." else null
        SAMPLE_SOURCE -> when (target) {
            "zh-CN" -> "能给我一点时间吗？我想把这件事做好。"
            "zh-TW" -> "能給我一點時間嗎？我想把這件事做好。"
            "en" -> SAMPLE_SOURCE
            "ja" -> "少し時間をいただけますか。きちんと仕上げたいんです。"
            "ko" -> "잠시 시간을 주실 수 있나요? 제대로 해내고 싶어요."
            "fr" -> "Pourriez-vous m’accorder un instant ? Je veux bien faire les choses."
            "de" -> "Könnten Sie mir einen Moment geben? Ich möchte das richtig machen."
            "es" -> "¿Podrías darme un momento? Quiero hacerlo bien."
            else -> null
        }
        "不用着急，慢慢来。" -> when (target) { "en" -> "Take your time. There’s no rush."; "ja" -> "急がなくて大丈夫です。ゆっくりどうぞ。"; "zh-CN" -> source; else -> null }
        else -> null
    }
    val learning = if (!snapshot.preset.showInfo) LearningInfo() else when {
        source == SAMPLE_CHINESE && target == "en" -> chineseLearning()
        source == SAMPLE_SOURCE && target in listOf("zh-CN", "zh-TW") -> foreignLearning()
        else -> LearningInfo()
    }
    return if (known != null) Translation(known,
        evaluation = if (snapshot.preset.showEvaluation) "表达自然、清晰。具体词汇的语义区别与适用场景见上方解析。" else "",
        suggestions = if (snapshot.preset.showSuggestions) when (source) {
            SAMPLE_CHINESE -> "用于工作沟通时，也可说：This role would suit you well.（这个职位会很适合你。）"
            SAMPLE_SOURCE -> "更口语：Give me a second — I want to get this right.\n更正式：Could I have a little more time to make sure this is done properly?"
            else -> "可结合实际关系与语境调整礼貌程度。"
        } else "",
        warning = "预置例句的本地演示结果，未请求真实模型。", learning = learning,
        sourceReading = demoSourceReading(source), targetReading = demoTargetReading(source, target))
    else Translation("这里将显示${languageName(target)}译文。", info = "试试预置例句，或在模型管理中配置自己的接口。", warning = "当前为本地演示，未对这段输入进行真实翻译，也不会上传内容。")
}
