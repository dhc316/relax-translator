package com.yijian.translate

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

class SpeechController(context: Context, private val notify: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var closed = false
    var speaking by mutableStateOf(false)
        private set
    private val engine = TextToSpeech(context.applicationContext) { status -> main.post { if (!closed) ready = status == TextToSpeech.SUCCESS } }
    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) { main.post { if (!closed) speaking = true } }
            override fun onDone(id: String?) { main.post { if (!closed && !engine.isSpeaking) speaking = false } }
            @Deprecated("Platform callback") override fun onError(id: String?) { main.post { if (!closed) { speaking = false; notify("系统朗读失败，请检查该语言的语音包。") } } }
        })
    }
    fun speak(text: String, language: String) {
        if (!ready) { notify("系统语音服务尚未就绪，请确认手机已安装文字转语音服务。") ; return }
        val code = when (language) { "日语", "jp" -> "ja"; "中文", "zh" -> "zh-CN"; "英语" -> "en"; else -> language }
            .ifBlank { if (text.any { it in '\u3040'..'\u30ff' }) "ja" else if (text.any { it in '\u4e00'..'\u9fff' }) "zh-CN" else "en" }
        val locale = Locale.forLanguageTag(code)
        if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) { notify("系统未安装${languageName(code)}语音，可在手机的文字转语音设置中添加。") ; return }
        engine.language = locale
        engine.setSpeechRate(.88f)
        val id = newId()
        text.chunked(TextToSpeech.getMaxSpeechInputLength()).forEachIndexed { index, part ->
            if (engine.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$id-$index") == TextToSpeech.ERROR) notify("系统暂时无法朗读这段文字。")
        }
    }
    fun stop() { engine.stop(); speaking = false }
    fun close() { closed = true; engine.stop(); engine.shutdown() }
}

@Composable fun rememberSpeech(notify: (String) -> Unit): SpeechController {
    val context = LocalContext.current
    val controller = remember(context) { SpeechController(context, notify) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    return controller
}
