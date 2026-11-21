package com.yijian.translate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecoveryAndPresetTest {
    private fun envelope(content: Any, finish: String = "stop") = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", finish).put("message", JSONObject().put("content", content)))).toString()
    private fun record(id: String, preset: String, session: String = "legacy", demo: Boolean = false) = Record(id,
        RequestSnapshot(session, "原文$id", null, Preset(id = preset), if (demo) ModelConfig.demo else ModelConfig(id = "real"), emptyList(), 10L), Translation("译文$id"))

    @Test fun phoneScreenshotFailureRecoversTranslationFromTruncatedLearning() {
        val content = """{"recognized_source":"","translation":"テスト","learning":{"source_language":"zh","direction":"zh_to_foreign","pronunciation":"cè shì","usage_notes":"测试可以作为名词"""
        val result = parseResponse(envelope(content, "length"))
        assertEquals("テスト", result.text)
        assertTrue(result.warning.contains("不完整"))
        assertFalse(result.text.startsWith("{"))
    }
    @Test fun malformedLaterFieldDoesNotDiscardCompleteEscapedTranslation() {
        val result = parseResponse(envelope("""{"translation":"He said \"hello\".\nNext line.","learning":nonsense !!!"""))
        assertEquals("He said \"hello\".\nNext line.", result.text)
    }
    @Test fun fencesThinkingAndTrailingExplanationsAreIgnored() {
        val result = parseResponse(envelope("<think>reasoning</think>\n```JSON\n{\"translation\":\"你好\"}\n```\n说明完毕"))
        assertEquals("你好", result.text)
    }
    @Test fun doubleEncodedPayloadIsDecoded() {
        val encoded = JSONObject.quote("{\"translation\":\"こんにちは\"}")
        assertEquals("こんにちは", parseResponse(envelope(encoded)).text)
    }
    @Test fun nestedDataAndCamelCaseFieldsAreAccepted() {
        val result = parseResponse(envelope("""{"data":{"translatedText":"今日は何を食べますか。","sourceReading":{"language":"zh","pinyin":"jīntiān chī shénme"},"targetReading":{"language":"ja","hiragana":"きょうはなにをたべますか。","romanization":"Kyō wa nani o tabemasu ka?"}}}"""))
        assertEquals("きょうはなにをたべますか。", result.targetReading.kana)
        assertTrue(result.targetReading.romaji.contains("Kyō"))
        assertTrue(result.sourceReading.phonetic.contains("jīntiān"))
    }
    @Test fun objectContentAndMixedTextPartsAreSupported() {
        val payload = JSONObject().put("translation", "译文")
        assertEquals("译文", parseResponse(envelope(payload)).text)
        assertEquals("译文", parseResponse(envelope(JSONArray().put("```json").put(JSONObject().put("type", "text").put("text", payload.toString())).put("```"))).text)
    }
    @Test fun nestedTranslationJsonFromLegacyHistoryIsRepairedOnLoad() {
        val old = JSONObject().put("translation", "{\"translation\":\"テスト\",\"learning\":{")
        assertEquals("テスト", Translation.from(old).text)
    }
    @Test fun unreadableStructuredResponseNeverBecomesRawJsonTranslation() {
        assertTrue(runCatching { parseResponse(envelope("{\"learning\":{broken")) }.isFailure)
    }
    @Test fun directPayloadAndChineseTranslationAliasAreSupported() {
        assertEquals("译文", parseResponse("{\"translation\":\"译文\"}").text)
        assertEquals("訳文", parseResponse(envelope("{\"译文\":\"訳文\"}")).text)
    }
    @Test fun readingsPersistWithBothJapaneseForms() {
        val reading = Reading("ja", kana = "きょう", romaji = "kyō")
        val value = Translation("今日", sourceReading = reading, targetReading = reading)
        assertEquals(value, Translation.from(JSONObject(value.json().toString())))
    }
    @Test fun cleanCorrectionSummariesAndExplicitFalseAreHidden() {
        assertFalse(LearningInfo(correctionSummary = "没有明显错误").showCorrections)
        assertFalse(LearningInfo(hasErrors = false, corrections = listOf(Correction("go", "goes", "一致"))).showCorrections)
        assertFalse(LearningInfo(hasErrors = true, correctionSummary = "没有明显错误").showCorrections)
        assertFalse(LearningInfo(corrections = listOf(Correction("go", "go", "无需修改"))).showCorrections)
        assertTrue(LearningInfo(corrections = listOf(Correction("go", "goes", "一致"))).showCorrections)
        assertTrue(LearningInfo(hasErrors = true, correctionSummary = "这个句子没有谓语，需要补全。").showCorrections)
    }
    @Test fun japaneseDemoIncludesReadingsOnBothSidesAndBothTranslationDirections() {
        val ja = demo(RequestSnapshot("s", SAMPLE_JAPANESE_ZH, null, Preset(target = "ja"), ModelConfig.demo, emptyList()))
        assertTrue(ja.sourceReading.phonetic.isNotBlank()); assertTrue(ja.targetReading.kana.isNotBlank()); assertTrue(ja.targetReading.romaji.isNotBlank())
        val zh = demo(RequestSnapshot("s", SAMPLE_JAPANESE, null, Preset(), ModelConfig.demo, emptyList()))
        assertTrue(zh.sourceReading.kana.isNotBlank()); assertTrue(zh.sourceReading.romaji.isNotBlank()); assertTrue(zh.targetReading.phonetic.isNotBlank())
    }
    @Test fun readingContractAppliesEvenWhenLearningIsDisabled() {
        val request = RequestSnapshot("s", "测试", null, Preset(target = "ja", showInfo = false), ModelConfig.demo, emptyList())
        val text = requestBody(request).toString()
        assertTrue(text.contains("source_reading")); assertTrue(text.contains("target_reading")); assertTrue(text.contains("romaji")); assertTrue(text.contains("kana"))
    }
    @Test fun samePresetMergesOldSessionsWhileOtherPresetsStayIsolated() {
        val records = listOf(record("a", "one", "session1"), record("b", "one", "session2"), record("c", "two", "session1"), record("d", "one", demo = true))
        val policy = ContextPolicy(true)
        assertEquals(listOf("a", "b"), selectPresetContext(records, "one", policy, 20).entries.map { it.id })
        assertEquals(listOf("c"), selectPresetContext(records, "two", policy, 20).entries.map { it.id })
    }
    @Test fun oldDeletedPresetHistoryRemainsAccessibleAfterMigration() {
        val existing = Preset(id = "one")
        val json = AppData(presets = listOf(existing), records = listOf(record("old", "deleted"))).json().put("version", 1)
        val restored = AppData.from(json)
        assertEquals(2, restored.presets.size); assertEquals("deleted", restored.presets.last().id)
        assertEquals("old", restored.records.single().id)
    }
    @Test fun eachPresetDraftSurvivesStorageRoundTrip() {
        val data = AppData(drafts = mapOf("a" to PresetDraft("draft a", "/a.jpg"), "b" to PresetDraft("draft b")))
        assertEquals(data.drafts, AppData.from(JSONObject(data.json().toString())).drafts)
    }
}
