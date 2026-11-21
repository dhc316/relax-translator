package com.yijian.translate

import android.app.Application
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import java.io.File

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AppStore(application)
    private val client = TranslationClient()
    var data by mutableStateOf(store.load())
        private set
    var workingPreset by mutableStateOf(data.presets.find { it.id == data.selectedPreset } ?: data.presets.first())
        private set
    var busy by mutableStateOf(false)
        private set
    var importingImage by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(store.recoveryMessage)
        private set
    var lastRequest by mutableStateOf<RequestSnapshot?>(null)
        private set
    var testingModel by mutableStateOf(false)
        private set
    var modelTestMessage by mutableStateOf<String?>(null)
        private set
    var detailOpen by mutableStateOf(false)
        private set
    var detailRecordId by mutableStateOf<String?>(null)
        private set
    var detailRequest by mutableStateOf<RequestSnapshot?>(null)
        private set
    var detailInput by mutableStateOf("")
        private set
    var detailImage by mutableStateOf<String?>(null)
        private set
    var detailEnterFromComposer by mutableStateOf(false)
        private set
    var deckRequest by mutableStateOf(0)
        private set
    val detailRecord get() = data.records.find { it.id == detailRecordId }
    val detailLoading get() = busy && detailRequest?.createdAt == lastRequest?.createdAt
    val detailDirty get() = detailRecord?.let { it.request.source != detailInput.trim() || it.request.image != detailImage } ?: false
    private var translationJob: Job? = null
    private var saveDraftJob: Job? = null
    private var requestGeneration = 0
    private var activeRecordId: String? = null
    private val saveQueue = Channel<Pair<AppData, Set<String>>>(Channel.UNLIMITED)
    val sessionId get() = workingPreset.id
    val currentRecords get() = data.records.filter { it.request.preset.id == workingPreset.id }
    val activeModel get() = data.models.find { it.id == workingPreset.modelId } ?: ModelConfig.demo
    val presetDirty get() = data.presets.find { it.id == workingPreset.id }?.let { it != workingPreset } ?: true

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for ((next, removePaths) in saveQueue) {
                try {
                    store.save(next)
                    if (removePaths.isNotEmpty()) {
                        val protected = withContext(Dispatchers.Main) {
                            attachmentPaths(data) + listOfNotNull(lastRequest?.image.takeIf { busy })
                        }
                        (removePaths - protected).forEach { File(it).delete() }
                    }
                }
                catch (_: Exception) { withContext(Dispatchers.Main) { notice = "本地保存失败，请检查手机剩余空间。" } }
            }
        }
        persist(data)
    }

    private fun persist(next: AppData, clean: Boolean = false) {
        val removed = if (clean) attachmentPaths(data) - attachmentPaths(next) else emptySet()
        data = next
        saveQueue.trySend(next to removed)
    }
    private fun attachmentPaths(value: AppData) = value.records.mapNotNull { it.request.image }.toSet() + listOfNotNull(value.draftImage) + value.drafts.values.mapNotNull { it.image }
    fun updateDraft(text: String) {
        data = data.copy(draft = text, drafts = if (workingPreset.id == "new") data.drafts else data.drafts + (workingPreset.id to PresetDraft(text, data.draftImage)))
        saveDraftJob?.cancel()
        saveDraftJob = viewModelScope.launch { delay(350); persist(data) }
    }
    fun updatePreset(change: (Preset) -> Preset) {
        workingPreset = change(workingPreset)
        if (workingPreset.id != "new") persist(data.copy(presets = data.presets.map { if (it.id == workingPreset.id) workingPreset else it }))
    }
    fun selectPreset(index: Int) {
        val target = data.presets.getOrNull(index)
        if (workingPreset.id == (target?.id ?: "new")) return
        closeDetail()
        val drafts = if (workingPreset.id == "new") data.drafts else data.drafts + (workingPreset.id to PresetDraft(data.draft, data.draftImage))
        workingPreset = target ?: workingPreset.copy(id = "new", name = "", instruction = "")
        val draft = drafts[workingPreset.id] ?: PresetDraft()
        persist(data.copy(selectedPreset = target?.id ?: data.selectedPreset, draft = draft.text, draftImage = draft.image, drafts = drafts))
        lastError = null
    }
    fun beginNewPreset() { selectPreset(data.presets.size); deckRequest++ }
    fun choosePreset(id: String) { data.presets.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(::selectPreset); deckRequest++ }
    fun createPreset() {
        val preset = workingPreset.copy(id = newId(), name = "预设 ${data.presets.size + 1}")
        workingPreset = preset
        persist(data.copy(presets = data.presets + preset, selectedPreset = preset.id, draft = "", draftImage = null))
    }
    fun savePreset(name: String = workingPreset.name, instruction: String = workingPreset.instruction) {
        val saved = workingPreset.copy(id = if (workingPreset.id == "new") newId() else workingPreset.id,
            name = name.trim().take(24).ifBlank { "我的预设" }, instruction = instruction.trim().take(4000))
        val exists = data.presets.any { it.id == saved.id }
        workingPreset = saved
        persist(data.copy(presets = if (exists) data.presets.map { if (it.id == saved.id) saved else it } else data.presets + saved, selectedPreset = saved.id))
    }
    fun deletePreset(id: String) {
        if (data.presets.size <= 1) { notice = "至少保留一个预设"; return }
        if (busy && lastRequest?.preset?.id == id) cancel()
        if (detailRequest?.preset?.id == id) closeDetail()
        val remaining = data.presets.filterNot { it.id == id }
        if (workingPreset.id == id) workingPreset = remaining.first()
        val draft = if (data.selectedPreset == id) data.drafts[workingPreset.id] ?: PresetDraft() else PresetDraft(data.draft, data.draftImage)
        persist(data.copy(presets = remaining, selectedPreset = workingPreset.id, records = data.records.filterNot { it.request.preset.id == id },
            drafts = data.drafts - id, draft = draft.text, draftImage = draft.image), clean = true)
    }
    fun favorites(values: List<String>) { persist(data.copy(favorites = values.distinct())) }
    fun setFontScale(value: Float) { persist(data.copy(fontScale = value)) }
    fun clearNotice() { notice = null }
    fun showNotice(text: String) { notice = text }
    fun clearError() { lastError = null }
    fun editDetail(text: String) { detailInput = text }
    fun closeDetail() {
        detailOpen = false
    }
    fun resumeDetail() {
        if (busy) lastRequest?.let { request ->
            data.presets.indexOfFirst { it.id == request.preset.id }.takeIf { it >= 0 && workingPreset.id != request.preset.id }?.let(::selectPreset)
            detailRequest = request; detailRecordId = activeRecordId?.takeIf { id -> data.records.any { it.id == id } }
            detailInput = request.source; detailImage = request.image
        }
        detailRequest?.let { request ->
            data.presets.indexOfFirst { it.id == request.preset.id }.takeIf { it >= 0 && workingPreset.id != request.preset.id }?.let(::selectPreset)
            detailEnterFromComposer = false; detailOpen = true
        }
    }
    fun openDetail(record: Record) {
        detailRequest = record.request
        detailRecordId = record.id
        detailInput = record.request.source
        detailImage = record.request.image
        detailEnterFromComposer = false
        detailOpen = true
    }
    fun sendDetail() {
        if (busy) return
        val request = detailRequest ?: return
        val preset = data.presets.find { it.id == request.preset.id } ?: request.preset
        val model = data.models.find { it.id == preset.modelId } ?: ModelConfig.demo
        if (detailInput.isBlank() && detailImage == null) return
        if (detailImage != null && !model.vision) { notice = "当前模型未启用图片支持"; return }
        val context = selectPresetContext(data.records.filterNot { it.id == detailRecordId }, preset.id, preset.context, System.currentTimeMillis())
        perform(RequestSnapshot(preset.id, detailInput.trim(), detailImage, preset, model, context.entries, contextTrimmed = context.trimmed))
    }
    fun newSession() {
        closeDetail()
        createPreset()
        lastError = null
    }
    fun selectSession(id: String) { data.presets.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(::selectPreset) }
    fun deleteSession(id: String) {
        if (detailRequest?.sessionId == id) closeDetail()
        if (busy && lastRequest?.sessionId == id) cancel()
        var sessions = data.sessions.filterNot { it.id == id }
        if (sessions.isEmpty()) sessions = listOf(Session())
        persist(data.copy(sessions = sessions, records = data.records.filterNot { it.request.sessionId == id },
            selectedSession = if (sessionId == id) sessions.last().id else sessionId), clean = !busy)
    }
    fun deleteRecord(id: String) { if (busy && activeRecordId == id) cancel(); if (detailRecordId == id) closeDetail(); persist(data.copy(records = data.records.filterNot { it.id == id }), clean = !busy) }
    fun reuseRecord(record: Record) {
        workingPreset = record.request.preset.copy(modelId = if (data.models.any { it.id == record.request.model.id }) record.request.model.id else "demo")
        persist(data.copy(draft = record.request.source, draftImage = record.request.image))
    }
    fun clearHistory() { closeDetail(); if (busy) cancel(); persist(data.copy(records = emptyList()), clean = true); lastError = null }

    fun importImage(uri: Uri) {
        val owner = workingPreset.id
        importingImage = true
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) { store.importImage(uri) }
                if (data.presets.none { it.id == owner }) { File(path).delete(); return@launch }
                val draft = data.drafts[owner] ?: PresetDraft(if (workingPreset.id == owner) data.draft else "")
                persist(data.copy(draftImage = if (workingPreset.id == owner) path else data.draftImage,
                    drafts = data.drafts + (owner to draft.copy(image = path))))
            } catch (error: Exception) { notice = error.message ?: "图片读取失败" }
            finally { importingImage = false }
        }
    }
    fun removeImage() { persist(data.copy(draftImage = null, drafts = data.drafts + (workingPreset.id to PresetDraft(data.draft))), clean = !busy) }

    fun keyFor(id: String): String = store.key(id)
    fun saveModel(model: ModelConfig, key: String): Boolean = try {
        require(model.name.isNotBlank()) { "请填写显示名称" }
        require(model.model.isNotBlank()) { "请填写模型 ID" }
        endpointUrl(model.endpoint)
        store.saveKey(model.id, key.trim())
        val exists = data.models.any { it.id == model.id }
        persist(data.copy(models = if (exists) data.models.map { if (it.id == model.id) model else it } else data.models + model))
        updatePreset { it.copy(modelId = model.id) }
        notice = "模型已保存，可在输入卡片中选择"
        true
    } catch (error: Exception) { notice = error.message ?: "保存失败"; false }

    fun deleteModel(id: String) {
        if (id == "demo") return
        store.removeKey(id)
        if (workingPreset.modelId == id) workingPreset = workingPreset.copy(modelId = "demo")
        persist(data.copy(models = data.models.filterNot { it.id == id }, presets = data.presets.map { if (it.modelId == id) it.copy(modelId = "demo") else it }))
    }

    fun testModel(model: ModelConfig, key: String) {
        if (testingModel) return
        testingModel = true
        modelTestMessage = null
        viewModelScope.launch {
            try {
                endpointUrl(model.endpoint)
                require(model.model.isNotBlank()) { "请填写模型 ID" }
                val snapshot = RequestSnapshot(sessionId, "Hello", null, workingPreset.copy(target = "zh-CN"), model.copy(maxTokens = 1024), emptyList())
                client.translateFast(snapshot, key.trim())
                modelTestMessage = "连接成功，模型已返回翻译结果"
            } catch (error: Exception) { modelTestMessage = error.message ?: "连接测试失败" }
            finally { testingModel = false }
        }
    }
    fun clearModelTest() { modelTestMessage = null }

    fun send() {
        if (busy || importingImage) return
        if (workingPreset.id == "new") { notice = "先保存这张卡片的预设"; return }
        if (data.draft.isBlank() && data.draftImage == null) { notice = "输入文字或添加图片后再翻译"; return }
        if (data.draft.length > 30000) { notice = "单次文字请控制在 30000 字符以内"; return }
        if (data.draftImage != null && !activeModel.vision) { notice = "当前模型未启用图片支持，请选择视觉模型"; return }
        val selected = selectPresetContext(data.records, workingPreset.id, workingPreset.context, System.currentTimeMillis())
        val snapshot = RequestSnapshot(sessionId, data.draft.trim(), data.draftImage, workingPreset.copy(), activeModel.copy(), selected.entries, contextTrimmed = selected.trimmed)
        perform(snapshot)
    }
    fun retry() { if (!busy) lastRequest?.let(::perform) }
    fun cancel() {
        val partial = data.records.find { it.id == activeRecordId && it.analysisState == "pending" }
        requestGeneration++; translationJob?.cancel(); busy = false; activeRecordId = null
        if (partial != null) persist(data.copy(records = data.records.map { if (it.id == partial.id) it.copy(analysisState = "cancelled", analysisError = "已停止补充解析，译文已保留。") else it }))
        notice = if (partial != null) "已停止补充解析，译文已保留" else "已取消，输入内容已保留"
    }
    private fun perform(snapshot: RequestSnapshot) {
        val generation = ++requestGeneration
        val recordId = newId()
        activeRecordId = recordId
        detailEnterFromComposer = !detailOpen
        detailRequest = snapshot
        detailRecordId = null
        detailInput = snapshot.source
        detailImage = snapshot.image
        detailOpen = true
        busy = true
        lastError = null
        lastRequest = snapshot
        translationJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            try {
                val key = if (snapshot.model.isDemo) "" else store.key(snapshot.model.id)
                val imageData = if (snapshot.image != null && !snapshot.model.isDemo) withContext(Dispatchers.IO) {
                    "data:image/jpeg;base64," + Base64.encodeToString(File(snapshot.image).readBytes(), Base64.NO_WRAP)
                } else null
                client.translateParallel(snapshot, key, imageData) { update ->
                    if (generation != requestGeneration || data.presets.none { it.id == snapshot.preset.id }) return@translateParallel
                    val previous = data.records.find { it.id == recordId }
                    val elapsed = System.currentTimeMillis() - start
                    val record = Record(recordId, snapshot, update.result, elapsed, update.analysisState, update.analysisError, previous?.firstResultMs ?: elapsed)
                    val clearDraft = previous == null && workingPreset.id == snapshot.preset.id && data.draft.trim() == snapshot.source && data.draftImage == snapshot.image
                    val ownerDraft = data.drafts[snapshot.preset.id]
                    val drafts = if (previous == null && ownerDraft?.text?.trim() == snapshot.source && ownerDraft.image == snapshot.image) data.drafts + (snapshot.preset.id to PresetDraft()) else data.drafts
                    val records = if (previous == null) data.records + record else data.records.map { if (it.id == recordId) record else it }
                    persist(data.copy(records = records, draft = if (clearDraft) "" else data.draft, draftImage = if (clearDraft) null else data.draftImage, drafts = drafts))
                    if (detailRequest?.createdAt == snapshot.createdAt) detailRecordId = recordId
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (generation == requestGeneration) lastError = error.message ?: "翻译失败，请重试" }
            finally { if (generation == requestGeneration) { busy = false; activeRecordId = null } }
        }
    }
}
