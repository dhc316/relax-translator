package com.yijian.translate

import kotlin.math.roundToInt

/** A row's blur radius falls linearly to zero at the bottom; no fixed-radius bands. */
fun progressiveBlur(pixels: IntArray, width: Int, height: Int, maxRadius: Int): IntArray {
    require(width > 0 && height > 0 && pixels.size == width * height)
    val radii = IntArray(height) { y -> (maxRadius.coerceAtLeast(0) * (1f - y.toFloat() / maxOf(1, height - 1))).roundToInt() }
    var result = pixels.copyOf()
    repeat(3) { result = blurPass(blurPass(result, width, height, radii, true), width, height, radii, false) }
    return result
}

private fun blurPass(input: IntArray, width: Int, height: Int, radii: IntArray, horizontal: Boolean): IntArray {
    val output = IntArray(input.size)
    val lines = if (horizontal) height else width
    val length = if (horizontal) width else height
    val a = IntArray(length + 1); val r = IntArray(length + 1); val g = IntArray(length + 1); val b = IntArray(length + 1)
    fun index(line: Int, p: Int) = if (horizontal) line * width + p else p * width + line
    for (line in 0 until lines) {
        for (p in 0 until length) {
            val value = input[index(line, p)]
            a[p + 1] = a[p] + (value ushr 24); r[p + 1] = r[p] + (value ushr 16 and 255)
            g[p + 1] = g[p] + (value ushr 8 and 255); b[p + 1] = b[p] + (value and 255)
        }
        for (p in 0 until length) {
            val radius = radii[if (horizontal) line else p]
            val start = maxOf(0, p - radius); val end = minOf(length, p + radius + 1); val n = end - start
            output[index(line, p)] = (((a[end] - a[start]) / n) shl 24) or (((r[end] - r[start]) / n) shl 16) or
                (((g[end] - g[start]) / n) shl 8) or ((b[end] - b[start]) / n)
        }
    }
    return output
}
