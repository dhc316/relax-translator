package com.yijian.translate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        setContent {
            val vm: AppViewModel = viewModel()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * vm.data.fontScale)) { RelaxTranslatorTheme { App(vm) } }
        }
    }
}

@Composable private fun App(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val snack = remember { SnackbarHostState() }
    var route by rememberSaveable { mutableStateOf("home") }
    var settingsReturn by rememberSaveable { mutableStateOf("home") }
    var showModel by remember { mutableStateOf(false) }
    var editModel by remember { mutableStateOf<ModelConfig?>(null) }
    val picker = rememberLauncherForActivityResult(GalleryImageContract()) { uri -> if (uri != null) vm.importImage(uri) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val json = JSONObject().put("app", context.getString(R.string.app_name)).put("version", 3).put("presets", JSONArray(vm.data.presets.map { it.json() })).put("records", JSONArray(vm.data.records.map { it.json() })).toString(2)
            scope.launch {
                try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("无法写入文件") }; vm.showNotice("历史已导出") }
                catch (_: Exception) { vm.showNotice("导出失败，请选择其他保存位置") }
            }
        }
    }
    LaunchedEffect(vm.notice) { vm.notice?.let { vm.clearNotice(); snack.showSnackbar(it, duration = SnackbarDuration.Short) } }
    BackHandler(drawer.isOpen || route != "home" || vm.detailOpen) {
        when { drawer.isOpen -> scope.launch { drawer.close() }; route == "settings" -> route = settingsReturn; vm.detailOpen -> vm.closeDetail(); else -> route = "home" }
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, scrimColor = Color(0xFF313B60).copy(alpha = .22f), drawerContent = {
        DrawerContent(vm, onClose = { scope.launch { drawer.close() } }, onSettings = { settingsReturn = "home"; route = "settings"; scope.launch { drawer.close() } })
    }) {
        Box(Modifier.fillMaxSize()) {
            MistBackground()
            Crossfade(if (route == "settings") "settings" else if (vm.detailOpen) "detail" else "home", animationSpec = androidx.compose.animation.core.tween(180), label = "screen") { page ->
                if (page == "home") HomeScreen(vm, { scope.launch { drawer.open() } }, { runCatching { picker.launch(Unit) }.onFailure { vm.showNotice("未能打开图库，请检查系统图库应用是否可用。") } },
                    { editModel = null; showModel = true }, { settingsReturn = "home"; route = "settings" })
                else if (page == "detail") DetailScreen(vm) { settingsReturn = "home"; route = "settings" }
                else SettingsScreen(vm, { route = settingsReturn }, { model -> editModel = model; showModel = true }, { exporter.launch(context.getString(R.string.history_export_filename)) })
            }
            SnackbarHost(snack, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 80.dp, start = 18.dp, end = 18.dp)) { data ->
                Snackbar(data, containerColor = Color(0xFF374363), contentColor = Color.White, shape = RoundedCornerShape(17.dp))
            }
        }
    }
    if (showModel) ModelEditor(vm, editModel) { showModel = false }
}

@Composable private fun DrawerContent(vm: AppViewModel, onClose: () -> Unit, onSettings: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var deletePreset by remember { mutableStateOf<Preset?>(null) }
    ModalDrawerSheet(modifier = Modifier.fillMaxWidth(.88f), drawerShape = RoundedCornerShape(topEnd = 33.dp, bottomEnd = 33.dp), drawerContainerColor = Color(0xFFEAF0FC)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.size(40.dp)); BrandTitle(Modifier.weight(1f).padding(start = 10.dp))
                CircleAction("close", "关闭菜单", onClick = onClose)
            }
            PrimaryButton("＋  新建预设", Modifier.fillMaxWidth()) { vm.beginNewPreset(); onClose() }
            Spacer(Modifier.height(17.dp))
            SmallField(search, { search = it }, "搜索预设与翻译")
            Text("预设与独立上下文", fontSize = 12.sp, color = Ink.Muted, modifier = Modifier.padding(top = 27.dp, bottom = 13.dp, start = 5.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                vm.data.presets.filter { preset -> search.isBlank() || preset.name.contains(search, true) || vm.data.records.any { it.request.preset.id == preset.id && (it.request.source.contains(search, true) || it.result.text.contains(search, true)) } }.forEach { preset ->
                    val selected = vm.workingPreset.id == preset.id
                    Row(Modifier.fillMaxWidth().padding(bottom = 7.dp).background(if (selected) Color(0xFFDCE4F7) else Color.Transparent, RoundedCornerShape(19.dp))
                        .clickable { vm.choosePreset(preset.id); onClose() }.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name.ifBlank { "未命名预设" }, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${vm.data.records.count { it.request.preset.id == preset.id }} 条翻译", fontSize = 10.sp, color = Ink.Muted, modifier = Modifier.padding(top = 6.dp))
                        }
                        IconButton(onClick = { deletePreset = preset }, modifier = Modifier.size(36.dp)) { Glyph("trash", Modifier.size(15.dp), Ink.Muted, "删除预设") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 21.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Ink.Green, androidx.compose.foundation.shape.CircleShape))
                Column(Modifier.weight(1f).padding(start = 9.dp)) { Text("保存在本机", fontSize = 12.sp); Text("预设与历史，随时继续", fontSize = 10.sp, color = Ink.Muted, modifier = Modifier.padding(top = 4.dp)) }
                CircleAction("settings", "打开设置", onClick = onSettings)
            }
        }
    }
    deletePreset?.let { preset -> ConfirmDialog("删除这个预设？", "它的独立历史、上下文和草稿将从本机删除。", { deletePreset = null }) { vm.deletePreset(preset.id); deletePreset = null } }
}
