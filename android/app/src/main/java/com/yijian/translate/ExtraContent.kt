package com.yijian.translate

import org.json.JSONArray
import org.json.JSONObject

enum class ExtraSection(val id: String, val title: String, val description: String) {
    READINGS("readings", "注音与读音", "原文与译文注音；日语假名、罗马字"),
    VOCABULARY("vocabulary", "词汇辨析", "候选词义、适用场景和例句"),
    USAGE("usage", "搭配与语法", "句子结构、常见搭配和用法"),
    CORRECTIONS("corrections", "纠错解析", "仅在原文确有错误时显示"),
    BACKGROUND("background", "补充说明", "文化背景和必要的衍生信息"),
    EVALUATION("evaluation", "表达评价", "语气、清晰度、礼貌程度和歧义"),
    SUGGESTIONS("suggestions", "表达建议", "替代表达及适用场景")
}

data class ExtraContent(val enabled: Boolean = true, val sections: Set<ExtraSection> = ExtraSection.entries.toSet()) {
    val requestsAny get() = enabled && sections.isNotEmpty()
    fun includes(section: ExtraSection) = enabled && section in sections
    fun summary() = if (!requestsAny) "仅请求译文" else "已选 ${sections.size} 项 · 随预设自动保存"
    fun json() = JSONObject().put("enabled", enabled)
        .put("sections", JSONArray(ExtraSection.entries.filter { it in sections }.map { it.id }))

    companion object {
        fun from(json: JSONObject): ExtraContent {
            val selected = json.optJSONArray("sections") ?: return ExtraContent(json.optBoolean("enabled", true))
            val ids = (0 until selected.length()).map { selected.optString(it) }.toSet()
            return ExtraContent(json.optBoolean("enabled", true), ExtraSection.entries.filter { it.id in ids }.toSet())
        }
        fun legacy(info: Boolean, evaluation: Boolean, suggestions: Boolean) = ExtraContent(sections = buildSet {
            add(ExtraSection.READINGS)
            if (info) addAll(listOf(ExtraSection.VOCABULARY, ExtraSection.USAGE, ExtraSection.CORRECTIONS, ExtraSection.BACKGROUND))
            if (evaluation) add(ExtraSection.EVALUATION)
            if (suggestions) add(ExtraSection.SUGGESTIONS)
        })
    }
}

/** Providers can ignore a prompt. Never surface or persist unrequested extra sections. */
fun Translation.withExtras(extras: ExtraContent): Translation {
    val readings = extras.includes(ExtraSection.READINGS)
    val corrections = extras.includes(ExtraSection.CORRECTIONS)
    return copy(
        info = if (extras.includes(ExtraSection.BACKGROUND)) info else "",
        evaluation = if (extras.includes(ExtraSection.EVALUATION)) evaluation else "",
        suggestions = if (extras.includes(ExtraSection.SUGGESTIONS)) suggestions else "",
        sourceReading = if (readings) sourceReading else Reading(),
        targetReading = if (readings) targetReading else Reading(),
        learning = learning.copy(
            pronunciation = if (readings) learning.pronunciation else "",
            usage = if (extras.includes(ExtraSection.USAGE)) learning.usage else "",
            vocabulary = if (extras.includes(ExtraSection.VOCABULARY)) learning.vocabulary.map { word ->
                if (readings) word else word.copy(choices = word.choices.map { it.copy(pronunciation = "") })
            } else emptyList(),
            corrections = if (corrections) learning.corrections else emptyList(),
            correctionSummary = if (corrections) learning.correctionSummary else "",
            hasErrors = if (corrections) learning.hasErrors else null
        )
    )
}

fun extraInstructions(extras: ExtraContent): String {
    val schema = JSONObject().put("translation", "完整目标语言译文").put("recognized_source", "图片识别原文；文字输入可留空")
    if (extras.includes(ExtraSection.READINGS)) schema.put("source_reading", JSONObject()).put("target_reading", JSONObject())
    val learning = JSONObject()
    if (listOf(ExtraSection.VOCABULARY, ExtraSection.USAGE, ExtraSection.CORRECTIONS).any(extras::includes)) {
        learning.put("source_language", "实际原文语言代码").put("direction", "zh_to_foreign/foreign_to_zh/other")
        if (extras.includes(ExtraSection.VOCABULARY)) learning.put("vocabulary", JSONArray().put(JSONObject()
            .put("source_term", "原词或中文关键词").put("explanation", "词义辨析与选择理由")
            .put("choices", JSONArray().put(JSONObject().put("term", "对应词或短语")
                .apply { if (extras.includes(ExtraSection.READINGS)) put("pronunciation", "可靠的标准读音") }
                .put("meaning", "含义").put("usage", "适用场景、搭配与词义区别")
                .put("example", "外语例句").put("example_translation", "例句中文释义")))))
        if (extras.includes(ExtraSection.USAGE)) learning.put("usage_notes", "语法结构、搭配和用法")
        if (extras.includes(ExtraSection.CORRECTIONS)) learning.put("has_errors", false).put("correction_summary", "确有错误才填写")
            .put("corrections", JSONArray().put(JSONObject().put("original", "有问题的原片段").put("corrected", "修正片段").put("explanation", "具体原因")))
        schema.put("learning", learning)
    }
    if (extras.includes(ExtraSection.BACKGROUND)) schema.put("derived_info", "必要的词语、文化背景补充，不重复其他模块")
    if (extras.includes(ExtraSection.EVALUATION)) schema.put("evaluation", "分析原文语气、清晰度、礼貌程度和歧义")
    if (extras.includes(ExtraSection.SUGGESTIONS)) schema.put("suggestions", "替代表达、中文解释与适用场景")
    schema.put("uncertainties", "识别或翻译疑点，无则留空")
    return buildString {
        appendLine("本次仅请求这些额外内容：" + ExtraSection.entries.filter(extras::includes).joinToString("、") { it.title } + "。")
        appendLine("未选择的内容不生成，也不以空字段、占位说明或其他字段夹带返回。说明使用简体中文，无依据的补充留空，不给翻译自评分。")
        if (extras.includes(ExtraSection.READINGS)) appendLine(readingInstructions())
        if (learning.length() > 0) appendLine("先判断实际原文语言；中文译外语 direction=zh_to_foreign，外语译中文 direction=foreign_to_zh，其他方向 direction=other。")
        if (extras.includes(ExtraSection.VOCABULARY)) appendLine("中文译外语时选 1–2 个值得辨析的关键词，每词提供 2–3 个对应词，解释适合/不适合的场景、词义区别和本次选择理由；外语译中文时解释原文关键词、语气及适用场景。每项给出有用例句和中文释义，不用相似译句凑数。")
        if (extras.includes(ExtraSection.USAGE)) appendLine("usage_notes 解释原文或译文的语法结构、常见搭配与实际用法。")
        if (extras.includes(ExtraSection.CORRECTIONS)) appendLine("检查原文拼写、语法和不自然表达，不制造错误。确有错误才返回 has_errors=true、correction_summary 及 corrections；没有错误必须返回 has_errors=false、correction_summary=\"\"、corrections=[]，不输出‘没有错误’模块。")
        appendLine("仅返回一个完整 JSON 对象，不使用代码块，不在字符串中再次编码 JSON。优先输出 translation，其次 recognized_source 和所选注音，再输出其余所选内容；字段及结构如下：")
        append(schema.toString())
    }
}
