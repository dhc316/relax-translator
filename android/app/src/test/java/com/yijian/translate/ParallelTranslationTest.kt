package com.yijian.translate

import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ParallelTranslationTest {
    private fun envelope(content: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()
    private fun snapshot(server: MockWebServer) = RequestSnapshot("p", "测试", "photo.jpg",
        Preset("p", target = "ja", modelId = "m", context = ContextPolicy(true)),
        ModelConfig("m", "测试", "http://127.0.0.1:${server.port}/v1", "vision", vision = true),
        listOf(HistoryContext("history", 1, "旧原文", "旧译文", "ja")))
    private fun fast(request: RecordedRequest) = JSONObject(request.body.clone().readUtf8()).getJSONArray("messages").getJSONObject(0).getString("content").contains("本次仅返回翻译结果")
    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private val image = "data:image/jpeg;base64,cGlj"

    @Test fun fastAndFullRequestsShareInputContextImageAndModelButHaveDifferentOutputContracts() {
        val server = server()
        try {
            val snap = snapshot(server).let { it.copy(model = it.model.copy(jsonMode = true, modernTokens = true)) }
            val a = requestBody(snap, image, true); val b = requestBody(snap, image)
            val am = a.getJSONArray("messages"); val bm = b.getJSONArray("messages")
            for (i in 1 until am.length()) assertEquals(bm.getJSONObject(i).toString(), am.getJSONObject(i).toString())
            assertEquals(b.getString("model"), a.getString("model"))
            assertEquals(b.getInt("max_completion_tokens"), a.getInt("max_completion_tokens"))
            assertEquals("json_object", a.getJSONObject("response_format").getString("type"))
            assertTrue(am.getJSONObject(0).getString("content").contains("只有 translation"))
            assertTrue(bm.getJSONObject(0).getString("content").contains("source_reading"))
            assertFalse(requestBody(snap.copy(model = snap.model.copy(jsonMode = false)), image, true).has("response_format"))
        } finally { server.shutdown() }
    }

    @Test fun bothRequestsStartBeforeEitherFinishesAndFastResultIsPublishedBeforeFull() = runBlocking {
        val server = server(); val both = CountDownLatch(2); val releaseFull = CountDownLatch(1)
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    both.countDown()
                    if (!both.await(3, TimeUnit.SECONDS)) return MockResponse().setResponseCode(500)
                    if (!fast(request)) releaseFull.await(4, TimeUnit.SECONDS)
                    return MockResponse().setBody(envelope(if (fast(request)) "速译" else """{"translation":"完整译文","target_reading":{"language":"ja","kana":"テスト","romaji":"tesuto"}}"""))
                }
            }
            val updates = mutableListOf<TranslationUpdate>()
            withTimeout(6000) {
                TranslationClient().translateParallel(snapshot(server), "test-key", image) { update ->
                    updates += update
                    if (update.analysisState == "pending") {
                        assertEquals(0L, both.count); assertEquals("速译", update.result.text)
                        assertTrue(update.result.warning.isBlank()); assertTrue(update.result.targetReading.romaji.isBlank())
                        releaseFull.countDown()
                    }
                }
            }
            assertEquals(listOf("pending", "complete"), updates.map { it.analysisState })
            assertEquals("完整译文", updates.last().result.text)
            assertEquals("tesuto", updates.last().result.targetReading.romaji)
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("Bearer test-key", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")) }
        } finally { releaseFull.countDown(); server.shutdown() }
    }

    @Test fun fullResultCanWinAndLateFastReplyCannotOverwriteIt() = runBlocking {
        val server = server(); val releaseFast = CountDownLatch(1)
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (fast(request)) releaseFast.await(4, TimeUnit.SECONDS)
                    return MockResponse().setBody(envelope(if (fast(request)) "迟到的速译" else "{\"translation\":\"完整译文\"}"))
                }
            }
            val updates = mutableListOf<TranslationUpdate>()
            withTimeout(2000) { TranslationClient().translateParallel(snapshot(server), "", image) { updates += it } }
            assertEquals(listOf("complete"), updates.map { it.analysisState })
            assertEquals("完整译文", updates.single().result.text)
        } finally { releaseFast.countDown(); server.shutdown() }
    }

    @Test fun failedFastRequestDoesNotCancelTheFullRequest() = runBlocking {
        val server = server()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (fast(request)) MockResponse().setResponseCode(500)
                    else MockResponse().setBody(envelope("{\"translation\":\"完整成功\"}" )).setHeadersDelay(100, TimeUnit.MILLISECONDS)
            }
            val updates = mutableListOf<TranslationUpdate>()
            TranslationClient().translateParallel(snapshot(server), "", image) { updates += it }
            assertEquals("complete", updates.single().analysisState); assertEquals("完整成功", updates.single().result.text)
        } finally { server.shutdown() }
    }

    @Test fun failedFullRequestKeepsFastTranslationAndReportsPartialFailure() = runBlocking {
        val server = server()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (fast(request)) MockResponse().setBody(envelope("保留速译"))
                    else MockResponse().setResponseCode(503).setHeadersDelay(150, TimeUnit.MILLISECONDS)
            }
            val updates = mutableListOf<TranslationUpdate>()
            TranslationClient().translateParallel(snapshot(server), "", image) { updates += it }
            assertEquals("failed", updates.last().analysisState); assertEquals("保留速译", updates.last().result.text)
            assertTrue(updates.last().analysisError.contains("503"))
        } finally { server.shutdown() }
    }

    @Test fun fullFailureBeforeFastSuccessStillPublishesUsableTranslation() = runBlocking {
        val server = server()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (fast(request)) MockResponse().setBody(envelope("可用译文")).setHeadersDelay(150, TimeUnit.MILLISECONDS)
                    else MockResponse().setResponseCode(503)
            }
            val updates = mutableListOf<TranslationUpdate>()
            TranslationClient().translateParallel(snapshot(server), "", image) { updates += it }
            assertEquals("failed", updates.single().analysisState); assertEquals("可用译文", updates.single().result.text)
        } finally { server.shutdown() }
    }

    @Test fun bothFailuresSurfaceAnErrorWithoutExposingCredentials() = runBlocking {
        val server = server()
        try {
            repeat(2) { server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad secret-unit\"}}")) }
            val error = runCatching { TranslationClient().translateParallel(snapshot(server), "secret-unit", image) { fail("must not publish a failed result") } }.exceptionOrNull()!!
            assertTrue(error.message!!.contains("401")); assertFalse(error.message!!.contains("secret-unit"))
        } finally { server.shutdown() }
    }

    @Test fun cancellingTheOperationStopsBothBranchesWithoutPublishingLateResults() = runBlocking {
        val server = server(); val both = CountDownLatch(2); val release = CountDownLatch(1)
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    both.countDown(); release.await(4, TimeUnit.SECONDS)
                    return MockResponse().setBody(envelope("{\"translation\":\"late\"}"))
                }
            }
            val updates = mutableListOf<TranslationUpdate>()
            val job = launch { TranslationClient().translateParallel(snapshot(server), "", image) { updates += it } }
            assertTrue(withContext(Dispatchers.IO) { both.await(3, TimeUnit.SECONDS) })
            withTimeout(1500) { job.cancelAndJoin() }; release.countDown(); assertTrue(updates.isEmpty())
        } finally { release.countDown(); server.shutdown() }
    }

    @Test fun quickResultSurvivesRelaunchWithoutAnEndlessPendingState() {
        val server = server()
        try {
            val record = Record("one", snapshot(server), Translation("已保存"), 120, "pending", firstResultMs = 120)
            val restored = Record.from(JSONObject(record.json().toString()))
            assertEquals("已保存", restored.result.text); assertEquals("interrupted", restored.analysisState)
            assertEquals(120L, restored.firstResultMs); assertTrue(restored.analysisError.contains("中断"))
            assertEquals("complete", Record.from(JSONObject(record.copy(analysisState = "complete").json().toString())).analysisState)
        } finally { server.shutdown() }
    }
}
