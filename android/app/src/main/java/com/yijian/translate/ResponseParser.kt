package com.yijian.translate

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal fun displayValue(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is String -> value.trim()
    is JSONArray -> (0 until value.length()).map { displayValue(value.opt(it)) }.filter(String::isNotBlank).joinToString("\n")
    is JSONObject -> value.keys().asSequence().map { key -> "$key：${displayValue(value.opt(key))}" }.joinToString("\n")
    else -> value.toString()
}

internal fun JSONObject.alias(vararg names: String): Any? {
    fun normalized(value: String) = value.lowercase().filter(Char::isLetterOrDigit)
    val keys = keys().asSequence().toList()
    return names.firstNotNullOfOrNull { name -> keys.firstOrNull { normalized(it) == normalized(name) }?.let { opt(it).takeUnless { v -> v == JSONObject.NULL } } }
}
internal fun JSONObject.text(vararg names: String) = displayValue(alias(*names))
internal fun Any?.asObject(): JSONObject? = when (this) {
    is JSONObject -> this
    is String -> runCatching { JSONObject(this) }.getOrNull()
    else -> null
}

private fun contentText(value: Any?): String = when (value) {
    is JSONObject -> value.toString()
    is JSONArray -> (0 until value.length()).mapNotNull { i ->
        val part = value.opt(i)
        if (part is String) part else (part as? JSONObject)?.alias("text", "content")?.let(::contentText)
    }.joinToString("\n")
    is String -> value.trim()
    else -> ""
}

fun parseResponse(body: String): Translation {
    val root = runCatching { JSONObject(body) }.getOrElse {
        throw IllegalStateException("接口没有返回有效 JSON，请检查地址是否为兼容的聊天接口")
    }
    // Accept direct structured payloads as well as compatible chat completion envelopes.
    if (root.alias("translation", "translated_text", "译文") != null) return parseModelContent(root.toString())
    val choice = root.optJSONArray("choices")?.optJSONObject(0)
        ?: throw IllegalStateException(root.optJSONObject("error")?.text("message")?.take(240) ?: "响应缺少 choices，请检查接口协议")
    val message = choice.optJSONObject("message") ?: throw IllegalStateException("响应缺少 message")
    val content = contentText(message.opt("content"))
    if (content.isBlank()) throw IllegalStateException(message.text("refusal").ifBlank { "模型没有返回正文；可提高输出长度后重试" })
    val result = parseModelContent(content)
    return if (choice.optString("finish_reason") == "length") result.copy(warning =
        listOf("模型达到输出长度限制，已保留可恢复的译文，解析可能不完整。", result.warning).filter(String::isNotBlank).joinToString("\n")) else result
}

/** Repair only JSON framing, never change words or ask another model to invent missing text. */
private fun closeTruncatedJson(text: String): String {
    val stack = mutableListOf<Char>()
    var quoted = false; var escaped = false
    val out = StringBuilder()
    for (c in text) {
        out.append(c)
        if (quoted) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
        } else when (c) {
            '"' -> quoted = true
            '{' -> stack += '}'
            '[' -> stack += ']'
            '}', ']' -> if (stack.lastOrNull() == c) {
                stack.removeAt(stack.lastIndex)
                if (stack.isEmpty()) return out.toString()
            }
        }
    }
    if (quoted) { if (escaped) out.setLength(out.length - 1); out.append('"') }
    while (out.isNotEmpty() && out.last().isWhitespace()) out.setLength(out.length - 1)
    if (out.lastOrNull() == ':' ) out.append("null")
    if (out.lastOrNull() == ',') out.setLength(out.length - 1)
    stack.asReversed().forEach(out::append)
    return out.toString()
}

private fun structured(text: String): Pair<JSONObject, Boolean>? {
    val cleaned = text.trim().removePrefix("\uFEFF").replace(Regex("(?is)<think>.*?</think>"), "").trim()
    val decoded = runCatching { JSONTokener(cleaned).nextValue() }.getOrNull()
    if (decoded is JSONObject) return decoded to false
    if (decoded is String && decoded != cleaned && decoded.contains('{')) structured(decoded)?.let { return it }
    val start = cleaned.indexOf('{')
    if (start < 0) return null
    val fragment = cleaned.substring(start)
    runCatching { JSONObject(fragment) }.getOrNull()?.let { return it to false }
    val repaired = closeTruncatedJson(fragment)
    return runCatching { JSONObject(repaired) }.getOrNull()?.let { it to true }
}

private fun unwrap(value: JSONObject): JSONObject {
    if (value.alias("translation", "translated_text", "translatedText", "译文", "翻译结果") != null) return value
    for (name in listOf("result", "data", "output", "response")) value.alias(name).asObject()?.let {
        val nested = unwrap(it)
        if (nested.alias("translation", "translated_text", "译文", "翻译结果") != null) return nested
    }
    return value
}

fun parseModelContent(content: String): Translation {
    val pair = structured(content)
    if (pair == null) {
        // Complete translation strings survive even when later fields contain malformed JSON.
        val recovered = Regex("\"(?:translation|translated_text|translatedText|译文)\"\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")")
            .find(content)?.groupValues?.get(1)?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
        if (!recovered.isNullOrBlank()) return Translation(recovered, warning = "回复格式不完整，已恢复译文；其他解析未能读取。")
        if (content.trim().startsWith("{") || content.contains("\"translation\"")) throw IllegalStateException("模型回复结构不完整，未能读取译文。请提高最大输出长度后重试。")
        val plain = content.trim().replace(Regex("^```(?:\\w+)?\\s*|\\s*```$"), "").trim()
        return Translation(plain, warning = "模型返回了纯文本译文，未提供结构化解析。")
    }
    val json = unwrap(pair.first)
    val raw = json.alias("translation", "translated_text", "译文", "翻译结果")
    val translated = if (raw is JSONObject) raw.text("text", "translation", "content", "译文") else displayValue(raw)
    if (translated.isBlank()) throw IllegalStateException("模型回复缺少翻译结果，请重试或调整模型")
    if (translated.trim().startsWith("{") && translated.contains("\"translation\"")) return parseModelContent(translated)
    val learningJson = json.alias("learning", "learning_info", "语言解析").asObject() ?: JSONObject()
    val learning = runCatching { LearningInfo.from(learningJson) }
    val sourceReading = Reading.from(json.alias("source_reading", "source_pronunciation", "原文读音")
        ?: learningJson.alias("source_reading", "source_pronunciation") ?: learningJson.alias("pronunciation"), learning.getOrNull()?.sourceLanguage.orEmpty())
    val targetReading = Reading.from(json.alias("target_reading", "translation_reading", "target_pronunciation", "译文读音")
        ?: learningJson.alias("target_reading", "target_pronunciation"))
    return Translation(translated, json.text("recognized_source", "source_text", "原文"), json.text("derived_info", "衍生信息"),
        json.text("evaluation", "评价"), json.text("suggestions", "建议"),
        listOf(if (pair.second) "回复结构不完整，已恢复译文；部分解析可能缺失。" else "", json.text("uncertainties", "warning"),
            if (learning.isFailure) "词汇解析格式不完整，已保留译文。" else "").filter(String::isNotBlank).joinToString("\n"),
        learning.getOrDefault(LearningInfo()), sourceReading, targetReading)
}
