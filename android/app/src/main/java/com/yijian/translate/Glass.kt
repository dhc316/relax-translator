package com.yijian.translate

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

val BackdropBlurReady = SemanticsPropertyKey<Boolean>("backdropBlurReady")
val BackdropFrameNumber = SemanticsPropertyKey<Int>("backdropFrameNumber")

/** Stream current frames instead of waiting for scrolling to stop. Header controls remain crisp. */
@Composable fun FrostedPage(scrollSignal: Int, revision: Int = 0, header: @Composable () -> Unit, content: @Composable (Dp) -> Unit) {
    val source = rememberGraphicsLayer()
    val density = LocalDensity.current
    val initialHeaderHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 78.dp
    var measuredHeaderPixels by remember { mutableIntStateOf(0) }
    val headerHeight = if (measuredHeaderPixels > 0) with(density) { measuredHeaderPixels.toDp() } else initialHeaderHeight
    // End inside the toolbar. The first history heading must never be frosted at rest.
    val gradientHeight = headerHeight
    val gradientPixels = with(density) { gradientHeight.roundToPx() }
    val maxBlurPixels = with(density) { 28.dp.toPx() }
    var contentWidth by remember { mutableIntStateOf(0) }
    val currentScroll by rememberUpdatedState(scrollSignal)
    val currentRevision by rememberUpdatedState(revision)
    var frame by remember(gradientPixels, contentWidth) { mutableStateOf<ImageBitmap?>(null) }
    var frameNumber by remember { mutableIntStateOf(0) }
    LaunchedEffect(gradientPixels, maxBlurPixels, contentWidth) {
        if (contentWidth <= 0) return@LaunchedEffect
        snapshotFlow { currentScroll to currentRevision }.conflate().collect {
            withFrameNanos { }
            runCatching {
                val captured = source.toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                val blurred = withContext(Dispatchers.Default) { gradientFrost(captured, maxBlurPixels) }
                frame = blurred.asImageBitmap(); frameNumber++
            }
            delay(16)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().onSizeChanged { contentWidth = it.width }.drawWithContent {
            // One display list owns the body. Recording it twice can detach nested
            // RenderNodes after page navigation and produce a transparent snapshot.
            // clip=false preserves the full live body; the bitmap readback only
            // contains this layer's toolbar-sized bounds.
            source.clip = false
            source.record(size = IntSize(size.width.toInt(), gradientPixels)) { this@drawWithContent.drawContent() }
            drawLayer(source)
        }) { MistBackground(); content(headerHeight) }
        Canvas(Modifier.fillMaxWidth().height(gradientHeight).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.clipToBounds().testTag("gradientBackdrop")) {
            val currentFrame = frame
            drawRect(Color(0xFFE1E9FC))
            if (currentFrame != null) {
                // Replace the sharp backdrop, rather than crossfading two copies of its text.
                drawImage(currentFrame, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                drawRect(Brush.verticalGradient(listOf(Color(0xFFE5EBFC).copy(alpha = .10f), Color.Transparent)))
            }
            // The radius already approaches zero. A short seam blend meets the live body.
            drawRect(Brush.verticalGradient(listOf(Color.White, Color.Transparent), startY = maxOf(0f, size.height - 12.dp.toPx()), endY = size.height), blendMode = BlendMode.DstIn)
        }
        Box(Modifier.fillMaxWidth().height(headerHeight).testTag("glassBackdrop").semantics {
            this[BackdropBlurReady] = frame != null; this[BackdropFrameNumber] = frameNumber
        })
        Column(Modifier.fillMaxWidth().onSizeChanged { measuredHeaderPixels = it.height }.statusBarsPadding()) { header() }
    }
}

private fun gradientFrost(input: Bitmap, maxBlurPixels: Float): Bitmap {
    val width = minOf(320, input.width)
    val height = maxOf(1, input.height * width / input.width)
    val small = Bitmap.createScaledBitmap(input, width, height, true)
    val pixels = IntArray(width * height)
    small.getPixels(pixels, 0, width, 0, 0, width, height)
    // A body with an opaque base must yield an opaque snapshot. Never publish
    // an empty/partial GPU readback and expose sharp history behind the title.
    val opaque = pixels.all { it ushr 24 == 255 }
    if (!opaque) {
        if (small !== input) small.recycle()
        input.recycle()
        error("Incomplete backdrop frame")
    }
    val radius = maxOf(1, (maxBlurPixels * width / input.width).roundToInt())
    val output = progressiveBlur(pixels, width, height, radius)
    if (small !== input) small.recycle()
    input.recycle()
    return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
}
