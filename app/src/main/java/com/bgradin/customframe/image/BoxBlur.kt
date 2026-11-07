package com.bgradin.customframe.image

/** Separable box blur with clamped edges. Runs on the small background image, off the UI thread. */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int = 3) {
    require(width > 0 && height > 0 && width.toLong() * height == pixels.size.toLong())
    require(radius in 0..100 && passes in 1..10)
    if (radius == 0) return
    val scratch = IntArray(pixels.size)
    repeat(passes) {
        blurAxis(pixels, scratch, width, height, radius, horizontal = true)
        blurAxis(scratch, pixels, width, height, radius, horizontal = false)
    }
}

private fun blurAxis(source: IntArray, destination: IntArray, width: Int, height: Int,
                     radius: Int, horizontal: Boolean) {
    val length = if (horizontal) width else height
    val lines = if (horizontal) height else width
    val stride = if (horizontal) 1 else width
    val window = radius * 2 + 1
    for (line in 0 until lines) {
        val base = if (horizontal) line * width else line
        var alpha = 0
        var red = 0
        var green = 0
        var blue = 0
        for (offset in -radius..radius) {
            val color = source[base + offset.coerceIn(0, length - 1) * stride]
            alpha += color ushr 24
            red += color ushr 16 and 255
            green += color ushr 8 and 255
            blue += color and 255
        }
        for (position in 0 until length) {
            destination[base + position * stride] = ((alpha / window) shl 24) or
                ((red / window) shl 16) or ((green / window) shl 8) or (blue / window)
            val leaving = source[base + (position - radius).coerceIn(0, length - 1) * stride]
            val entering = source[base + (position + radius + 1).coerceIn(0, length - 1) * stride]
            alpha += (entering ushr 24) - (leaving ushr 24)
            red += (entering ushr 16 and 255) - (leaving ushr 16 and 255)
            green += (entering ushr 8 and 255) - (leaving ushr 8 and 255)
            blue += (entering and 255) - (leaving and 255)
        }
    }
}
