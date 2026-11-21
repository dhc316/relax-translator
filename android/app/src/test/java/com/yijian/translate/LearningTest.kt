package com.yijian.translate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LearningTest {
    private fun response(payload: JSONObject) = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", payload.toString())))).toString()
    @Test fun chineseDemoDistinguishesWordsByContextInsteadOfOnlyGivingSynonyms() {
        val result = demo(RequestSnapshot("s", SAMPLE_CHINESE, null, Preset(target = "en"), ModelConfig.demo, emptyList()))
        assertEquals("This job is a good fit for you.", result.text)
        val choices = result.learning.vocabulary.first().choices
        assertEquals(listOf("a good fit", "suitable", "appropriate"), choices.map { it.term })
        assertTrue(choices.all { it.usage.isNotBlank() && it.example.isNotBlank() && it.exampleTranslation.isNotBlank() })
        assertTrue(choices.last().usage.contains("场合"))
    }
    @Test fun foreignDemoContainsPronunciationUsageAndAnHonestCorrectionConclusion() {
        val result = demo(RequestSnapshot("s", SAMPLE_SOURCE, null, Preset(), ModelConfig.demo, emptyList()))
        assertEquals("foreign_to_zh", result.learning.direction)
        assertTrue(result.learning.pronunciation.contains("məʊmənt"))
        assertTrue(result.learning.vocabulary.any { word -> word.choices.any { it.usage.contains("宾语补足语") } })
        assertTrue(result.learning.correctionSummary.contains("没有明显"))
        assertTrue(result.learning.corrections.isEmpty())
    }
    @Test fun richLearningSurvivesNetworkParsingAndLocalStorageRoundTrip() {
        val expected = chineseLearning()
        val result = parseResponse(response(JSONObject().put("translation", "test").put("learning", expected.json())))
        assertEquals(expected, result.learning)
        assertEquals(result, Translation.from(JSONObject(result.json().toString())))
    }
    @Test fun legacyRecordsWithoutLearningStillLoad() {
        val old = JSONObject().put("translation", "旧译文").put("derived_info", "旧说明")
        assertEquals("旧译文", Translation.from(old).text)
        assertEquals("旧说明", Translation.from(old).info)
        assertFalse(Translation.from(old).learning.hasContent)
    }
    @Test fun malformedOptionalLearningDoesNotDiscardValidTranslation() {
        val malformed = JSONObject().put("vocabulary", JSONArray().put("wrong type"))
        val result = parseResponse(response(JSONObject().put("translation", "有效译文").put("learning", malformed)))
        assertEquals("有效译文", result.text)
        assertTrue(result.warning.contains("词汇解析格式不完整"))
    }
    @Test fun promptRequiresDifferentLearningForEachDirectionAndDoesNotInventErrors() {
        val request = RequestSnapshot("s", "你好", null, Preset(target = "en"), ModelConfig.demo, emptyList())
        val prompt = requestBody(request).getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(prompt.contains("zh_to_foreign")); assertTrue(prompt.contains("foreign_to_zh"))
        assertTrue(prompt.contains("不制造错误")); assertTrue(prompt.contains("example_translation"))
    }
    @Test fun turningLearningOffRemovesDemoLearning() {
        val request = RequestSnapshot("s", SAMPLE_CHINESE, null, Preset(target = "en", showInfo = false), ModelConfig.demo, emptyList())
        assertFalse(demo(request).learning.hasContent)
    }
    @Test fun correctionDetailsRemainStructured() {
        val learning = LearningInfo(sourceLanguage = "en", direction = "foreign_to_zh", correctionSummary = "发现一处主谓一致问题。",
            corrections = listOf(Correction("He go", "He goes", "第三人称单数现在时需要加 s。")))
        val result = parseResponse(response(JSONObject().put("translation", "他去学校。").put("learning", learning.json())))
        assertEquals("He goes", result.learning.corrections.single().corrected)
        assertTrue(result.learning.corrections.single().explanation.contains("第三人称"))
    }
}
