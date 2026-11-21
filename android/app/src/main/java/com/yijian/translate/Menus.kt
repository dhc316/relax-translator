package com.yijian.translate

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

private class AboveAnchor(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left.coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin))
        val y = (anchorBounds.top - popupContentSize.height - margin).coerceAtLeast(margin * 2)
        return IntOffset(x, y)
    }
}

@Composable fun UpMenu(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val width = minOf(380, configuration.screenWidthDp - 32).dp
    val provider = remember(density) { AboveAnchor(with(density) { 10.dp.roundToPx() }) }
    var entered by remember { mutableStateOf(false) }
    val progress by animateFloatAsState(if (entered) 1f else 0f, tween(170), label = "menu")
    LaunchedEffect(Unit) { entered = true }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFFFCFCFF),
            border = BorderStroke(1.dp, Color.White), modifier = Modifier.padding(11.dp).width(width - 22.dp).softShadow(28.dp, .14f)
                .heightIn(max = minOf(500, (configuration.screenHeightDp * .65f).toInt()).dp)
                .graphicsLayer { alpha = progress; scaleX = .96f + .04f * progress; scaleY = .96f + .04f * progress; transformOrigin = TransformOrigin(.5f, 1f) }) {
            Column(Modifier.padding(20.dp), content = content)
        }
    }
}

@Composable fun MenuHeading(title: String, subtitle: String? = null, trailing: @Composable (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = Ink.Muted, modifier = Modifier.padding(top = 4.dp))
        }
        trailing?.invoke()
    }
    Spacer(Modifier.height(15.dp))
}

@Composable fun SmallField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, numeric: Boolean = false, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, modifier = modifier.fillMaxWidth(), label = { Text(label, fontSize = 12.sp) },
        singleLine = singleLine, maxLines = if (singleLine) 1 else 4, shape = RoundedCornerShape(15.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = Ink.Dark),
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Ink.Line, focusedBorderColor = Ink.Blue))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun LanguageMenu(vm: AppViewModel, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    MenuHeading("翻译成", "选择目标语言", trailing = {
        TextButton(onClick = { editing = !editing }) { Text(if (editing) "完成" else "编辑常用", fontSize = 12.sp) }
    })
    SmallField(search, { search = it }, "搜索语言 / Search")
    Spacer(Modifier.height(14.dp))
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("常用语言", fontSize = 11.sp, color = Ink.Muted, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        if (editing) {
            vm.data.favorites.forEachIndexed { index, code ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(languageName(code), Modifier.weight(1f), fontSize = 13.sp)
                    IconButton(onClick = {
                        if (index > 0) { val list = vm.data.favorites.toMutableList(); list[index] = list[index - 1]; list[index - 1] = code; vm.favorites(list) }
                    }, enabled = index > 0, modifier = Modifier.size(40.dp)) { Glyph("up", Modifier.size(17.dp), if (index > 0) Ink.Blue else Ink.Line, "前移${languageName(code)}") }
                    IconButton(onClick = { vm.favorites(vm.data.favorites - code) }, modifier = Modifier.size(40.dp)) { Glyph("close", Modifier.size(16.dp), Ink.Muted, "移除常用${languageName(code)}") }
                }
            }
            if (vm.data.favorites.isEmpty()) Text("点击下方语言，添加到常用", color = Ink.Muted, fontSize = 12.sp)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                vm.data.favorites.forEach { code -> Pill(languageName(code), vm.workingPreset.target == code) { vm.updatePreset { it.copy(target = code) }; onDismiss() } }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Ink.Line)
        Text(if (editing) "点击添加或移除常用语言" else "全部语言", fontSize = 11.sp, color = Ink.Muted)
        Spacer(Modifier.height(5.dp))
        languages.filter { search.isBlank() || it.name.contains(search.trim(), true) || it.native.contains(search.trim(), true) || it.code.contains(search.trim(), true) }.forEach { language ->
            val selected = if (editing) language.code in vm.data.favorites else language.code == vm.workingPreset.target
            Row(Modifier.fillMaxWidth().clickable {
                if (editing) vm.favorites(if (selected) vm.data.favorites - language.code else vm.data.favorites + language.code)
                else { vm.updatePreset { it.copy(target = language.code) }; onDismiss() }
            }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(language.name, fontSize = 14.sp, color = if (selected) Ink.Blue else Ink.Dark)
                    Text(language.native, fontSize = 11.sp, color = Ink.Muted, modifier = Modifier.padding(top = 2.dp))
                }
                if (selected) Glyph("check", Modifier.size(19.dp), Ink.Blue)
                else if (editing) Glyph("plus", Modifier.size(19.dp), Ink.Muted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ContextMenu(vm: AppViewModel) {
    val policy = vm.workingPreset.context
    var countText by remember { mutableStateOf(policy.count.toString()) }
    var minutesText by remember { mutableStateOf(policy.minutes.toString()) }
    fun change(transform: (ContextPolicy) -> ContextPolicy) { vm.updatePreset { it.copy(context = transform(it.context)) } }
    MenuHeading("附带上下文", "只附带当前预设的独立上下文", trailing = {
        Switch(policy.enabled, { checked -> change { it.copy(enabled = checked) } }, modifier = Modifier.height(32.dp))
    })
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf("count" to "按条数", "time" to "按时间", "both" to "同时限制").forEach { (key, label) ->
                Pill(label, policy.mode == key) { change { it.copy(mode = key, enabled = true) } }
            }
        }
        if (policy.mode != "time") {
            Spacer(Modifier.height(18.dp))
            Text("最近多少条", fontSize = 12.sp, color = Ink.Muted)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(3, 5, 10, 20).forEach { count -> Pill("$count 条", policy.count == count) { countText = count.toString(); change { it.copy(count = count, enabled = true) } } }
            }
            Spacer(Modifier.height(9.dp))
            SmallField(countText, { value -> countText = value.filter(Char::isDigit).take(3); countText.toIntOrNull()?.takeIf { it in 1..100 }?.let { n -> change { it.copy(count = n) } } }, "自定义条数 · 1–100", numeric = true)
        }
        if (policy.mode != "count") {
            Spacer(Modifier.height(18.dp))
            Text("回溯多长时间", fontSize = 12.sp, color = Ink.Muted)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf(10, 30, 60, 1440).forEach { minutes -> Pill(if (minutes == 1440) "24 小时" else "$minutes 分钟", policy.minutes == minutes) { minutesText = minutes.toString(); change { it.copy(minutes = minutes, enabled = true) } } }
            }
            Spacer(Modifier.height(9.dp))
            SmallField(minutesText, { value -> minutesText = value.filter(Char::isDigit).take(5); minutesText.toIntOrNull()?.takeIf { it in 1..10080 }?.let { n -> change { it.copy(minutes = n) } } }, "自定义分钟 · 最多 7 天", numeric = true)
        }
        Spacer(Modifier.height(15.dp))
        val selected = selectPresetContext(vm.data.records, vm.workingPreset.id, policy, System.currentTimeMillis())
        Surface(color = Ink.Pale, shape = RoundedCornerShape(14.dp)) {
            Text(if (policy.enabled) "当前将附带 ${selected.entries.size} 条真实翻译记录。\n仅限当前会话；历史图片使用识别文字。" else "关闭后只发送本次输入。\n历史记录仍保留在手机上。", fontSize = 11.sp, lineHeight = 18.sp, color = Ink.Muted, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable fun ModelMenu(vm: AppViewModel, onDismiss: () -> Unit, onAdd: () -> Unit, onManage: () -> Unit) {
    MenuHeading("选择模型", "为当前预设选择翻译模型")
    Column(Modifier.verticalScroll(rememberScrollState())) {
        vm.data.models.forEach { model ->
            val selected = model.id == vm.activeModel.id
            Row(Modifier.fillMaxWidth().clickable { vm.updatePreset { it.copy(modelId = model.id) }; onDismiss() }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(39.dp).background(if (selected) Ink.Pale else Color(0xFFF4F5FA), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Glyph("model", tint = if (selected) Ink.Blue else Ink.Muted) }
                Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                    Text(model.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) Ink.Blue else Ink.Dark)
                    Text(if (model.isDemo) "本地体验 · 不上传内容" else model.model, fontSize = 11.sp, color = Ink.Muted, maxLines = 1)
                }
                if (!model.isDemo) TinyTag(if (model.vision) "图片" else "文字")
                if (selected) Glyph("check", Modifier.padding(start = 8.dp).size(18.dp), Ink.Blue)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Ink.Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onDismiss(); onAdd() }) { Glyph("plus", Modifier.size(17.dp), Ink.Blue); Spacer(Modifier.width(5.dp)); Text("添加模型", fontSize = 13.sp) }
            TextButton(onClick = { onDismiss(); onManage() }) { Text("管理模型", fontSize = 13.sp) }
        }
    }
}
