package com.yijian.translate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

object Ink {
    val Blue = Color(0xFF526BC0)
    val Dark = Color(0xFF22283A)
    val Muted = Color(0xFF83899E)
    val Pale = Color(0xFFEDF0FB)
    val Line = Color(0xFFE9ECF5)
    val Green = Color(0xFF5B8C83)
    val Lavender = Color(0xFF9D9BCC)
    val Card = Color(0xFFFCFCFF)
}

/** Shape-aware painted shadow, independent of Android's projected elevation shadows. */
fun Modifier.softShadow(radius: Dp = 28.dp, strength: Float = .10f, extent: Dp = 9.dp) = drawBehind {
    for (step in 16 downTo 1) {
        val spread = step / 16f * extent.toPx()
        drawRoundRect(Color(0xFF475471).copy(alpha = strength * (1f - step / 18f) / 16f),
            Offset(-spread, 3.dp.toPx() - spread), Size(size.width + spread * 2, size.height + spread * 2),
            CornerRadius(radius.toPx() + spread))
    }
}

@Composable fun RelaxTranslatorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink.Blue, onPrimary = Color.White,
        background = Color(0xFFE9EEFC), surface = Color(0xFFF9FAFF), onSurface = Ink.Dark,
        onSurfaceVariant = Ink.Muted, outline = Ink.Line, secondaryContainer = Ink.Pale),
        typography = Typography(bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)),
        content = { CompositionLocalProvider(LocalContentColor provides Ink.Dark, content = content) })
}

@Composable fun MistBackground() {
    Canvas(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFE0E8FC), Color(0xFFEEF0FA), Color(0xFFF4F1FA))))) {
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .65f), Color.Transparent), Offset(size.width * .1f, size.height * .3f), size.width * .9f), size.width * .9f, Offset(size.width * .1f, size.height * .3f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFD3DFFB).copy(alpha = .55f), Color.Transparent), Offset(size.width, 0f), size.width), size.width, Offset(size.width, 0f))
    }
}

@Composable fun BrandMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_brand), contentDescription = stringResource(R.string.app_name), modifier = modifier)
}

@Composable fun BrandTitle(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.app_name), modifier = modifier, fontFamily = FontFamily.SansSerif,
        fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal,
        letterSpacing = .25.sp, color = Color(0xFF2D3448), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun makeVector(name: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        when (name) {
            "speaker" -> { moveTo(4f, 9f); lineTo(8f, 9f); lineTo(13f, 5f); lineTo(13f, 19f); lineTo(8f, 15f); lineTo(4f, 15f); close(); moveTo(17f, 8f); curveTo(20f, 10f, 20f, 14f, 17f, 16f); moveTo(20f, 5f); curveTo(25f, 9f, 25f, 15f, 20f, 19f) }
            "menu" -> { moveTo(5f, 6f); lineTo(19f, 6f); moveTo(5f, 12f); lineTo(19f, 12f); moveTo(5f, 18f); lineTo(15f, 18f) }
            "plus" -> { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
            "close" -> { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
            "up" -> { moveTo(7f, 14f); lineTo(12f, 9f); lineTo(17f, 14f) }
            "down" -> { moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f) }
            "back" -> { moveTo(19f, 12f); lineTo(5f, 12f); moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f) }
            "right" -> { moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f) }
            "check" -> { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f) }
            "send" -> { moveTo(12f, 19f); lineTo(12f, 5f); moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) }
            "stop" -> { moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close() }
            "image" -> { moveTo(5f, 4f); lineTo(19f, 4f); quadTo(21f, 4f, 21f, 6f); lineTo(21f, 18f); quadTo(21f, 20f, 19f, 20f); lineTo(5f, 20f); quadTo(3f, 20f, 3f, 18f); lineTo(3f, 6f); quadTo(3f, 4f, 5f, 4f); close(); moveTo(3f, 16f); lineTo(9f, 10f); lineTo(15f, 16f); lineTo(18f, 13f); lineTo(21f, 16f); moveTo(16f, 8f); lineTo(16.01f, 8f) }
            "globe" -> { moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); close(); moveTo(3f, 12f); lineTo(21f, 12f); moveTo(12f, 3f); curveTo(6f, 8f, 6f, 16f, 12f, 21f); curveTo(18f, 16f, 18f, 8f, 12f, 3f) }
            "model", "spark" -> { moveTo(12f, 3f); quadTo(12.7f, 10.6f, 20f, 12f); quadTo(12.7f, 13.4f, 12f, 21f); quadTo(11.3f, 13.4f, 4f, 12f); quadTo(11.3f, 10.6f, 12f, 3f); close(); moveTo(20f, 3f); lineTo(20f, 7f); moveTo(18f, 5f); lineTo(22f, 5f) }
            "context" -> { moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 15f); lineTo(14f, 15f); lineTo(9f, 19f); lineTo(9f, 15f); lineTo(4f, 15f); close(); moveTo(8f, 9f); lineTo(16f, 9f) }
            "copy" -> { moveTo(9f, 4f); lineTo(20f, 4f); lineTo(20f, 16f); lineTo(16f, 16f); moveTo(4f, 8f); lineTo(16f, 8f); lineTo(16f, 21f); lineTo(4f, 21f); close() }
            "edit" -> { moveTo(14f, 5f); lineTo(18f, 9f); moveTo(5f, 15f); lineTo(16f, 4f); quadTo(17f, 3f, 18f, 4f); lineTo(20f, 6f); quadTo(21f, 7f, 20f, 8f); lineTo(9f, 19f); lineTo(4f, 20f); close(); moveTo(13f, 20f); lineTo(20f, 20f) }
            "search" -> { moveTo(17f, 10f); arcTo(7f, 7f, 0f, true, true, 3f, 10f); arcTo(7f, 7f, 0f, true, true, 17f, 10f); moveTo(15f, 15f); lineTo(21f, 21f) }
            "clock" -> { moveTo(21f, 12f); arcTo(9f, 9f, 0f, true, true, 3f, 12f); arcTo(9f, 9f, 0f, true, true, 21f, 12f); moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f) }
            "book" -> { moveTo(12f, 6f); quadTo(7f, 3f, 3f, 5f); lineTo(3f, 19f); quadTo(7f, 17f, 12f, 20f); quadTo(17f, 17f, 21f, 19f); lineTo(21f, 5f); quadTo(17f, 3f, 12f, 6f); lineTo(12f, 20f) }
            "note" -> { moveTo(6f, 3f); lineTo(16f, 3f); lineTo(20f, 7f); lineTo(20f, 21f); lineTo(4f, 21f); lineTo(4f, 3f); close(); moveTo(9f, 10f); lineTo(15f, 10f); moveTo(9f, 15f); lineTo(15f, 15f) }
            "trash" -> { moveTo(4f, 6f); lineTo(20f, 6f); moveTo(9f, 6f); lineTo(9f, 3f); lineTo(15f, 3f); lineTo(15f, 6f); moveTo(6f, 6f); lineTo(7f, 21f); lineTo(17f, 21f); lineTo(18f, 6f); moveTo(10f, 10f); lineTo(10f, 17f); moveTo(14f, 10f); lineTo(14f, 17f) }
            "settings" -> { moveTo(12f, 3f); lineTo(15f, 5f); lineTo(19f, 5f); lineTo(21f, 9f); lineTo(19f, 12f); lineTo(21f, 15f); lineTo(19f, 19f); lineTo(15f, 19f); lineTo(12f, 21f); lineTo(9f, 19f); lineTo(5f, 19f); lineTo(3f, 15f); lineTo(5f, 12f); lineTo(3f, 9f); lineTo(5f, 5f); lineTo(9f, 5f); close(); moveTo(15f, 12f); arcTo(3f, 3f, 0f, true, true, 9f, 12f); arcTo(3f, 3f, 0f, true, true, 15f, 12f) }
            "download" -> { moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f); moveTo(4f, 16f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 16f) }
            "reload" -> { moveTo(19f, 7f); curveTo(15f, 1f, 4f, 3f, 4f, 12f); curveTo(4f, 21f, 17f, 25f, 21f, 14f); moveTo(19f, 3f); lineTo(19f, 8f); lineTo(14f, 8f) }
            else -> { moveTo(12f, 4f); lineTo(12f, 14f); moveTo(12f, 19f); lineTo(12.01f, 19f) }
        }
    }
}.build()

@Composable fun Glyph(name: String, modifier: Modifier = Modifier.size(22.dp), tint: Color = Ink.Dark, description: String? = null) {
    val vector = remember(name) { makeVector(name) }
    Icon(vector, contentDescription = description, modifier = modifier, tint = tint)
}

@Composable fun CircleAction(icon: String, description: String, modifier: Modifier = Modifier, tint: Color = Ink.Dark, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier.size(46.dp).softShadow(23.dp, .12f), shape = CircleShape,
        color = Color(0xFFF9FAFF)) {
        Box(contentAlignment = Alignment.Center) { Glyph(icon, tint = tint, description = description) }
    }
}

@Composable fun TinyTag(text: String, color: Color = Ink.Blue) {
    Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .085f)).padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable fun Pill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = if (selected) Ink.Blue else Ink.Pale,
        modifier = Modifier.heightIn(min = 38.dp)) {
        Box(Modifier.padding(horizontal = 15.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Color.White else Ink.Muted)
        }
    }
}

@Composable fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ink.Blue, disabledContainerColor = Ink.Blue.copy(alpha = .3f)),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) { Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }
}
