package com.yijian.translate

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ExtraContentTest {
    private fun snapshot(extras: ExtraContent, model: ModelConfig = ModelConfig.demo) = RequestSnapshot("p", SAMPLE_SOURCE, null,
        Preset("p", extraContent = extras), model, emptyList())
    private fun envelope(content: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString()
    private fun server() = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    private fun model(server: MockWebServer) = ModelConfig("m", "测试", "http://127.0.0.1:${server.port}/v1", "vision", vision = true)

    @Test fun legacyOutputPreferencesMigrateWithoutLosingReadingsOrChangingOtherSections() {
        val old = JSONObject().put("id", "old").put("showInfo", false).put("showEvaluation", true).put("showSuggestions", false)
        val restored = Preset.from(old)
        assertEquals(setOf(ExtraSection.READINGS, ExtraSection.EVALUATION), restored.extras.sections)
        assertTrue(restored.extras.requestsAny)
        assertEquals(restored, Preset.from(JSONObject(restored.json().toString())))
    }

    @Test fun disabledAndEmptySelectionsRemainDistinctAndSurvivePresetAndSnapshotStorage() {
        val chosen = ExtraContent(false, setOf(ExtraSection.USAGE, ExtraSection.CORRECTIONS))
        val snap = snapshot(chosen)
        val restored = RequestSnapshot.from(JSONObject(snap.json().toString()))
        assertEquals(chosen, restored.preset.extras)
        assertFalse(restored.preset.extras.requestsAny)
        assertEquals(chosen.sections, restored.preset.extras.copy(enabled = true).sections)
        val empty = ExtraContent.from(JSONObject().put("enabled", true).put("sections", JSONArray().put("unknown-future-field")))
        assertTrue(empty.sections.isEmpty()); assertFalse(empty.requestsAny)
        assertEquals(empty, ExtraContent.from(JSONObject(empty.json().toString())))
    }

    @Test fun fullReplySchemaContainsOnlyTheSelectedSections() {
        for (section in ExtraSection.entries) {
            val policy = ExtraContent(sections = setOf(section))
            val schema = JSONObject(extraInstructions(policy).substringAfterLast('\n'))
            assertEquals(section == ExtraSection.READINGS, schema.has("source_reading"))
            assertEquals(section == ExtraSection.READINGS, schema.has("target_reading"))
            assertEquals(section == ExtraSection.BACKGROUND, schema.has("derived_info"))
            assertEquals(section == ExtraSection.EVALUATION, schema.has("evaluation"))
            assertEquals(section == ExtraSection.SUGGESTIONS, schema.has("suggestions"))
            val learning = schema.optJSONObject("learning") ?: JSONObject()
            assertEquals(section == ExtraSection.VOCABULARY, learning.has("vocabulary"))
            assertEquals(section == ExtraSection.USAGE, learning.has("usage_notes"))
            assertEquals(section == ExtraSection.CORRECTIONS, learning.has("corrections"))
            assertEquals(section == ExtraSection.CORRECTIONS, learning.has("has_errors"))
            if (section == ExtraSection.VOCABULARY) assertFalse(learning.toString().contains("pronunciation"))
        }
    }

    @Test fun noSelectedExtrasUsesTheTranslationOnlyContractEvenForDirectRequestBodyCalls() {
        for (policy in listOf(ExtraContent(enabled = false), ExtraContent(sections = emptySet()))) {
            val prompt = requestBody(snapshot(policy)).getJSONArray("messages").getJSONObject(0).getString("content")
            assertTrue(prompt.contains("本次仅返回翻译结果"))
            assertFalse(prompt.contains("source_reading")); assertFalse(prompt.contains("usage_notes"))
        }
    }

    @Test fun unrequestedProviderContentIsRemovedBeforeItCanReachHistory() {
        val raw = Translation("译文", info = "背景", evaluation = "评价", suggestions = "建议",
            learning = foreignLearning().copy(corrections = listOf(Correction("He go", "He goes", "主谓一致")), hasErrors = true),
            sourceReading = Reading("en", "IPA"), targetReading = Reading("zh", "拼音"))
        val selected = raw.withExtras(ExtraContent(sections = setOf(ExtraSection.EVALUATION)))
        assertEquals("译文", selected.text); assertEquals("评价", selected.evaluation)
        assertTrue(selected.info.isEmpty()); assertTrue(selected.suggestions.isEmpty())
        assertFalse(selected.learning.hasContent); assertNull(selected.learning.hasErrors)
        assertFalse(selected.sourceReading.hasContent); assertFalse(selected.targetReading.hasContent)
        val persisted = Translation.from(JSONObject(selected.json().toString()))
        assertEquals(selected, persisted)
    }

    @Test fun disablingReadingsAlsoRemovesUnrequestedWordPronunciations() {
        val result = demo(snapshot(ExtraContent(sections = setOf(ExtraSection.VOCABULARY))))
        assertTrue(result.learning.vocabulary.isNotEmpty())
        assertTrue(result.learning.vocabulary.all { word -> word.choices.all { it.pronunciation.isBlank() } })
        assertFalse(result.sourceReading.hasContent); assertFalse(result.targetReading.hasContent)
        assertTrue(result.learning.pronunciation.isBlank()); assertTrue(result.learning.usage.isBlank())
    }

    @Test fun disabledExtrasSendsExactlyOneRequestWithTheFrozenImageContextAndJsonMode() = runBlocking {
        val server = server()
        try {
            server.enqueue(MockResponse().setBody(envelope("{\"translation\":\"只要译文\",\"evaluation\":\"不要显示\"}")))
            val snap = snapshot(ExtraContent(enabled = false), model(server).copy(jsonMode = true)).copy(image = "photo.jpg",
                history = listOf(HistoryContext("h", 1, "原文", "旧译文", "zh-CN")))
            val updates = mutableListOf<TranslationUpdate>()
            TranslationClient().translateParallel(snap, "unit-secret", "data:image/jpeg;base64,cGlj") { updates += it }
            assertEquals(1, server.requestCount)
            assertEquals(listOf("complete"), updates.map { it.analysisState })
            assertEquals("只要译文", updates.single().result.text)
            assertTrue(updates.single().result.evaluation.isBlank())
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("Bearer unit-secret", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8()); val messages = body.getJSONArray("messages")
            assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
            assertTrue(messages.getJSONObject(1).getString("content").contains("旧译文"))
            assertEquals("data:image/jpeg;base64,cGlj", messages.getJSONObject(messages.length() - 1).getJSONArray("content")
                .getJSONObject(1).getJSONObject("image_url").getString("url"))
        } finally { server.shutdown() }
    }

    @Test fun uncheckingEverySectionAlsoSkipsTheSecondRequest() = runBlocking {
        val server = server()
        try {
            server.enqueue(MockResponse().setBody(envelope("只有译文")))
            val updates = mutableListOf<TranslationUpdate>()
            TranslationClient().translateParallel(snapshot(ExtraContent(sections = emptySet()), model(server)), "") { updates += it }
            assertEquals(1, server.requestCount); assertEquals("complete", updates.single().analysisState)
        } finally { server.shutdown() }
    }

    @Test fun translationOnlyFailureDoesNotStartAnUnrequestedFallbackAndRedactsCredentials() = runBlocking {
        val server = server()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad unit-secret\"}}"))
            val error = runCatching { TranslationClient().translateParallel(snapshot(ExtraContent(enabled = false), model(server)), "unit-secret") { fail("no result expected") } }.exceptionOrNull()!!
            assertEquals(1, server.requestCount); assertTrue(error.message!!.contains("401")); assertFalse(error.message!!.contains("unit-secret"))
        } finally { server.shutdown() }
    }

    @Test fun selectedExtrasStillUseParallelRequestsAndDoNotReportMissingDisabledReadings() = runBlocking {
        val server = server()
        try {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val fast = JSONObject(request.body.clone().readUtf8()).getJSONArray("messages").getJSONObject(0).getString("content").contains("本次仅返回翻译结果")
                    return MockResponse().setBody(envelope(if (fast) "速译" else "{\"translation\":\"完整译文\",\"evaluation\":\"自然\",\"suggestions\":\"不应保留\"}"))
                        .setHeadersDelay(if (fast) 0 else 150, TimeUnit.MILLISECONDS)
                }
            }
            val updates = mutableListOf<TranslationUpdate>()
            TranslationClient().translateParallel(snapshot(ExtraContent(sections = setOf(ExtraSection.EVALUATION)), model(server)), "") { updates += it }
            assertEquals(2, server.requestCount)
            val result = updates.last().result
            assertEquals("complete", updates.last().analysisState); assertEquals("自然", result.evaluation)
            assertTrue(result.suggestions.isBlank()); assertFalse(result.warning.contains("注音"))
            repeat(2) {
                val prompt = JSONObject(server.takeRequest(1, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("messages").getJSONObject(0).getString("content")
                if (!prompt.contains("本次仅返回翻译结果")) {
                    assertTrue(prompt.contains("evaluation")); assertFalse(prompt.contains("source_reading")); assertFalse(prompt.contains("suggestions"))
                }
            }
        } finally { server.shutdown() }
    }
}
