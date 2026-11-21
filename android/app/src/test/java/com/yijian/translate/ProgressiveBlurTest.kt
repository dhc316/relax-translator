package com.yijian.translate

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ProgressiveBlurTest {
    @Test fun contrastRisesSmoothlyTowardTheClearBottom() {
        val width = 144; val height = 144
        val input = IntArray(width * height) { p -> if (p % width / 3 % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val output = progressiveBlur(input, width, height, 14)
        fun contrast(row: Int) = (16 until width - 16).sumOf { x -> abs((output[row * width + x] and 255) - (output[row * width + x - 1] and 255)) }
        assertTrue(contrast(8) < contrast(height - 1))
        assertTrue(contrast(height / 2) < contrast(height - 1))
        assertArrayEquals(input.copyOfRange(width * (height - 1), input.size), output.copyOfRange(width * (height - 1), output.size))
    }
    @Test fun blurKeepsUniformColorsAndTransparentChannelsWithoutDarkEdges() {
        val color = 0x80B2C3D4.toInt()
        assertTrue(progressiveBlur(IntArray(80 * 60) { color }, 80, 60, 20).all { it == color })
    }
    @Test fun zeroRadiusPreservesExactPixelsAndSmallImagesAreValid() {
        val input = intArrayOf(0xFF001122.toInt(), 0xCC334455.toInt(), 0x80667788.toInt(), 0x0099AABB)
        assertArrayEquals(input, progressiveBlur(input, 2, 2, 0))
        assertEquals(0xFFFFFFFF.toInt(), progressiveBlur(intArrayOf(0xFFFFFFFF.toInt()), 1, 1, 20).single())
    }
}
