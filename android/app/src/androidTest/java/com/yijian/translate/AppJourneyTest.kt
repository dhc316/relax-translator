package com.yijian.translate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import androidx.compose.ui.graphics.asAndroidBitmap

@RunWith(AndroidJUnit4::class)
class AppJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private fun launch(data: AppData = AppData()) {
        instrumentation.waitForIdleSync()
        AppStore(context).save(data)
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        compose.waitForIdle()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("presetStack", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun back() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        val dir = File(context.filesDir, "qa").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
    }
    @After fun finish() { scenario?.close() }

    @Test fun extraContentMenuAutosavesPerPresetAndFitsBesideLanguageAndContext() {
        val first = Preset("extras-a", context = ContextPolicy(true, "both", 100, 10080))
        val second = Preset("extras-b", extraContent = ExtraContent(false, setOf(ExtraSection.SUGGESTIONS)))
        launch(AppData(presets = listOf(first, second), selectedPreset = first.id, fontScale = 1.3f))
        compose.onNodeWithText("额外内容").assertIsDisplayed()
        val extraBounds = compose.onNodeWithTag("extrasSelector").fetchSemanticsNode().boundsInRoot
        val languageBounds = compose.onNodeWithText("简体中文").fetchSemanticsNode().boundsInRoot
        assertTrue(languageBounds.right <= extraBounds.left)
        compose.onNodeWithText("额外内容").performClick()
        compose.onNodeWithTag("extrasEnabled").assertIsOn()
        for (section in ExtraSection.entries.filter { it != ExtraSection.VOCABULARY }) {
            compose.onNodeWithTag("extra-${section.id}").performScrollTo().performClick().assertIsOff()
        }
        compose.onNodeWithTag("extrasEnabled").performClick().assertIsOff()
        compose.onNodeWithTag("extra-vocabulary").performScrollTo().assertIsNotEnabled().assertIsOn()
        compose.waitUntil(10000) {
            AppStore(context).load().presets.first().extras == ExtraContent(false, setOf(ExtraSection.VOCABULARY))
        }
        back()
        scenario!!.onActivity { androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java].choosePreset(second.id) }
        compose.waitForIdle()
        compose.onNodeWithText("额外内容").performClick()
        compose.onNodeWithTag("extrasEnabled").assertIsOff()
        compose.onNodeWithTag("extra-suggestions").performScrollTo().assertIsOn()
        back()
        scenario!!.onActivity { androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java].choosePreset(first.id) }
        compose.waitForIdle()
        compose.onNodeWithText("额外内容").performClick()
        compose.onNodeWithTag("extrasEnabled").performClick().assertIsOn()
        compose.onNodeWithTag("extra-vocabulary").performScrollTo().assertIsOn()
        compose.onNodeWithTag("extra-readings").performScrollTo().assertIsOff()
        back()
        compose.waitUntil(10000) { AppStore(context).load().presets.first().extras == ExtraContent(sections = setOf(ExtraSection.VOCABULARY)) }
        scenario!!.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15000) { runCatching { compose.onAllNodesWithTag("extrasSelector").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.onNodeWithText("额外内容").performClick()
        compose.onNodeWithTag("extrasEnabled").assertIsOn()
        compose.onNodeWithTag("extra-vocabulary").performScrollTo().assertIsOn()
        compose.onNodeWithTag("extra-readings").performScrollTo().assertIsOff()
        screenshot("v11-extras-menu-restored")
    }

    @Test fun disablingExtrasSendsOneRequestAndShowsOnlyTheTranslation() {
        val server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
        try {
            val payload = JSONObject().put("translation", "仅译文模式已生效").put("evaluation", "不应该显示的评价")
                .put("source_reading", JSONObject().put("phonetic", "不应该显示的注音"))
            server.enqueue(MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", payload.toString())))).toString()))
            val model = ModelConfig("extras-network", "接口测试", "http://127.0.0.1:${server.port}/v1", "test")
            val preset = Preset("extras-only", modelId = model.id, extraContent = ExtraContent(enabled = false))
            launch(AppData(presets = listOf(preset), models = listOf(ModelConfig.demo, model), selectedPreset = preset.id, draft = "Hello"))
            compose.onNodeWithContentDescription("开始翻译").performClick()
            compose.waitUntil(15000) { AppStore(context).load().records.any { it.result.text == "仅译文模式已生效" } }
            compose.onNodeWithTag("translationResult").assertTextEquals("仅译文模式已生效")
            assertEquals(1, server.requestCount)
            val record = AppStore(context).load().records.single()
            assertEquals("complete", record.analysisState); assertTrue(record.result.evaluation.isBlank())
            compose.onNodeWithTag("analysisProgress").assertDoesNotExist()
            compose.onNodeWithTag("readingsSection").assertDoesNotExist()
            compose.onNodeWithText("表达评价").assertDoesNotExist()
            screenshot("v11-translation-only")
        } finally { server.shutdown() }
    }

    @Test fun selectingVocabularyOnlyHidesReadingsUsageEvaluationAndSuggestions() {
        val preset = Preset("extras-vocab", target = "en", extraContent = ExtraContent(sections = setOf(ExtraSection.VOCABULARY)))
        launch(AppData(presets = listOf(preset), selectedPreset = preset.id, draft = SAMPLE_CHINESE))
        compose.onNodeWithContentDescription("开始翻译").performClick()
        compose.waitUntil(15000) { AppStore(context).load().records.any { it.analysisState == "complete" } }
        compose.onNodeWithTag("detailScroll").performScrollToNode(hasText("一个意思，多种表达"))
        compose.onNodeWithText("一个意思，多种表达").assertIsDisplayed()
        compose.onNodeWithTag("readingsSection").assertDoesNotExist()
        compose.onNodeWithText("搭配与语法").assertDoesNotExist()
        compose.onNodeWithText("表达评价").assertDoesNotExist()
        compose.onNodeWithText("表达建议").assertDoesNotExist()
        val result = AppStore(context).load().records.single().result
        assertTrue(result.learning.vocabulary.isNotEmpty()); assertTrue(result.learning.usage.isBlank())
        assertFalse(result.sourceReading.hasContent); assertTrue(result.evaluation.isBlank())
    }

    private fun verifyHeaderLayout(fontScale: Float, image: String) {
        val preset = Preset("header", "日常翻译")
        val record = Record("header-record", RequestSnapshot("header", "hello", null, preset, ModelConfig.demo, emptyList()), Translation("你好"))
        launch(AppData(presets = listOf(preset), selectedPreset = "header", records = listOf(record), fontScale = fontScale))
        scenario!!.onActivity { androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java].setFontScale(fontScale) }
        compose.waitUntil(15000) { compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).fetchSemanticsNode().config[BackdropBlurReady] }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        val subtitle = compose.onNodeWithTag("homeSubtitle", useUnmergedTree = true)
        subtitle.assertIsDisplayed().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertFalse("subtitle line box must fit, including at larger font sizes", layout.didOverflowHeight)
        assertTrue("the complete glyph line must be inside the text height", layout.getLineBottom(0) <= layout.size.height + 1f)
        val textBox = subtitle.fetchSemanticsNode().boundsInRoot
        val glassBox = compose.onNodeWithTag("gradientBackdrop", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val headingBox = compose.onNodeWithText("历史记录").fetchSemanticsNode().boundsInRoot
        assertTrue("subtitle must fit entirely in the toolbar", textBox.bottom <= glassBox.bottom)
        assertTrue("history heading must lie completely outside the blur", headingBox.top > glassBox.bottom)
        screenshot(image)
    }

    @Test fun homeSubtitleAndHistoryHeadingRemainClear() = verifyHeaderLayout(1f, "v051-01-clear-header-and-history")

    @Test fun largeFontSubtitleFitsAndBlurFollowsTheToolbar() = verifyHeaderLayout(1.6f, "v051-02-large-font-header")

    @Test fun opaqueBackdropSurvivesDetailRoundTripsAndPresetChanges() {
        val a = Preset("opaque-a", "背景采样")
        val b = Preset("opaque-b", "其他预设")
        val records = (1..14).map { Record("opaque-$it", RequestSnapshot(a.id, "Backdrop text $it", null, a, ModelConfig.demo, emptyList()), Translation("采样内容 $it")) }
        launch(AppData(presets = listOf(a, b), selectedPreset = a.id, records = records))
        repeat(3) {
            compose.onNodeWithTag("history-opaque-14").performClick()
            compose.onNodeWithTag("translationCard").assertExists()
            back()
            scenario!!.onActivity { activity ->
                androidx.lifecycle.ViewModelProvider(activity)[AppViewModel::class.java].choosePreset(b.id)
            }
            compose.waitForIdle()
            scenario!!.onActivity { activity ->
                androidx.lifecycle.ViewModelProvider(activity)[AppViewModel::class.java].choosePreset(a.id)
            }
            compose.waitForIdle()
        }
        // BackdropBlurReady now also requires every captured pixel to be opaque.
        compose.waitUntil(15000) { compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).fetchSemanticsNode().config[BackdropBlurReady] }
        val initial = compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).fetchSemanticsNode().config[BackdropFrameNumber]
        compose.onNodeWithTag("historyList").performTouchInput { swipeUp(durationMillis = 900) }
        compose.waitUntil(15000) { compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).fetchSemanticsNode().config[BackdropFrameNumber] > initial }
        screenshot("v051-03-backdrop-after-navigation")
        compose.onNodeWithTag("draftInput").assertIsDisplayed()
    }

    @Test fun homeEditorDeletesPresetDirectlyAndHeaderHasNoAddButton() {
        val a = Preset("a", "保留空间")
        val b = Preset("b", "待删除空间")
        val record = Record("delete-owned", RequestSnapshot("b", "删除记录", null, b, ModelConfig.demo, emptyList()), Translation("删除译文"))
        launch(AppData(presets = listOf(a, b), selectedPreset = "b", records = listOf(record), draft = "删除草稿"))
        compose.onNodeWithContentDescription("新建预设").assertDoesNotExist()
        compose.onNodeWithContentDescription("编辑当前预设").performClick()
        compose.onNodeWithText("删除预设").assertIsEnabled().performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(10000) { AppStore(context).load().presets.size == 1 }
        compose.onNodeWithTag("draftInput").assertExists()
        assertEquals("a", AppStore(context).load().selectedPreset)
        assertTrue(AppStore(context).load().records.isEmpty())
        assertFalse(AppStore(context).load().drafts.containsKey("b"))
        compose.onNodeWithContentDescription("编辑当前预设").performClick()
        compose.onNodeWithText("删除预设").assertIsNotEnabled()
        compose.onNodeWithText("至少保留一个预设。").assertExists()
        screenshot("v05-01-editor-delete")
        compose.onNodeWithText("完成").performClick()
    }

    @Test fun cardAndHistoryMoveTogetherAndPhotoDoesNotChangeHeightAtSettlement() {
        val photo = File(context.filesDir, "motion-photo.png")
        Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).let { image ->
            image.eraseColor(android.graphics.Color.BLUE); photo.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        val a = Preset("a", "工作翻译", target = "en")
        val b = Preset("b", "日语学习", target = "ja", context = ContextPolicy(true, count = 10))
        fun record(id: String, preset: Preset) = Record(id, RequestSnapshot(preset.id, "Original $id", null, preset, ModelConfig.demo, emptyList()), Translation("Translation $id"))
        launch(AppData(presets = listOf(a, b), selectedPreset = "a", draft = "Draft A", drafts = mapOf("a" to PresetDraft("Draft A"), "b" to PresetDraft("Draft B", photo.path)),
            records = listOf(record("A1", a), record("A2", a), record("B1", b))))
        val initialHeight = compose.onNodeWithTag("presetCard-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        val before = compose.onNodeWithTag("historyTransition", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        screenshot("v05-02-home-shared-background")
        compose.onNodeWithTag("presetStack", useUnmergedTree = true).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * .72f, height * .30f))
            moveTo(androidx.compose.ui.geometry.Offset(width * .63f, height * .30f), 80)
            moveTo(androidx.compose.ui.geometry.Offset(width * .52f, height * .30f), 120)
            moveTo(androidx.compose.ui.geometry.Offset(width * .40f, height * .30f), 160)
        }
        val during = compose.onNodeWithTag("historyTransition", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        var changed = 0
        for (y in (before.height * .16).toInt() until (before.height * .45).toInt() step 3)
            for (x in 40 until before.width - 40 step 3) if (before.getPixel(x, y) != during.getPixel(x, y)) changed++
        screenshot("v05-03-linked-transition-mid-gesture")
        val position = compose.onNodeWithTag("presetStack", useUnmergedTree = true).fetchSemanticsNode().config[PresetPosition]
        assertTrue("drag must advance the actual deck position", position > .1f)
        assertTrue("history must visibly transition before the gesture ends; position=$position changed=$changed", changed > 100)
        assertEquals("a", AppStore(context).load().selectedPreset)
        compose.onNodeWithTag("presetStack", useUnmergedTree = true).performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(width * .12f, height * .30f), 200); up() }
        compose.waitUntil(10000) { AppStore(context).load().selectedPreset == "b" }
        compose.onNodeWithTag("draftInput").assertTextEquals("Draft B")
        compose.onNodeWithText("图片已添加").assertIsDisplayed()
        val finalHeight = compose.onNodeWithTag("presetCard-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        assertEquals("photo page uses the same card geometry", initialHeight, finalHeight, 1.5f)
        compose.onNodeWithText("日语").assertIsDisplayed()
        compose.onNodeWithText("最近 10 条").assertIsDisplayed()
        screenshot("v05-04-full-photo-card-after-swipe")
    }

    @Test fun blurredBackdropUpdatesBeforeTheScrollGestureEnds() {
        val preset = Preset("p")
        val records = (1..14).map { Record("frost-$it", RequestSnapshot("p", "Visible original $it", null, preset, ModelConfig.demo, emptyList()), Translation("渐变模糊测试 $it")) }
        launch(AppData(presets = listOf(preset), selectedPreset = "p", records = records))
        compose.waitUntil(15000) { compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).fetchSemanticsNode().config[BackdropBlurReady] }
        val initial = compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).fetchSemanticsNode().config[BackdropFrameNumber]
        compose.onNodeWithTag("historyList").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * .5f, height * .4f)); moveTo(androidx.compose.ui.geometry.Offset(width * .5f, height * .15f), 500)
        }
        compose.waitUntil(15000) { compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).fetchSemanticsNode().config[BackdropFrameNumber] > initial }
        screenshot("v05-05-linear-frost-during-scroll")
        compose.onNodeWithTag("historyList").performTouchInput { up() }
    }

    @Test fun imagePickerUsesPhotoOrGallerySelectionAndHandlesCancel() {
        val picker = GalleryImageContract()
        val intent = picker.createIntent(context, Unit)
        assertNotEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertNotEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intent.action)
        assertEquals("image/*", intent.type)
        val uri = Uri.parse("content://media/external/images/media/42")
        assertEquals(uri, picker.parseResult(android.app.Activity.RESULT_OK, Intent().setData(uri)))
        assertNull(picker.parseResult(android.app.Activity.RESULT_CANCELED, Intent().setData(uri)))
        assertNull(picker.parseResult(android.app.Activity.RESULT_OK, Intent()))
    }

    @Test fun quickTranslationIsVisibleWhileFullRequestIsStillBlockedAndUpdatesOneHistoryRecord() {
        val server = MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"), 0)
        val both = CountDownLatch(2); val fullGate = CountDownLatch(1)
        try {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    both.countDown(); both.await(5, TimeUnit.SECONDS)
                    val quick = request.body.clone().readUtf8().contains("本次仅返回翻译结果")
                    if (!quick) fullGate.await(30, TimeUnit.SECONDS)
                    val content = if (quick) "Fast translation" else """{"translation":"Full translation","source_reading":{"language":"zh-CN","phonetic":"cè shì"},"target_reading":{"language":"en","phonetic":"/fʊl/"},"learning":{"usage_notes":"完整用法说明"}}"""
                    return MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString())
                }
            }
            val model = ModelConfig("parallel-ui", "并行测试", "http://127.0.0.1:${server.port}/v1", "test")
            val preset = Preset("p", target = "en", modelId = model.id)
            launch(AppData(presets = listOf(preset, preset.copy(id = "other", name = "其他空间")), models = listOf(ModelConfig.demo, model), selectedPreset = "p", draft = "测试"))
            compose.onNodeWithContentDescription("开始翻译").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Fast translation").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(0L, both.count)
            assertEquals(1, AppStore(context).load().records.size)
            val id = AppStore(context).load().records.single().id
            compose.onNodeWithTag("translationResult").assertTextEquals("Fast translation")
            compose.onNodeWithTag("analysisProgress").assertIsDisplayed()
            compose.onNodeWithTag("readingsSection", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithContentDescription("复制译文").performClick()
            screenshot("v04-07-fast-translation-visible")
            scenario!!.onActivity {
                val vm = androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java]
                assertTrue(vm.busy)
                vm.choosePreset("other"); vm.updateDraft("Keep other draft"); vm.resumeDetail()
            }
            compose.onNodeWithTag("translationResult").assertTextEquals("Fast translation")
            fullGate.countDown()
            compose.waitUntil(15000) { AppStore(context).load().records.singleOrNull()?.analysisState == "complete" }
            compose.onNodeWithTag("translationResult").assertTextEquals("Full translation")
            compose.onNodeWithTag("analysisProgress").assertDoesNotExist()
            assertEquals(id, AppStore(context).load().records.single().id)
            assertEquals("Keep other draft", AppStore(context).load().drafts["other"]?.text)
            assertEquals(2, server.requestCount)
            compose.onNodeWithTag("detailScroll").performScrollToNode(hasText("完整用法说明"))
            compose.onNodeWithText("完整用法说明").assertIsDisplayed()
            screenshot("v04-08-full-result-same-record")
        } finally { fullGate.countDown(); server.shutdown() }
    }

    @Test fun cancellingOrDeletingAPartialResultPreventsLateAnalysisFromResurrectingIt() {
        val server = MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"), 0)
        val cancelGate = CountDownLatch(1); val deleteGate = CountDownLatch(1)
        try {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val body = request.body.clone().readUtf8()
                    val quick = body.contains("本次仅返回翻译结果")
                    val deleting = body.contains("Delete me")
                    if (!quick) (if (deleting) deleteGate else cancelGate).await(20, TimeUnit.SECONDS)
                    val content = if (quick) (if (deleting) "Delete quick" else "Keep quick") else "{\"translation\":\"Late full\"}"
                    return MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content)))).toString())
                }
            }
            val model = ModelConfig("cancel-ui", "取消测试", "http://127.0.0.1:${server.port}/v1", "test")
            val preset = Preset("p", modelId = model.id)
            launch(AppData(presets = listOf(preset), models = listOf(ModelConfig.demo, model), selectedPreset = "p", draft = "Cancel me"))
            compose.onNodeWithContentDescription("开始翻译").performClick()
            compose.waitUntil(15000) { compose.onAllNodesWithText("Keep quick").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("取消").performClick()
            compose.waitUntil(10000) { AppStore(context).load().records.singleOrNull()?.analysisState == "cancelled" }
            compose.onNodeWithTag("translationResult").assertTextEquals("Keep quick")
            cancelGate.countDown(); screenshot("v04-09-cancel-keeps-translation")
            scenario!!.onActivity {
                val vm = androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java]
                vm.updateDraft("Delete me"); vm.send()
            }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Delete quick").fetchSemanticsNodes().isNotEmpty() }
            val partialId = AppStore(context).load().records.first { it.result.text == "Delete quick" }.id
            scenario!!.onActivity { androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java].deleteRecord(partialId) }
            deleteGate.countDown()
            compose.waitUntil(10000) { AppStore(context).load().records.size == 1 }
            Thread.sleep(400)
            assertEquals("Keep quick", AppStore(context).load().records.single().result.text)
            assertTrue(AppStore(context).load().records.none { it.result.text == "Late full" })
        } finally { cancelGate.countDown(); deleteGate.countDown(); server.shutdown() }
    }

    @Test fun truncatedModelReplyRecoversOnTheAndroidJsonRuntime() {
        val partial = """{"recognized_source":"","translation":"テスト","learning":{"source_language":"zh","usage_notes":"原词可以作名词"""
        val envelope = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", partial)).put("finish_reason", "length"))).toString()
        val result = parseResponse(envelope)
        assertEquals("テスト", result.text)
        assertTrue(result.warning.contains("不完整"))
        val preset = Preset("recovery", target = "ja")
        val legacy = AppData(presets = listOf(preset), selectedPreset = preset.id,
            records = listOf(Record("recovered", RequestSnapshot("old-session", "测试", null, preset, ModelConfig.demo, emptyList()), Translation(partial))))
        launch(legacy)
        compose.onNodeWithText("テスト").assertIsDisplayed()
        assertEquals("テスト", AppStore(context).load().records.single().result.text)
        screenshot("v03-19-recovered-json-history")
    }

    @Test fun homeMenusAndSettingsAreUsable() {
        launch()
        compose.onNodeWithText("今天，想翻译些什么？").assertIsDisplayed()
        screenshot("01-home")
        compose.onNodeWithText("简体中文").performClick()
        compose.onNodeWithText("翻译成").assertIsDisplayed()
        screenshot("02-language-menu")
        compose.onNodeWithText("编辑常用").performClick()
        compose.onNodeWithContentDescription("移除常用韩语").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onAllNodesWithText("英语").onFirst().performClick()
        compose.onNodeWithText("上下文关闭").performClick()
        compose.onNodeWithText("按时间").performClick()
        screenshot("03-context-menu")
        back()
        compose.onNodeWithText("近 30 分钟").assertIsDisplayed()
        compose.onNodeWithText("本地演示").performClick()
        compose.onNodeWithText("选择模型").assertIsDisplayed()
        screenshot("04-model-menu")
        back()
        compose.onNodeWithContentDescription("打开菜单").performClick()
        screenshot("05-drawer")
        compose.onNodeWithContentDescription("打开设置").performClick()
        compose.onNodeWithText("翻译模型").assertIsDisplayed()
        screenshot("06-settings")
        compose.onNodeWithText("添加翻译模型").performClick()
        compose.onAllNodesWithText("添加翻译模型").onLast().assertIsDisplayed()
        screenshot("07-model-settings")
        compose.onNodeWithContentDescription("关闭模型设置").performClick()
        assertFalse("favorite change must persist", AppStore(context).load().favorites.contains("ko"))
    }

    @Test fun presetAutosavesAndKeepsItsOwnDraftAfterSwitchAndRelaunch() {
        val first = Preset(id = "first")
        launch(AppData(presets = listOf(first), selectedPreset = first.id, draft = "Draft A"))
        compose.onNodeWithTag("presetStack", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.onNodeWithText("给这张卡片一个用途").assertIsDisplayed()
        screenshot("v03-08-new-preset")
        compose.onNodeWithText("设置这个预设").performClick()
        compose.onNodeWithText("预设名称，如：工作邮件").performTextReplacement("Work")
        compose.onNodeWithText("补充翻译要求").performTextReplacement("Be polite and concise.")
        compose.onNodeWithText("完成").performClick()
        compose.waitUntil(10000) { AppStore(context).load().presets.last().name == "Work" }
        assertEquals("", compose.onNodeWithTag("draftInput").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("draftInput").performTextInput("Draft B")
        // Semantics text entry may not open the IME; Back can finish the activity.
        scenario!!.onActivity { activity ->
            val view = activity.currentFocus ?: activity.window.decorView
            (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(view.windowToken, 0)
        }
        compose.waitForIdle()
        scenario!!.onActivity { androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java].choosePreset("first") }
        compose.onNodeWithTag("draftInput").assertTextEquals("Draft A")
        val second = AppStore(context).load().presets.last().id
        scenario!!.onActivity { androidx.lifecycle.ViewModelProvider(it)[AppViewModel::class.java].choosePreset(second) }
        compose.onNodeWithTag("draftInput").assertTextEquals("Draft B")
        screenshot("v03-09-autosaved-preset")
        compose.waitUntil(10000) { AppStore(context).load().draft == "Draft B" }
        scenario!!.close(); scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(15000) { runCatching { compose.onAllNodesWithTag("presetStack", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        compose.waitForIdle()
        val restored = AppStore(context).load()
        assertEquals("Work", restored.presets.first { it.id == restored.selectedPreset }.name)
        compose.onNodeWithTag("draftInput").assertTextEquals("Draft B")
        assertEquals("Be polite and concise.", AppStore(context).load().presets.last().instruction)
        assertEquals("Draft A", AppStore(context).load().drafts["first"]?.text)
    }

    @Test fun historyIsCompactAndOpensAnEditableTranslationDetail() {
        launch()
        compose.onNodeWithText("试译一句英文").performClick()
        compose.onNodeWithContentDescription("开始翻译").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("能给我一点时间吗？我想把这件事做好。").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15000) { AppStore(context).load().records.any { it.analysisState == "complete" } }
        compose.onNodeWithText("翻译详情").assertIsDisplayed()
        compose.onNodeWithTag("detailInput").assertTextEquals(SAMPLE_SOURCE)
        compose.onNodeWithTag("translationResult").assertIsDisplayed()
        compose.onNodeWithTag("detailScroll").performScrollToNode(hasText("注音与读音"))
        compose.onNodeWithContentDescription("朗读原文").assertExists()
        compose.onNodeWithContentDescription("朗读译文").assertExists()
        compose.onNodeWithText("纠错解析").assertDoesNotExist()
        screenshot("v03-10-detail")
        for (heading in listOf("注音与读音", "搭配与语法")) {
            compose.onNodeWithTag("detailScroll").performScrollToNode(hasText(heading))
            compose.onNodeWithText(heading).assertExists()
        }
        compose.onNodeWithTag("detailScroll").performScrollToIndex(0)
        compose.onNodeWithContentDescription("复制译文").performScrollTo().performClick()
        compose.onNodeWithContentDescription("返回历史").performClick()
        compose.onNodeWithText("历史记录").assertIsDisplayed()
        compose.onNodeWithText("原文读音").assertDoesNotExist()
        compose.onNodeWithText("纠错解析").assertDoesNotExist()
        screenshot("v03-11-history")
        compose.onNodeWithText("简体中文").performClick()
        compose.onAllNodesWithText("日语").onFirst().performClick()
        compose.onNodeWithText("能给我一点时间吗？我想把这件事做好。").performClick()
        compose.onNodeWithText("翻译详情").assertIsDisplayed()
        compose.onNodeWithContentDescription("详情设置").performClick()
        back()
        compose.onNodeWithText("翻译详情").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回历史").performClick()
        compose.onNodeWithText("日语").assertIsDisplayed()
        compose.onNodeWithText("能给我一点时间吗？我想把这件事做好。").performClick()
        compose.onNodeWithTag("detailScroll").performScrollToNode(hasContentDescription("删除这条翻译"))
        compose.onNodeWithContentDescription("删除这条翻译").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.onNodeWithText("今天，想翻译些什么？").assertIsDisplayed()
    }

    @Test fun chineseToEnglishShowsContextualWordChoicesBelowTranslation() {
        launch()
        compose.onNodeWithText("试试中文辨析").performClick()
        compose.onNodeWithContentDescription("开始翻译").performClick()
        compose.waitUntil(15000) { AppStore(context).load().records.any { it.analysisState == "complete" } }
        compose.onNodeWithTag("detailScroll").performScrollToNode(hasText("一个意思，多种表达"))
        compose.onNodeWithText("一个意思，多种表达").assertExists()
        compose.onNodeWithText("suitable").assertExists()
        compose.onNodeWithText("appropriate").assertExists()
        compose.onNodeWithTag("detailScroll").performScrollToNode(hasText("appropriate"))
        screenshot("v03-12-word-choices")
        val learning = AppStore(context).load().records.single().result.learning
        assertEquals(3, learning.vocabulary.first().choices.size)
        assertTrue(learning.vocabulary.first().choices.all { it.exampleTranslation.isNotBlank() })
    }

    @Test fun keyboardNeverCoversHomeOrDetailInput() {
        launch()
        compose.onNodeWithTag("draftInput").performClick()
        compose.onNodeWithTag("draftInput").performTextInput(SAMPLE_SOURCE)
        assertInputAboveKeyboard("draftInput")
        screenshot("v03-13-home-keyboard")
        compose.onNodeWithContentDescription("开始翻译").performClick()
        compose.waitUntil(15000) { AppStore(context).load().records.any { it.analysisState == "complete" } }
        compose.onNodeWithTag("detailInput").performClick()
        assertInputAboveKeyboard("detailInput")
        screenshot("v03-14-detail-keyboard")
    }

    private fun assertInputAboveKeyboard(tag: String) {
        var keyboardTop = 0
        compose.waitUntil(15000) {
            scenario!!.onActivity { activity ->
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                if (insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true)
                    keyboardTop = activity.window.decorView.height - insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            }
            keyboardTop > 0 && compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom <= keyboardTop
        }
        assertTrue(compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom <= keyboardTop)
    }

    @Test fun recentsDeckKeepsLeftCardInFrontAndGathersRightCardsBehind() {
        val presets = listOf(Preset("a", "工作"), Preset("b", "日常翻译"), Preset("c", "日语练习", target = "ja"))
        launch(AppData(presets = presets, selectedPreset = "b", draft = "Preserve me"))
        fun card(index: Int) = compose.onNodeWithTag("presetCard-$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        fun tap(x: Float, y: Float) {
            val deck = compose.onNodeWithTag("presetStack", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("presetStack", useUnmergedTree = true).performTouchInput {
                click(androidx.compose.ui.geometry.Offset(x - deck.left, y - deck.top))
            }
            compose.waitForIdle()
        }
        val left = card(0); val center = card(1); val right = card(2)
        assertTrue("left card must overlap selected card", left.right > center.left)
        assertTrue("selected card must overlap right card", center.right > right.left)
        assertTrue("right cards gather behind rather than sit a full page apart", right.left > center.left && right.left < center.center.x)
        assertTrue("left overlap must leave current editor unobscured", left.right <= compose.onNodeWithTag("draftInput").fetchSemanticsNode().boundsInRoot.left)
        assertTrue("left foreground overlap stays below five percent of selected width", left.right - center.left < center.width * .05f)
        screenshot("v04-01-asymmetric-middle-deck")
        // Hit-test the actual intersections, not a copy of the z-index formula.
        tap((right.left + center.right) / 2, center.top + center.height * .15f)
        assertEquals("b", AppStore(context).load().selectedPreset)
        tap((center.left + left.right) / 2, center.center.y)
        compose.waitUntil(10000) { AppStore(context).load().selectedPreset == "a" }
        compose.onNodeWithTag("draftInput").assertExists()
        screenshot("v04-02-left-card-on-top")
        val first = card(0); val second = card(1)
        tap((second.left + first.right) / 2, first.top + first.height * .15f)
        assertEquals("a", AppStore(context).load().selectedPreset)
        screenshot("v04-03-left-edge-deck")
        tap((card(0).right + card(1).right) / 2, card(1).center.y)
        compose.waitUntil(10000) { AppStore(context).load().selectedPreset == "b" }
        compose.onNodeWithTag("draftInput").assertTextEquals("Preserve me")
        compose.onNodeWithTag("presetStack", useUnmergedTree = true).performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * .60f, height * .3f))
            moveTo(androidx.compose.ui.geometry.Offset(width * .45f, height * .3f), 350)
        }
        screenshot("v04-04-deck-in-motion")
        compose.onNodeWithTag("presetStack", useUnmergedTree = true).performTouchInput { up() }
        compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).assertExists()
        val deck = compose.onNodeWithTag("presetStack", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val card = compose.onNodeWithTag("draftInput").fetchSemanticsNode().boundsInRoot
        assertTrue("cards leave room for side neighbours", card.width < deck.width * .85f)
    }

    @Test fun inputAndResultComeBeforeReadingsAndOtherLearning() {
        launch()
        compose.onNodeWithText("日语注音示例").performClick()
        compose.onNodeWithContentDescription("开始翻译").performClick()
        compose.waitUntil(15000) { AppStore(context).load().records.any { it.analysisState == "complete" } }
        compose.onNodeWithTag("detailInput").assertIsDisplayed()
        compose.onNodeWithTag("translationResult").assertIsDisplayed()
        val source = compose.onNodeWithTag("detailInput").fetchSemanticsNode().boundsInRoot
        val translation = compose.onNodeWithTag("translationResult").fetchSemanticsNode().boundsInRoot
        val readingTop = compose.onNodeWithTag("readingsSection", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue("editable source must precede translation", source.bottom <= translation.top)
        assertTrue("both primary fields must precede readings", translation.bottom <= readingTop)
        screenshot("v031-04-input-result-first")
        compose.onNodeWithTag("detailScroll").performScrollToNode(hasTestTag("targetReading"))
        compose.onNodeWithTag("sourceReading").assertExists()
        compose.onNodeWithTag("targetReading").assertIsDisplayed()
        compose.onNodeWithText("假名  きょうはなにをたべますか。").assertIsDisplayed()
        compose.onNodeWithText("罗马字  Kyō wa nani o tabemasu ka?").assertIsDisplayed()
        compose.onNodeWithTag("detailScroll").performScrollToNode(hasText("表达评价"))
        assertTrue(compose.onNodeWithTag("readingsSection", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom <= compose.onNodeWithText("表达评价").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("纠错解析").assertDoesNotExist()
        screenshot("v031-05-readings-before-learning")
    }

    @Test fun presetContextAndDraftStayIsolatedWhileRequestIsInFlight() {
        val server = MockWebServer(); server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val model = ModelConfig("isolation-model", "隔离测试", "http://127.0.0.1:${server.port}/v1", "qa")
            val a = Preset("a", "空间 A", modelId = model.id, context = ContextPolicy(true))
            val b = a.copy(id = "b", name = "空间 B")
            fun previous(id: String, preset: Preset) = Record(id, RequestSnapshot("same-old-session", "context $id", null, preset, model, emptyList(), System.currentTimeMillis() - 2000), Translation("history $id"))
            launch(AppData(presets = listOf(a,b), models = listOf(ModelConfig.demo, model), selectedPreset = "a", records = listOf(previous("A_ONLY", a), previous("B_ONLY", b)), draft = "draft a"))
            server.enqueue(MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "{\"translation\":\"done b\"}")))).toString()).setHeadersDelay(900, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "{\"translation\":\"done b\"}")))).toString()).setHeadersDelay(900, TimeUnit.MILLISECONDS))
            scenario!!.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[AppViewModel::class.java]
                vm.choosePreset("b"); vm.updatePreset { it.copy(target = "ja") }; vm.updateDraft("send b"); vm.send()
                vm.choosePreset("a"); vm.updateDraft("keep a")
            }
            compose.waitUntil(15000) { AppStore(context).load().records.any { it.result.text == "done b" && it.analysisState == "complete" } }
            repeat(2) {
                val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).toString()
                assertTrue(body.contains("B_ONLY")); assertFalse(body.contains("A_ONLY"))
            }
            val stored = AppStore(context).load()
            assertEquals("keep a", stored.draft); assertEquals("ja", stored.presets.last().target)
            assertEquals("", stored.drafts["b"]?.text)
            assertEquals("a", stored.selectedPreset)
            server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"translation\\\":\\\"late b\\\"}\"}}]}").setHeadersDelay(2500, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"{\\\"translation\\\":\\\"late b\\\"}\"}}]}").setHeadersDelay(2500, TimeUnit.MILLISECONDS))
            scenario!!.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[AppViewModel::class.java]
                vm.choosePreset("b"); vm.updateDraft("delete while sending"); vm.send(); vm.deletePreset("b")
                assertFalse(vm.busy)
            }
            compose.waitUntil(10000) { AppStore(context).load().presets.size == 1 }
            assertTrue(AppStore(context).load().records.none { it.request.preset.id == "b" })
        } finally { server.shutdown() }
    }

    @Test fun translationInputCardRisesFromComposerToBelowTheToolbar() {
        launch()
        compose.onNodeWithText("试译一句英文").performClick()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("开始翻译").performClick()
            compose.mainClock.advanceTimeBy(96)
            val initialTop = compose.onNodeWithTag("translationCard", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            screenshot("v03-17-input-rising")
            compose.mainClock.advanceTimeBy(900)
            val finalTop = compose.onNodeWithTag("translationCard", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            val toolbarBottom = compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("input card should visibly move up: $initialTop -> $finalTop", initialTop - finalTop > 60f)
            assertTrue("input card stays below toolbar", finalTop >= toolbarBottom - 1f)
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun scrollingHistoryHasARealBlurredBackdrop() {
        val preset = Preset("p")
        val records = (1..12).map { index ->
            Record("blur-$index", RequestSnapshot("s", "The word suitable depends on context. $index", null, preset, ModelConfig.demo, emptyList(), index.toLong()), Translation("“适合”在不同场景中有不同表达。$index"))
        }
        launch(AppData(presets = listOf(preset), selectedPreset = "p", sessions = listOf(Session("s")), selectedSession = "s", records = records))
        compose.onNodeWithTag("historyList").performTouchInput { swipeUp() }
        compose.waitUntil(15000) { compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).fetchSemanticsNode().config[BackdropBlurReady] }
        screenshot("v03-16-real-backdrop-blur")
    }

    @Test fun imageAndContextTravelThroughRealHttpAndRequestSettingsStayFixed() {
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val model = ModelConfig("qa-model", "接口测试", "http://127.0.0.1:${server.port}/v1", "qa-vision", vision = true)
            val preset = Preset("p", "日常翻译", modelId = model.id, context = ContextPolicy(true, "both", 1, 30))
            val now = System.currentTimeMillis()
            fun old(id: String, ago: Long, session: String = "s") = Record(id, RequestSnapshot(session, "old $id", null, preset, model, emptyList(), now - ago), Translation("旧译文$id"))
            val imageFile = File(context.cacheDir, "qa-source.png")
            val bmp = Bitmap.createBitmap(3000, 900, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { drawColor(android.graphics.Color.WHITE); drawText("Hello from a photo", 80f, 300f, Paint().apply { color = android.graphics.Color.BLACK; textSize = 100f }) }
            imageFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; bmp.recycle()
            val store = AppStore(context)
            val imported = store.importImage(Uri.fromFile(imageFile))
            store.saveKey(model.id, "qa-local-test-key")
            val state = AppData(presets = listOf(preset, preset.copy(id = "other-preset", name = "其他预设")), models = listOf(ModelConfig.demo, model),
                sessions = listOf(Session("s", "图片测试", now)), selectedSession = "s", selectedPreset = "p",
                records = listOf(old("old", 3600000), old("recent", 60000), old("other", 1000, "other").let { it.copy(request = it.request.copy(preset = preset.copy(id = "other-preset"))) }), draft = "翻译图片中的文字", draftImage = imported)
            val result = JSONObject().put("translation", "来自照片的问候").put("recognized_source", "Hello from a photo").put("derived_info", "这是一句问候语。").put("evaluation", "语气友好。").put("suggestions", "可用于照片说明。")
            val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", result.toString())).put("finish_reason", "stop")))
            server.enqueue(MockResponse().setBody(response.toString()).setHeadersDelay(1500, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(response.toString()).setHeadersDelay(1500, TimeUnit.MILLISECONDS))
            launch(state)
            scenario!!.onActivity { activity -> androidx.lifecycle.ViewModelProvider(activity)[AppViewModel::class.java].importImage(Uri.fromFile(imageFile)) }
            compose.waitUntil(15000) { AppStore(context).load().drafts["p"]?.image != null }
            assertEquals("翻译图片中的文字", AppStore(context).load().draft)
            compose.onNodeWithText("图片已添加").assertIsDisplayed()
            compose.onNodeWithContentDescription("开始翻译").performClick()
            scenario!!.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[AppViewModel::class.java]
                vm.updatePreset { it.copy(target = "en") }
            }
            compose.waitUntil(20000) { AppStore(context).load().records.any { it.result.text == "来自照片的问候" && it.analysisState == "complete" } }
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer qa-local-test-key", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            val messages = body.getJSONArray("messages")
            assertEquals(4, messages.length())
            assertTrue(messages.getJSONObject(1).getString("content").contains("recent"))
            assertFalse(messages.getJSONObject(1).getString("content").contains("旧译文old"))
            val parts = messages.getJSONObject(3).getJSONArray("content")
            assertTrue(parts.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/jpeg;base64,"))
            val secondRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer qa-local-test-key", secondRequest.getHeader("Authorization"))
            val secondMessages = JSONObject(secondRequest.body.readUtf8()).getJSONArray("messages")
            for (i in 1 until messages.length()) assertEquals(messages.get(i).toString(), secondMessages.get(i).toString())
            val saved = store.load().records.last()
            assertEquals("zh-CN", saved.request.preset.target)
            assertEquals("recent", saved.request.history.single().id)
            assertFalse(File(context.filesDir, "state.json").readText().contains("qa-local-test-key"))
            assertEquals("qa-local-test-key", store.key(model.id))
            screenshot("12-image-http-result")
            scenario!!.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[AppViewModel::class.java]
                vm.importImage(Uri.fromFile(imageFile)); vm.choosePreset("other-preset")
            }
            compose.waitUntil(15000) { AppStore(context).load().drafts["p"]?.image != null }
            assertNull(AppStore(context).load().draftImage)
            assertNotNull(AppStore(context).load().drafts["p"]?.image)
        } finally { server.shutdown() }
    }
}
