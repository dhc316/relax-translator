package com.yijian.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class TranslationUpdate(val result: Translation, val analysisState: String, val analysisError: String = "")
private data class BranchReply(val full: Boolean, val result: Translation? = null, val error: Exception? = null)

/** Both calls use the exact same frozen input/context. Neither branch waits for the other to start. */
suspend fun TranslationClient.translateParallel(snapshot: RequestSnapshot, key: String, imageData: String? = null,
    onUpdate: (TranslationUpdate) -> Unit) = supervisorScope {
    if (!snapshot.preset.extras.requestsAny) {
        onUpdate(TranslationUpdate(translateFast(snapshot, key, imageData), "complete"))
        return@supervisorScope
    }
    val replies = Channel<BranchReply>(2)
    fun request(full: Boolean) = launch {
        val reply = try {
            BranchReply(full, if (full) translate(snapshot, key, imageData) else translateFast(snapshot, key, imageData))
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { BranchReply(full, error = error) }
        replies.send(reply)
    }
    val fastJob = request(false)
    val fullJob = request(true)
    var fast: Translation? = null
    var fullError: Exception? = null
    var fastError: Exception? = null
    try {
        repeat(2) {
            val reply = replies.receive()
            if (reply.full) {
                if (reply.result != null) {
                    // The full result's translation and readings stay together. A late quick reply cannot replace it.
                    onUpdate(TranslationUpdate(reply.result, "complete"))
                    fastJob.cancel()
                    return@supervisorScope
                }
                fullError = reply.error
                fast?.let { onUpdate(TranslationUpdate(it, "failed", fullError?.message ?: "补充解析失败")) }
            } else {
                fast = reply.result
                fastError = reply.error
                fast?.let { onUpdate(TranslationUpdate(it, if (fullError == null) "pending" else "failed", fullError?.message.orEmpty())) }
            }
        }
        if (fast == null) throw IllegalStateException("翻译未完成：${fastError?.message ?: "未获取到译文"}\n完整解析：${fullError?.message ?: "未获取到结果"}")
    } finally {
        fastJob.cancel(); fullJob.cancel(); replies.close()
    }
}
