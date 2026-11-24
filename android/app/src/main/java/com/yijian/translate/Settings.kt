package com.yijian.translate

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onModel: (ModelConfig?) -> Unit, onExport: () -> Unit) {
    var clearConfirm by remember { mutableStateOf(false) }
    var removeModel by remember { mutableStateOf<ModelConfig?>(null) }
    var editPreset by remember { mutableStateOf(false) }
    var removePreset by remember { mutableStateOf<Preset?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleAction("back", "返回首页", onClick = onBack)
            Text("设置", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 19.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            SectionTitle("翻译模型", "连接你习惯使用的模型")
            GroupCard {
                vm.data.models.forEach { model ->
                    SettingRow("model", model.name, if (model.isDemo) "内置演示 · 无需联网" else model.model, onClick = { if (!model.isDemo) onModel(model) }) {
                        if (model.isDemo) TinyTag("演示") else {
                            IconButton(onClick = { removeModel = model }, modifier = Modifier.size(38.dp)) { Glyph("trash", Modifier.size(17.dp), Ink.Muted, "删除模型${model.name}") }
                            Glyph("right", Modifier.size(15.dp), Ink.Muted)
                        }
                    }
                }
                TextButton(onClick = { onModel(null) }, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Glyph("plus", Modifier.size(17.dp), Ink.Blue); Spacer(Modifier.width(7.dp)); Text("添加翻译模型", fontSize = 13.sp)
                }
            }
            SectionTitle("快捷预设", "左右滑动输入卡片，快速切换")
            GroupCard {
                vm.data.presets.forEachIndexed { index, preset ->
                    SettingRow("context", preset.name, "${languageName(preset.target)} · ${vm.data.models.find { it.id == preset.modelId }?.name ?: "本地演示"}", onClick = { vm.selectPreset(index); editPreset = true }) {
                        if (vm.data.presets.size > 1) IconButton(onClick = { removePreset = preset }, modifier = Modifier.size(38.dp)) { Glyph("trash", Modifier.size(17.dp), Ink.Muted, "删除预设${preset.name}") }
                        Glyph("right", Modifier.size(15.dp), Ink.Muted)
                    }
                }
            }
            SectionTitle("额外内容", "当前预设：${vm.workingPreset.name.ifBlank { "新的预设" }}")
            GroupCard {
                SettingRow("spark", "请求额外内容", vm.workingPreset.extras.summary()) {
                    Switch(vm.workingPreset.extras.enabled, { value -> vm.updatePreset { it.copy(extraContent = it.extras.copy(enabled = value)) } })
                }
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp)) {
                    ExtraContentChoices(vm.workingPreset.extras) { next -> vm.updatePreset { it.copy(extraContent = next) } }
                }
            }
            SectionTitle("外观")
            GroupCard {
                SettingRow("spark", "主题", "浅雾蓝与柔和卡片") { Box(Modifier.size(13.dp).background(Ink.Blue, CircleShape)); Text(" 雾蓝", fontSize = 12.sp, color = Ink.Muted) }
                Text("阅读字号", fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 7.dp, bottom = 13.dp))
                Row(Modifier.padding(horizontal = 14.dp).padding(bottom = 17.dp).fillMaxWidth().background(Ink.Pale, RoundedCornerShape(20.dp)).padding(4.dp)) {
                    listOf(.9f to "小", 1f to "标准", 1.15f to "大", 1.3f to "特大").forEach { (scale, label) ->
                        Surface(onClick = { vm.setFontScale(scale) }, shape = RoundedCornerShape(17.dp), color = if (vm.data.fontScale == scale) Color.White else Color.Transparent, modifier = Modifier.weight(1f)) {
                            Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 12.sp, color = if (vm.data.fontScale == scale) Ink.Blue else Ink.Muted) }
                        }
                    }
                }
            }
            SectionTitle("本地数据")
            GroupCard {
                SettingRow("download", "导出翻译历史", "共 ${vm.data.records.size} 条 · 不包含 API 密钥", onClick = onExport) { Glyph("right", Modifier.size(15.dp), Ink.Muted) }
                SettingRow("trash", "清空翻译历史", "预设和模型配置会保留", onClick = { clearConfirm = true }) { Glyph("right", Modifier.size(15.dp), Ink.Muted) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 29.dp, bottom = 35.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                BrandMark(Modifier.size(24.dp)); Text("${stringResource(R.string.app_name)}  ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = Ink.Muted, modifier = Modifier.padding(start = 7.dp))
            }
        }
    }
    if (clearConfirm) ConfirmDialog("清空翻译历史？", "所有预设的翻译记录将从本机删除，设置会保留。", { clearConfirm = false }) { vm.clearHistory(); clearConfirm = false }
    removeModel?.let { model -> ConfirmDialog("删除 ${model.name}？", "使用它的预设将切换到本地演示。", { removeModel = null }) { vm.deleteModel(model.id); removeModel = null } }
    removePreset?.let { preset -> ConfirmDialog("删除 ${preset.name}？", "该预设的历史、上下文和草稿将一并删除。", { removePreset = null }) { vm.deletePreset(preset.id); removePreset = null } }
    if (editPreset) PresetEditor(vm) { editPreset = false }
}

@Composable fun SectionTitle(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 7.dp, top = 24.dp, bottom = 12.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink.Muted)
        if (subtitle != null) Text(subtitle, fontSize = 10.sp, color = Ink.Muted.copy(alpha = .8f), modifier = Modifier.padding(top = 4.dp))
    }
}
@Composable fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(27.dp), color = Color.White.copy(alpha = .77f), border = BorderStroke(1.dp, Color.White.copy(alpha = .65f))) { Column(content = content) }
}
@Composable fun SettingRow(icon: String, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(Color(0xFFE7EDF9), CircleShape), contentAlignment = Alignment.Center) { Glyph(icon, Modifier.size(19.dp), Ink.Blue.copy(alpha = .8f)) }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
            Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 10.sp, color = Ink.Muted, modifier = Modifier.padding(top = 5.dp), maxLines = 2)
        }
        trailing()
    }
}
@Composable fun ConfirmDialog(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(28.dp), containerColor = Color(0xFFFCFCFF),
        title = { Text(title, fontSize = 19.sp) }, text = { Text(message, color = Ink.Muted) },
        confirmButton = { TextButton(onClick = confirm) { Text("确认删除") } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable fun ModelEditor(vm: AppViewModel, original: ModelConfig?, dismiss: () -> Unit) {
    val initial = remember { original ?: ModelConfig() }
    var name by remember { mutableStateOf(initial.name) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var modelId by remember { mutableStateOf(initial.model) }
    var key by remember { mutableStateOf(runCatching { vm.keyFor(initial.id) }.getOrDefault("")) }
    var showKey by remember { mutableStateOf(false) }
    var vision by remember { mutableStateOf(initial.vision) }
    var json by remember { mutableStateOf(initial.jsonMode) }
    var modern by remember { mutableStateOf(initial.modernTokens) }
    var maxTokens by remember { mutableStateOf(initial.maxTokens.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initial.id) { vm.clearModelTest() }
    fun candidate() = initial.copy(name = name.trim(), endpoint = endpoint.trim(), model = modelId.trim(), vision = vision, jsonMode = json, modernTokens = modern, maxTokens = maxTokens.toIntOrNull()?.coerceIn(256, 16384) ?: 4096)
    Dialog(onDismissRequest = { if (!vm.testingModel) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(18.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * .89f).dp), shape = RoundedCornerShape(30.dp), color = Color(0xFFFCFCFF)) {
            Column(Modifier.padding(22.dp).imePadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (original == null) "添加翻译模型" else "编辑模型", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                        Text("兼容 Chat Completions 接口", fontSize = 10.sp, color = Ink.Muted, modifier = Modifier.padding(top = 6.dp))
                    }
                    IconButton(onClick = dismiss) { Glyph("close", description = "关闭模型设置") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(16.dp))
                    SmallField(name, { name = it.take(40) }, "显示名称，如：我的翻译模型")
                    Spacer(Modifier.height(10.dp))
                    SmallField(endpoint, { endpoint = it }, "接口地址，如：https://服务地址/v1")
                    Spacer(Modifier.height(10.dp))
                    SmallField(modelId, { modelId = it }, "模型 ID（供应商提供）")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(key, { key = it }, label = { Text("API 密钥", fontSize = 12.sp) }, singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(15.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp), modifier = Modifier.fillMaxWidth(),
                        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示", fontSize = 10.sp) } })
                    Text("密钥加密保存在本机。内容只会发送到你配置的接口。", fontSize = 10.sp, lineHeight = 17.sp, color = Ink.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                    CompactToggle("支持图片输入", "按模型实际能力开启", vision) { vision = it }
                    CompactToggle("JSON 模式", "接口支持 response_format 时开启", json) { json = it }
                    CompactToggle("使用新式长度参数", "发送 max_completion_tokens", modern) { modern = it }
                    Spacer(Modifier.height(6.dp))
                    SmallField(maxTokens, { maxTokens = it.filter(Char::isDigit).take(5) }, "最大输出长度 · 256–16384 tokens", numeric = true)
                    if (error != null) Text(error.orEmpty(), color = Color(0xFFAE665D), fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp))
                    vm.modelTestMessage?.let { message -> Text(message, color = if (message.startsWith("连接成功")) Ink.Green else Color(0xFFAE665D), fontSize = 11.sp, modifier = Modifier.padding(top = 9.dp)) }
                    Spacer(Modifier.height(13.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        error = runCatching { endpointUrl(endpoint); require(modelId.isNotBlank()) { "请填写模型 ID" } }.exceptionOrNull()?.message
                        if (error == null) vm.testModel(candidate(), key)
                    }, enabled = !vm.testingModel, shape = RoundedCornerShape(17.dp), modifier = Modifier.weight(1f).height(48.dp)) { Text(if (vm.testingModel) "测试中…" else "测试连接", fontSize = 12.sp) }
                    PrimaryButton("保存模型", Modifier.weight(1f), !vm.testingModel) {
                        error = runCatching { endpointUrl(endpoint); require(name.isNotBlank() && modelId.isNotBlank()) { "请填写名称和模型 ID" } }.exceptionOrNull()?.message
                        if (error == null && vm.saveModel(candidate(), key)) dismiss()
                    }
                }
            }
        }
    }
}

@Composable fun CompactToggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 12.sp); Text(subtitle, fontSize = 10.sp, color = Ink.Muted, modifier = Modifier.padding(top = 3.dp)) }
        Switch(value, onChange, modifier = Modifier.height(33.dp))
    }
}
