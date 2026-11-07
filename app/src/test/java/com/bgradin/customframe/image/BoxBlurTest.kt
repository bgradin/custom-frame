package com.bgradin.customframe.image

import org.junit.Assert.*
import org.junit.Test

class BoxBlurTest {
    @Test fun uniformColorAndAlphaArePreservedIncludingTinyImages() {
        for ((width, height) in listOf(1 to 1, 1 to 9, 7 to 1, 5 to 7)) {
            val pixels = IntArray(width * height) { 0x8040a0ff.toInt() }
            boxBlur(pixels, width, height, 12)
            assertTrue(pixels.all { it == 0x8040a0ff.toInt() })
        }
    }

    @Test fun zeroRadiusPreservesInput() {
        val pixels = intArrayOf(1, 2, 3, 4)
        boxBlur(pixels, 2, 2, 0)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), pixels)
    }

    @Test fun edgeClampingAndBothAxesMatchNaiveReference() {
        val width = 5
        val height = 3
        val original = IntArray(width * height) { index ->
            ((index * 13) shl 24) or ((index * 11) shl 16) or ((index * 7) shl 8) or (index * 17)
        }
        val actual = original.copyOf()
        boxBlur(actual, width, height, 2, passes = 2)
        var expected = original.copyOf()
        repeat(2) {
            for (horizontal in listOf(true, false)) {
                val input = expected
                expected = IntArray(input.size) { index ->
                    val x = index % width
                    val y = index / width
                    var color = 0
                    for (shift in listOf(0, 8, 16, 24)) {
                        var sum = 0
                        for (offset in -2..2) {
                            val sx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                            val sy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                            sum += input[sy * width + sx] ushr shift and 255
                        }
                        color = color or ((sum / 5) shl shift)
                    }
                    color
                }
            }
        }
        assertArrayEquals(expected, actual)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedDimensions() { boxBlur(IntArray(6), 2, 2, 1) }
}
