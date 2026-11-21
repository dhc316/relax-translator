@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.yijian.translate

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

val PresetPosition = SemanticsPropertyKey<Float>("presetPosition")

@Composable fun HomeScreen(vm: AppViewModel, onMenu: () -> Unit, onPickImage: () -> Unit, onAddModel: () -> Unit, onSettings: () -> Unit) {
    var menu by remember { mutableStateOf<String?>(null) }
    var editPreset by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val initial = vm.data.presets.indexOfFirst { it.id == vm.workingPreset.id }.let { if (it < 0) vm.data.presets.size else it }
    val pager = rememberPagerState(initialPage = initial) { vm.data.presets.size + 1 }
    val lists = (vm.data.presets.map { it.id } + "new").map { id -> key(id) { rememberLazyListState() } }
    val position = pager.currentPage + pager.currentPageOffsetFraction
    val nearest = position.roundToInt().coerceIn(lists.indices)
    val list = lists[nearest]
    val revision = remember(vm.data.records) { vm.data.records.hashCode() }
    LaunchedEffect(pager) {
        var moving = false
        snapshotFlow { pager.isScrollInProgress }.collect { scrolling ->
            if (scrolling) moving = true
            else if (moving) {
                moving = false
                withFrameNanos { }
                val page = pager.currentPage
                if (!pager.isScrollInProgress && vm.data.presets.getOrNull(page)?.id != vm.workingPreset.id) vm.selectPreset(page)
            }
        }
    }
    LaunchedEffect(vm.deckRequest, vm.data.presets.size) {
        val desired = vm.data.presets.indexOfFirst { it.id == vm.workingPreset.id }.let { if (it < 0) vm.data.presets.size else it }
        if (pager.currentPage != desired) pager.animateScrollToPage(desired)
    }
    fun presetAt(page: Int) = vm.data.presets.getOrNull(page) ?: vm.workingPreset.copy(id = "new", name = "", instruction = "")
    fun openMenu(name: String) { focus.clearFocus(); keyboard?.hide(); menu = name }
    FrostedPage(list.firstVisibleItemIndex * 100000 + list.firstVisibleItemScrollOffset + (position * 1000).roundToInt(), revision, header = {
        Row(Modifier.fillMaxWidth().heightIn(min = 78.dp).padding(horizontal = 21.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandMark(Modifier.size(39.dp))
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                BrandTitle()
                PageBlend(pager, Modifier.fillMaxWidth().padding(top = 3.dp).heightIn(min = with(LocalDensity.current) { 16.sp.toDp() })) { page, _ ->
                    val preset = presetAt(page)
                    Text(if (preset.id == "new") "创建独立翻译空间" else "${preset.name.ifBlank { "未命名预设" }} · 独立上下文", color = Ink.Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("homeSubtitle"))
                }
            }
            CircleAction("menu", "打开菜单", onClick = onMenu)
        }
    }) { headerHeight ->
        BoxWithConstraints(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            // Photo and non-photo pages share geometry throughout the gesture.
            val hasPhoto = vm.data.draftImage != null || vm.data.drafts.values.any { it.image != null }
            val cardHeight by animateDpAsState(minOf(if (hasPhoto) 262.dp else 226.dp, maxOf(156.dp, maxHeight - headerHeight - 74.dp)), tween(220), label = "composer-height")
            val dockHeight = cardHeight + 84.dp
            val fadeEnd = with(LocalDensity.current) { (maxHeight - dockHeight + 24.dp).toPx() }
            PageBlend(pager, Modifier.fillMaxSize().testTag("historyTransition").graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(listOf(Color.White, Color.Transparent), startY = maxOf(0f, fadeEnd - 64.dp.toPx()), endY = maxOf(1f, fadeEnd)), blendMode = BlendMode.DstIn)
            }) { page, interactive ->
                val preset = presetAt(page)
                val records = vm.data.records.filter { it.request.preset.id == preset.id }.asReversed()
                HomeTimeline(vm, preset, records, lists[page], headerHeight, dockHeight, interactive) { record -> focus.clearFocus(); keyboard?.hide(); vm.openDetail(record) }
            }
            Composer(vm, pager, onPickImage, menu, ::openMenu, { menu = null }, { editPreset = true }, onAddModel, onSettings, cardHeight, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (editPreset) PresetEditor(vm) { editPreset = false }
}

/** The cards and all surrounding content share one continuous gesture position. */
@Composable private fun PageBlend(pager: PagerState, modifier: Modifier = Modifier, content: @Composable BoxScope.(Int, Boolean) -> Unit) {
    val position = pager.currentPage + pager.currentPageOffsetFraction
    val first = floor(position).toInt().coerceIn(0, pager.pageCount - 1)
    val last = (first + 1).coerceAtMost(pager.pageCount - 1)
    Box(modifier) {
        for (page in first..last) {
            val opacity = (1f - abs(position - page) * 2f).coerceIn(0f, 1f)
            if (opacity > .001f) key(page) {
                val interactive = !pager.isScrollInProgress && page == pager.settledPage
                Box(Modifier.fillMaxWidth().graphicsLayer { alpha = opacity; translationX = (page - position) * 20.dp.toPx() }
                    .then(if (interactive) Modifier else Modifier.clearAndSetSemantics { })) { content(page, interactive) }
            }
        }
    }
}

@Composable private fun HomeTimeline(vm: AppViewModel, preset: Preset, records: List<Record>, list: LazyListState, headerHeight: Dp, dockHeight: Dp, interactive: Boolean, open: (Record) -> Unit) {
    if (records.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(top = headerHeight, bottom = dockHeight).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.padding(bottom = 19.dp).size(72.dp).background(Color.White.copy(alpha = .48f), RoundedCornerShape(25.dp)), contentAlignment = Alignment.Center) { BrandMark(Modifier.size(54.dp)) }
            Text("今天，想翻译些什么？", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Text("译出意思，也读懂词语的不同用法。", color = Ink.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 11.dp, bottom = 20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Pill("试译一句英文") { if (interactive) { vm.updatePreset { it.copy(target = "zh-CN") }; vm.updateDraft(SAMPLE_SOURCE) } }
                Pill("试试中文辨析") { if (interactive) { vm.updatePreset { it.copy(target = "en") }; vm.updateDraft(SAMPLE_CHINESE) } }
            }
            TextButton(onClick = { vm.updatePreset { it.copy(target = "ja") }; vm.updateDraft(SAMPLE_JAPANESE_ZH) }, enabled = interactive) { Text("日语注音示例", fontSize = 12.sp) }
            if (preset.modelId == "demo") Text("本地演示 · 配置模型后即可真实翻译", color = Ink.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 15.dp))
            if (vm.busy) TextButton(onClick = vm::resumeDetail, enabled = interactive) { Text("查看进行中的翻译", fontSize = 12.sp) }
            Spacer(Modifier.height(16.dp))
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().testTag("historyList"), state = list, contentPadding = PaddingValues(start = 21.dp, end = 21.dp, top = headerHeight + 17.dp, bottom = dockHeight + 24.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("历史记录", fontSize = 12.sp, color = Ink.Muted, modifier = Modifier.weight(1f))
                if (vm.busy) TextButton(onClick = vm::resumeDetail, enabled = interactive) { Text("翻译进行中", fontSize = 11.sp) }
                else Text("${records.size} 条", fontSize = 11.sp, color = Ink.Muted)
            } }
            items(records, key = { it.id }) { record -> HistorySummary(record, interactive) { open(record) } }
        }
    }
}

@Composable private fun HistorySummary(record: Record, interactive: Boolean, open: () -> Unit) {
    Surface(onClick = open, enabled = interactive, shape = RoundedCornerShape(24.dp), color = Ink.Card, border = BorderStroke(1.dp, Color.White.copy(alpha = .8f)), modifier = Modifier.fillMaxWidth().testTag("history-${record.id}")) {
        Column(Modifier.padding(19.dp)) {
            Text(record.result.recognized.ifBlank { record.request.source.ifBlank { "图片原文" } }, color = Ink.Muted, fontSize = 13.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            HorizontalDivider(color = Ink.Line, modifier = Modifier.padding(vertical = 12.dp))
            Text(record.result.text, color = Ink.Dark, fontSize = 17.sp, lineHeight = 26.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun Composer(vm: AppViewModel, pager: PagerState, onPickImage: () -> Unit, menu: String?, openMenu: (String) -> Unit, closeMenu: () -> Unit, editPreset: () -> Unit, addModel: () -> Unit, settings: () -> Unit, height: Dp, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val bring = remember { BringIntoViewRequester() }
    val ime = WindowInsets.ime.getBottom(LocalDensity.current)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(ime, focused) { if (ime > 0 && focused) { delay(260); bring.bringIntoView() } }
    LaunchedEffect(pager.isScrollInProgress) { if (pager.isScrollInProgress) closeMenu() }
    Column(modifier.padding(bottom = 10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(height + 30.dp).testTag("presetStack").semantics { this[PresetPosition] = pager.currentPage + pager.currentPageOffsetFraction }) {
            val cardWidth = maxWidth * .84f
            HorizontalPager(pager, modifier = Modifier.fillMaxSize().testTag("presetPager"), pageSize = PageSize.Fixed(cardWidth), contentPadding = PaddingValues(horizontal = (maxWidth - cardWidth) / 2), pageSpacing = 12.dp, beyondViewportPageCount = 2, key = { it }) { page ->
                val signedOffset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                val preset = vm.data.presets.getOrNull(page)
                val isNew = preset == null
                val editable = page == pager.settledPage && preset?.id == vm.workingPreset.id
                val draft = if (preset?.id == vm.workingPreset.id) PresetDraft(vm.data.draft, vm.data.draftImage) else vm.data.drafts[preset?.id] ?: PresetDraft()
                val model = vm.data.models.find { it.id == preset?.modelId } ?: ModelConfig.demo
                Surface(Modifier.fillMaxWidth().height(height).testTag("presetCard-$page").zIndex(-page.toFloat()).graphicsLayer {
                    val relative = -signedOffset
                    val offset = if (relative > 0f) size.width * (minOf(relative, 1f) * .085f + maxOf(relative - 1f, 0f) * .045f) else -size.width * (minOf(-relative, 1f) * .965f + maxOf(-relative - 1f, 0f))
                    transformOrigin = TransformOrigin(1f, .5f)
                    translationX = offset + signedOffset * (size.width + 12.dp.toPx())
                    val scale = if (relative > 0f) 1f - minOf(relative, 3f) * .045f else 1f + minOf(-relative, 1f) * .02f
                    scaleX = scale; scaleY = scale
                }.softShadow(29.dp, .20f, 18.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if (page != pager.settledPage) scope.launch { pager.animateScrollToPage(page) }
                }, shape = RoundedCornerShape(29.dp), color = Ink.Card, border = BorderStroke(1.dp, Ink.Blue.copy(alpha = .10f))) {
                    val ownsUI = if (isNew) vm.workingPreset.id == "new" else preset!!.id == vm.workingPreset.id
                    Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 13.dp).then(if (ownsUI) Modifier else Modifier.clearAndSetSemantics { })) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.size(6.dp).background(if (isNew) Ink.Lavender else Ink.Blue, CircleShape))
                            Text(if (isNew) "新的预设" else preset!!.name, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Ink.Muted, modifier = Modifier.padding(start = 7.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!isNew) IconButton(onClick = editPreset, modifier = Modifier.size(29.dp), enabled = editable) { Glyph("edit", Modifier.size(14.dp), Ink.Muted, "编辑当前预设") }
                            Text("${page + 1} / ${vm.data.presets.size + 1}", color = Ink.Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 7.dp))
                        }
                        if (isNew) {
                            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Glyph("spark", Modifier.size(27.dp), Ink.Lavender)
                                Text("给这张卡片一个用途", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
                                Text("独立的历史与上下文，设置自动保存", color = Ink.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 7.dp, bottom = 12.dp))
                                PrimaryButton("设置这个预设", enabled = !pager.isScrollInProgress && page == pager.settledPage) { vm.createPreset(); editPreset() }
                            }
                        } else {
                            BasicTextField(draft.text, { if (editable) vm.updateDraft(it) }, enabled = editable, textStyle = TextStyle(fontSize = 17.sp, color = Ink.Dark, lineHeight = 27.sp), cursorBrush = SolidColor(Ink.Blue),
                                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 11.dp, bottom = 8.dp).testTag(if (preset!!.id == vm.workingPreset.id) "draftInput" else "draftInput-${preset.id}")
                                    .then(if (preset.id == vm.workingPreset.id) Modifier.bringIntoViewRequester(bring) else Modifier).onFocusChanged {
                                        if (editable) { focused = it.isFocused; if (it.isFocused) scope.launch { delay(260); bring.bringIntoView() } }
                                    }, decorationBox = { field -> Box { if (draft.text.isEmpty()) Text("输入文字，或添加一张图片…", fontSize = 17.sp, color = Color(0xFFAFB3C4), lineHeight = 27.sp); field() } })
                            draft.image?.let { image -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 7.dp)) {
                                LocalPhoto(image, Modifier.size(43.dp).clip(RoundedCornerShape(10.dp)))
                                Text("图片已添加", fontSize = 11.sp, color = Ink.Muted, modifier = Modifier.padding(start = 9.dp).weight(1f))
                                IconButton(onClick = vm::removeImage, enabled = editable, modifier = Modifier.size(32.dp)) { Glyph("close", Modifier.size(15.dp), Ink.Muted, "移除图片") }
                            } }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Surface(onClick = onPickImage, shape = RoundedCornerShape(15.dp), color = Ink.Pale, enabled = editable && !vm.importingImage) {
                                    Row(Modifier.padding(horizontal = 8.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (editable && vm.importingImage) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp) else Glyph("image", Modifier.size(16.dp), Ink.Blue)
                                        Text("附带图片", color = Ink.Blue, fontSize = 10.sp, modifier = Modifier.padding(start = 5.dp))
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    Surface(onClick = { openMenu("model") }, shape = RoundedCornerShape(15.dp), color = Color(0xFFF3F3FA), enabled = editable) {
                                        Row(Modifier.padding(horizontal = 8.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Glyph("model", Modifier.size(15.dp), Ink.Muted)
                                            Text(model.name, color = Ink.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 5.dp))
                                            Glyph("up", Modifier.size(12.dp), Ink.Muted)
                                        }
                                    }
                                    if (editable && menu == "model") UpMenu(closeMenu) { ModelMenu(vm, closeMenu, addModel, settings) }
                                }
                                val pendingHere = vm.busy && vm.lastRequest?.preset?.id == preset!!.id
                                val ready = draft.text.isNotBlank() || draft.image != null
                                Surface(onClick = { if (pendingHere) vm.cancel() else vm.send() }, shape = CircleShape, color = if (pendingHere || ready) Ink.Blue else Color(0xFFE9ECF5), modifier = Modifier.size(43.dp), enabled = editable && (pendingHere || (!vm.busy && !vm.importingImage && ready))) {
                                    Box(contentAlignment = Alignment.Center) { Glyph(if (pendingHere) "stop" else "send", Modifier.size(22.dp), if (pendingHere || ready) Color.White else Color(0xFFB7BDCF), if (editable) (if (pendingHere) "取消翻译" else "开始翻译") else null) }
                                }
                            }
                        }
                    }
                    if (!ownsUI) Box(Modifier.matchParentSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        scope.launch { pager.animateScrollToPage(page) }
                    })
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PageBlend(pager, Modifier.fillMaxWidth()) { page, interactive ->
                    val preset = vm.data.presets.getOrNull(page) ?: vm.workingPreset
                    BottomSelector("globe", languageName(preset.target), false, interactive) { openMenu("language") }
                }
                if (menu == "language") UpMenu(closeMenu) { LanguageMenu(vm, closeMenu) }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                PageBlend(pager, Modifier.fillMaxWidth()) { page, interactive ->
                    val preset = vm.data.presets.getOrNull(page) ?: vm.workingPreset
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { BottomSelector("context", preset.context.label(), preset.context.enabled, interactive) { openMenu("context") } }
                }
                if (menu == "context") UpMenu(closeMenu) { ContextMenu(vm) }
            }
        }
    }
}

@Composable private fun BottomSelector(icon: String, text: String, active: Boolean, interactive: Boolean, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(15.dp)).clickable(enabled = interactive, onClick = onClick).padding(horizontal = 9.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, Modifier.size(16.dp), if (active) Ink.Blue else Ink.Muted)
        Text(text, fontSize = 11.sp, color = if (active) Ink.Blue else Ink.Muted, modifier = Modifier.padding(start = 7.dp, end = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Glyph("up", Modifier.size(11.dp), if (active) Ink.Blue else Ink.Muted)
    }
}

@Composable fun LocalPhoto(path: String, modifier: Modifier) {
    val image by produceState<android.graphics.Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 3 }) }.getOrNull() } }
    val bitmap = image
    if (bitmap != null) Image(bitmap.asImageBitmap(), "已选择的图片", modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(Ink.Pale), contentAlignment = Alignment.Center) { Glyph("image", tint = Ink.Muted) }
}

@Composable fun PresetEditor(vm: AppViewModel, dismiss: () -> Unit) {
    var deleting by remember { mutableStateOf(false) }
    val owner = remember { vm.workingPreset.id }
    AlertDialog(onDismissRequest = dismiss, shape = RoundedCornerShape(28.dp), containerColor = Ink.Card, title = { Text("预设设置", fontSize = 20.sp) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("设置自动保存，每张卡片保留独立上下文。", color = Ink.Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 13.dp))
            SmallField(vm.workingPreset.name, { value -> vm.updatePreset { it.copy(name = value.take(24)) } }, "预设名称，如：工作邮件")
            Spacer(Modifier.height(12.dp))
            SmallField(vm.workingPreset.instruction, { value -> vm.updatePreset { it.copy(instruction = value.take(4000)) } }, "补充翻译要求", singleLine = false)
            Text("${languageName(vm.workingPreset.target)} · ${vm.activeModel.name}\n${vm.workingPreset.context.label()}", fontSize = 11.sp, lineHeight = 20.sp, color = Ink.Muted, modifier = Modifier.padding(top = 13.dp))
            if (vm.data.presets.size == 1) Text("至少保留一个预设。", color = Ink.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 12.dp))
        } }, confirmButton = { TextButton(onClick = dismiss) { Text("完成") } },
        dismissButton = { TextButton(onClick = { deleting = true }, enabled = vm.data.presets.size > 1) { Text("删除预设", color = if (vm.data.presets.size > 1) Color(0xFFAD6C67) else Ink.Muted) } })
    if (deleting) ConfirmDialog("删除这个预设？", "它的历史、上下文和草稿将从本机删除。", { deleting = false }) { vm.deletePreset(owner); deleting = false; dismiss() }
}
