package com.yijian.translate

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable fun DetailScreen(vm: AppViewModel, onSettings: () -> Unit) {
    val record = vm.detailRecord
    val request = vm.detailRequest ?: return
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    val speech = rememberSpeech(vm::showNotice)
    val clipboard = LocalClipboardManager.current
    val bring = remember { BringIntoViewRequester() }
    var sourceFocused by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var entered by remember(request.createdAt) { mutableStateOf(!vm.detailEnterFromComposer) }
    val arrival by animateFloatAsState(if (entered) 1f else 0f, spring(dampingRatio = .88f, stiffness = 165f), label = "input-card-rise")
    val riseDistance = with(density) { (LocalConfiguration.current.screenHeightDp.dp * .48f).toPx() }
    LaunchedEffect(request.createdAt) { entered = true; list.scrollToItem(0) }
    LaunchedEffect(ime, sourceFocused) {
        if (ime > 0 && sourceFocused) { delay(80); list.scrollToItem(0); bring.bringIntoView() }
    }
    FrostedPage(list.firstVisibleItemIndex * 100000 + list.firstVisibleItemScrollOffset, (record?.hashCode() ?: 0) + vm.detailInput.hashCode(), header = {
        Row(Modifier.fillMaxWidth().height(78.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleAction("back", "返回历史") { focus.clearFocus(); keyboard?.hide(); vm.closeDetail() }
            Column(Modifier.weight(1f).padding(start = 15.dp)) {
                Text("翻译详情", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("${request.model.name} · ${languageName(request.preset.target)}", color = Ink.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
            CircleAction("settings", "详情设置", onClick = onSettings)
        }
    }) { headerHeight ->
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().imePadding().testTag("detailScroll"), state = list,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = headerHeight + 15.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item(key = "translation-card") {
                Surface(Modifier.fillMaxWidth().graphicsLayer {
                    translationY = riseDistance * (1f - arrival); scaleX = .97f + .03f * arrival; scaleY = .97f + .03f * arrival; alpha = .3f + .7f * arrival
                }.testTag("translationCard"), shape = RoundedCornerShape(28.dp), color = Ink.Card, border = BorderStroke(1.dp, Color.White)) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("原文", fontSize = 11.sp, color = Ink.Muted, modifier = Modifier.weight(1f))
                            TinyTag(record?.result?.learning?.sourceLanguage?.ifBlank { "自动识别" } ?: "自动识别", Ink.Lavender)
                            request.image?.let { LocalPhoto(it, Modifier.padding(start = 10.dp).size(38.dp)) }
                        }
                        BasicTextField(vm.detailInput, vm::editDetail, textStyle = TextStyle(color = Ink.Dark, fontSize = 17.sp, lineHeight = 27.sp),
                            cursorBrush = SolidColor(Ink.Blue), maxLines = 6,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp).heightIn(min = 58.dp, max = 164.dp)
                                .bringIntoViewRequester(bring).testTag("detailInput").onFocusChanged { state ->
                                    sourceFocused = state.isFocused
                                    if (state.isFocused) scope.launch { delay(250); list.animateScrollToItem(0); bring.bringIntoView() }
                                }, decorationBox = { field -> Box { if (vm.detailInput.isBlank()) Text(if (request.image != null) "可补充图片翻译范围…" else "在这里修改原文…", color = Ink.Muted, fontSize = 16.sp); field() } })
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                            if (ime > 0) TextButton(onClick = { focus.clearFocus(); keyboard?.hide() }) { Text("收起键盘", fontSize = 11.sp) }
                            TextButton(onClick = { focus.clearFocus(); keyboard?.hide(); if (vm.detailLoading) vm.cancel() else vm.sendDetail() }, enabled = !vm.busy || vm.detailLoading) {
                                Glyph(if (vm.detailLoading) "stop" else "reload", Modifier.size(14.dp), Ink.Blue)
                                Text(if (vm.detailLoading) "取消" else "重新翻译", fontSize = 11.sp, modifier = Modifier.padding(start = 5.dp))
                            }
                        }
                        HorizontalDivider(color = Ink.Line, modifier = Modifier.padding(top = 2.dp, bottom = 16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Glyph("globe", Modifier.size(16.dp), Ink.Blue)
                            Text("译文 · ${languageName(request.preset.target)}", color = Ink.Blue, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 7.dp).weight(1f))
                            if (request.model.isDemo) TinyTag("演示")
                            if (record != null && !vm.detailDirty) IconButton(onClick = { clipboard.setText(AnnotatedString(record.result.text)); vm.showNotice("译文已复制") }, modifier = Modifier.size(31.dp)) { Glyph("copy", Modifier.size(16.dp), Ink.Muted, "复制译文") }
                        }
                        when {
                            vm.detailDirty -> Text("原文已修改，重新翻译后显示结果。", color = Ink.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 22.dp))
                            record != null -> SelectionContainer { Text(record.result.text, color = Ink.Dark, fontSize = 21.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 13.dp, bottom = 4.dp).testTag("translationResult")) }
                            vm.detailLoading -> Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("正在翻译…", color = Ink.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
                            }
                            vm.lastError != null -> Column(Modifier.padding(top = 16.dp)) {
                                Text("这次翻译没有完成", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text(vm.lastError.orEmpty(), color = Ink.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                                TextButton(onClick = vm::retry) { Text("重试原请求") }
                            }
                            else -> Text("翻译已取消，原文仍保留在这里。", color = Ink.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp))
                        }
                    }
                }
            }
            if (record != null && !vm.detailDirty) {
                val result = record.result
                val learning = result.learning
                if (record.analysisState != "complete") item(key = "analysis-progress") {
                    Surface(color = Ink.Pale.copy(alpha = .8f), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().testTag("analysisProgress")) {
                        Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (record.analysisState == "pending") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Column(Modifier.padding(start = if (record.analysisState == "pending") 12.dp else 0.dp)) {
                                Text(if (record.analysisState == "pending") "译文已就绪，正在补充注音与词汇解析…" else "译文已保留", color = Ink.Blue, fontSize = 12.sp)
                                if (record.analysisError.isNotBlank()) Text(record.analysisError, color = Ink.Muted, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
                                if (record.analysisState != "pending") Text("可点击上方“重新翻译”补充完整结果。", color = Ink.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                    }
                }
                if (record.analysisState == "complete") {
                    item(key = "readings") {
                        Column(Modifier.testTag("readingsSection")) {
                            DetailHeading("注音与读音", "speaker")
                            Surface(color = Ink.Card, shape = RoundedCornerShape(24.dp)) {
                                Column(Modifier.fillMaxWidth().padding(start = 19.dp, end = 19.dp, bottom = 19.dp, top = 5.dp)) {
                                    ReadingBlock("原文", result.sourceReading.let { if (it.hasContent) it else Reading(learning.sourceLanguage, learning.pronunciation) }, result.recognized.ifBlank { request.source }, learning.sourceLanguage, speech)
                                    HorizontalDivider(color = Ink.Line, modifier = Modifier.padding(top = 14.dp, bottom = 5.dp))
                                    ReadingBlock("译文", result.targetReading, result.text, request.preset.target, speech)
                                    if (speech.speaking) TextButton(onClick = speech::stop) { Text("停止朗读", fontSize = 11.sp) }
                                }
                            }
                        }
                    }
                    if (learning.showCorrections) item { CorrectionSection(learning) }
                    if (learning.vocabulary.isNotEmpty()) item {
                        Column {
                            DetailHeading(if (learning.direction == "zh_to_foreign") "一个意思，多种表达" else "词汇与用法", "book")
                            learning.vocabulary.forEach { word -> VocabularyCard(word) }
                        }
                    }
                    if (learning.usage.isNotBlank()) item { LearningSection("搭配与语法", "note", learning.usage) }
                    if (result.info.isNotBlank() && !learning.hasContent) item { LearningSection("补充说明", "book", result.info) }
                    if (result.evaluation.isNotBlank()) item { LearningSection("表达评价", "note", result.evaluation) }
                    if (result.suggestions.isNotBlank()) item { LearningSection("表达建议", "spark", result.suggestions) }
                }
                if (result.warning.isNotBlank()) item { Text(result.warning, color = Ink.Muted, fontSize = 10.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 5.dp)) }
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${record.request.history.size} 条上下文 · 保存在本机", fontSize = 10.sp, color = Ink.Muted, modifier = Modifier.weight(1f).padding(horizontal = 5.dp))
                    IconButton(onClick = { confirmDelete = true }) { Glyph("trash", Modifier.size(17.dp), Ink.Muted, "删除这条翻译") }
                } }
            }
        }
    }
    if (confirmDelete && record != null) ConfirmDialog("删除这条翻译？", "该记录的原文、译文和解析将从本机删除。", { confirmDelete = false }) { vm.deleteRecord(record.id); confirmDelete = false }
}

@Composable private fun DetailHeading(title: String, icon: String) {
    Row(Modifier.padding(start = 5.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, Modifier.size(17.dp), Ink.Blue)
        Text(title, fontSize = 14.sp, color = Ink.Dark, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
    }
}
@Composable private fun LearningSection(title: String, icon: String, body: String) {
    Column { DetailHeading(title, icon); Surface(color = Ink.Card, shape = RoundedCornerShape(24.dp)) {
        SelectionContainer { Text(body, fontSize = 13.sp, lineHeight = 23.sp, color = Ink.Dark.copy(alpha = .84f), modifier = Modifier.fillMaxWidth().padding(19.dp)) }
    } }
}
@Composable private fun VocabularyCard(word: Vocabulary) {
    Surface(color = Ink.Card, shape = RoundedCornerShape(25.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), border = BorderStroke(1.dp, Color.White)) {
        Column(Modifier.padding(19.dp)) {
            Text(word.sourceTerm, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink.Dark)
            if (word.explanation.isNotBlank()) Text(word.explanation, color = Ink.Muted, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
            word.choices.forEachIndexed { index, choice ->
                HorizontalDivider(color = Ink.Line, modifier = Modifier.padding(top = 15.dp, bottom = 15.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TinyTag("${index + 1}", Ink.Lavender)
                    Text(choice.term, color = Ink.Blue, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 9.dp))
                }
                if (choice.pronunciation.isNotBlank()) Text(choice.pronunciation, fontSize = 12.sp, color = Ink.Lavender, modifier = Modifier.padding(top = 5.dp))
                if (choice.meaning.isNotBlank()) Text(choice.meaning, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
                if (choice.usage.isNotBlank()) Text(choice.usage, color = Ink.Muted, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 7.dp))
                if (choice.example.isNotBlank()) Surface(color = Ink.Pale.copy(alpha = .65f), shape = RoundedCornerShape(13.dp), modifier = Modifier.padding(top = 11.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        SelectionContainer { Text(choice.example, color = Ink.Dark, fontSize = 13.sp, lineHeight = 21.sp) }
                        if (choice.exampleTranslation.isNotBlank()) Text(choice.exampleTranslation, color = Ink.Muted, fontSize = 11.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
    }
}

@Composable private fun CorrectionSection(learning: LearningInfo) {
    Column {
        DetailHeading("纠错解析", "edit")
        Surface(color = Ink.Card, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                if (learning.correctionSummary.isNotBlank()) Text(learning.correctionSummary, fontSize = 13.sp, lineHeight = 23.sp, color = Ink.Muted)
                learning.corrections.filter { it.original.trim() != it.corrected.trim() }.forEach { correction ->
                    Spacer(Modifier.height(13.dp))
                    Text(correction.original, color = Color(0xFFA5736B), fontSize = 14.sp)
                    Text("→ ${correction.corrected}", color = Ink.Green, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp))
                    Text(correction.explanation, color = Ink.Muted, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable private fun ReadingBlock(label: String, reading: Reading, text: String, language: String, speech: SpeechController) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(if (label == "原文") "sourceReading" else "targetReading")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${label}注音", color = Ink.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { speech.speak(text, reading.language.ifBlank { language }) }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), modifier = Modifier.heightIn(min = 34.dp)) {
                Glyph("speaker", Modifier.size(14.dp), Ink.Blue, "朗读${label}")
                Text("朗读", fontSize = 11.sp, modifier = Modifier.padding(start = 5.dp))
            }
        }
        if (reading.hasContent) {
            if (reading.phonetic.isNotBlank()) SelectionContainer { Text(reading.phonetic, fontSize = 12.sp, lineHeight = 20.sp, color = Ink.Dark) }
            if (reading.kana.isNotBlank()) SelectionContainer { Text("假名  ${reading.kana}", fontSize = 12.sp, lineHeight = 20.sp, color = Ink.Dark) }
            if (reading.romaji.isNotBlank()) SelectionContainer { Text("罗马字  ${reading.romaji}", fontSize = 12.sp, lineHeight = 20.sp, color = Ink.Blue) }
            if (reading.notes.isNotBlank()) Text(reading.notes, fontSize = 10.sp, lineHeight = 17.sp, color = Ink.Muted)
        } else Text("本条回复未提供注音，可重新翻译补充。", fontSize = 11.sp, color = Ink.Muted)
    }
}
