package com.yijian.translate

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class GradientBackdropTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualBackdropBlursStronglyAtTopButPreservesContrastAndColorBelow() {
        compose.setContent {
            RelaxTranslatorTheme {
                FrostedPage(0, header = { Box(Modifier.height(240.dp)) }) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color.White)
                        val stripe = 8.dp.toPx()
                        var x = 0f
                        while (x < size.width) {
                            drawRect(Color.Black, Offset(x, 0f), Size(stripe, size.height))
                            x += stripe * 2
                        }
                    }
                }
            }
        }
        compose.waitUntil(15000) { compose.onNodeWithTag("glassBackdrop", useUnmergedTree = true).fetchSemanticsNode().config[BackdropBlurReady] }
        val glass = compose.onNodeWithTag("gradientBackdrop", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        fun statistics(y: Int): Pair<Double, Double> {
            val values = (bitmap.width / 5 until bitmap.width * 4 / 5).map { x -> (bitmap.getPixel(x, y) shr 16 and 255).toDouble() }
            val mean = values.average()
            return mean to sqrt(values.map { (it - mean) * (it - mean) }.average())
        }
        val top = statistics((glass.top + glass.height * .15f).toInt())
        val bottom = statistics((glass.bottom - 2).toInt())
        val outside = statistics((glass.bottom + 30).toInt())
        assertTrue("top must actually blur, not just tint the stripes: top=$top bottom=$bottom", top.second < bottom.second * .4)
        assertTrue("the blur must fade back to a sharp lower edge: bottom=$bottom outside=$outside", bottom.second > outside.second * .8)
        assertTrue("a pale opaque veil must not wash out the backdrop: $top", top.first in 110.0..165.0)
        assertTrue("normal content below the toolbar must stay sharp: $outside", outside.second > 110)
        bitmap.recycle()
    }
}
