package com.yijian.translate

import org.json.JSONObject

data class PresetDraft(val text: String = "", val image: String? = null) {
    fun json() = JSONObject().put("text", text).put("image", image ?: "")
    companion object { fun from(j: JSONObject) = PresetDraft(j.optString("text"), j.optString("image").ifBlank { null }) }
}

/** Old independent sessions are grouped by the preset recorded on each original request. */
fun restoreHistoricalPresets(presets: List<Preset>, records: List<Record>): List<Preset> = presets + records
    .map { it.request.preset }.filter { old -> old.id != "new" && presets.none { it.id == old.id } }
    .distinctBy { it.id }.map { it.copy(name = "归档 · ${it.name.ifBlank { "旧预设" }}") }

fun selectPresetContext(records: List<Record>, presetId: String, policy: ContextPolicy, now: Long, charBudget: Int = 16000): SelectedContext =
    selectContext(records.filter { it.request.preset.id == presetId }.map { it.copy(request = it.request.copy(sessionId = presetId)) }, presetId, policy, now, charBudget)
