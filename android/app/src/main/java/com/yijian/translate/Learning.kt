package com.yijian.translate

import org.json.JSONArray
import org.json.JSONObject

data class WordChoice(val term: String, val pronunciation: String = "", val meaning: String = "", val usage: String = "", val example: String = "", val exampleTranslation: String = "") {
    fun json() = JSONObject().put("term", term).put("pronunciation", pronunciation).put("meaning", meaning).put("usage", usage).put("example", example).put("example_translation", exampleTranslation)
    companion object {
        fun from(j: JSONObject) = WordChoice(j.text("term"), j.text("pronunciation"), j.text("meaning"), j.text("usage"), j.text("example"), j.text("example_translation"))
    }
}
data class Vocabulary(val sourceTerm: String, val explanation: String = "", val choices: List<WordChoice> = emptyList()) {
    fun json() = JSONObject().put("source_term", sourceTerm).put("explanation", explanation).put("choices", JSONArray(choices.map { it.json() }))
    companion object {
        fun from(j: JSONObject) = Vocabulary(j.text("source_term"), j.text("explanation"), j.optJSONArray("choices")?.objects()?.map(WordChoice::from).orEmpty().filter { it.term.isNotBlank() })
    }
}
data class Correction(val original: String, val corrected: String, val explanation: String) {
    fun json() = JSONObject().put("original", original).put("corrected", corrected).put("explanation", explanation)
    companion object { fun from(j: JSONObject) = Correction(j.text("original"), j.text("corrected"), j.text("explanation")) }
}
data class LearningInfo(val sourceLanguage: String = "", val direction: String = "", val pronunciation: String = "", val usage: String = "", val correctionSummary: String = "", val vocabulary: List<Vocabulary> = emptyList(), val corrections: List<Correction> = emptyList(), val hasErrors: Boolean? = null) {
    val showCorrections get() = hasErrors != false && (corrections.any { it.original.isNotBlank() && it.corrected.isNotBlank() && it.original.trim() != it.corrected.trim() } || (hasErrors == true && correctionSummary.isNotBlank() && !Regex("(没有|未发现|不存在|无)\\s*(明显的?|任何)?\\s*(语法错误|拼写错误|错误|问题)|无需(修改|纠错)|no (obvious )?(errors|mistakes)", RegexOption.IGNORE_CASE).containsMatchIn(correctionSummary)))
    val hasContent get() = pronunciation.isNotBlank() || usage.isNotBlank() || vocabulary.isNotEmpty() || corrections.isNotEmpty() || correctionSummary.isNotBlank()
    fun json() = JSONObject().put("source_language", sourceLanguage).put("direction", direction).put("pronunciation", pronunciation).put("usage_notes", usage).put("correction_summary", correctionSummary)
        .put("vocabulary", JSONArray(vocabulary.map { it.json() })).put("corrections", JSONArray(corrections.map { it.json() })).put("has_errors", hasErrors ?: JSONObject.NULL)
    companion object {
        fun from(j: JSONObject) = LearningInfo(j.text("source_language"), j.text("direction"), j.text("pronunciation"), j.text("usage_notes"), j.text("correction_summary"),
            j.optJSONArray("vocabulary")?.objects()?.map(Vocabulary::from).orEmpty().filter { it.sourceTerm.isNotBlank() || it.choices.isNotEmpty() },
            j.optJSONArray("corrections")?.objects()?.map(Correction::from).orEmpty(), when (j.opt("has_errors")) { true, "true" -> true; false, "false" -> false; else -> null })
    }
}
