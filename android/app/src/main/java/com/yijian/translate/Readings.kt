package com.yijian.translate

import org.json.JSONObject

data class Reading(val language: String = "", val phonetic: String = "", val kana: String = "", val romaji: String = "", val notes: String = "") {
    val hasContent get() = phonetic.isNotBlank() || kana.isNotBlank() || romaji.isNotBlank()
    fun json() = JSONObject().put("language", language).put("phonetic", phonetic).put("kana", kana).put("romaji", romaji).put("notes", notes)
    companion object {
        fun from(value: Any?, fallback: String = ""): Reading {
            val obj = value.asObject()
            return if (obj != null) Reading(obj.text("language", "language_code").ifBlank { fallback },
                obj.text("phonetic", "ipa", "pinyin", "pronunciation", "reading", "annotation", "注音"),
                obj.text("kana", "hiragana", "furigana", "假名"), obj.text("romaji", "romanization", "romanisaton", "罗马字"), obj.text("notes", "说明"))
            else Reading(fallback, displayValue(value))
        }
    }
}

fun readingInstructions() = """
    无论是否开启词汇解析，都必须为原文和译文分别提供注音与读音：source_reading 和 target_reading。
    每个对象为 {"language":"实际语言代码","phonetic":"标准注音；中文用带声调拼音，英语用 IPA 并标明英/美","kana":"日语全文的假名读音，非日语留空","romaji":"日语全文对应的罗马字读音，非日语留空","notes":"必要的发音提示，无则留空"}。
    日语必须同时填写 kana 和 romaji，含汉字的文字须转为对应假名，不能用汉字原文代替假名。原文或译文含日语时均适用。
    两份读音必须分别对应实际原文与最终译文，不得只给原文，不把整段解释放进读音字段。不确定的专名读音在 notes 说明，不编造确定读音。
""".trimIndent()
