package com.bgradin.customframe.image

import android.graphics.Bitmap
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import java.security.MessageDigest

/** Avoid RenderScript context allocation accounting on Android 7. Glide runs this on its decoder. */
class SoftwareBlurTransformation : BitmapTransformation() {
    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val width = toTransform.width
        val height = toTransform.height
        val pixels = IntArray(width * height)
        toTransform.getPixels(pixels, 0, width, 0, 0, width, height)
        // Three box passes approximate a Gaussian blur at the quarter-resolution background size.
        boxBlur(pixels, width, height, radius = 12)
        return pool.get(width, height, Bitmap.Config.ARGB_8888).apply {
            density = toTransform.density
            setHasAlpha(toTransform.hasAlpha())
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    override fun equals(other: Any?) = other is SoftwareBlurTransformation
    override fun hashCode() = ID.hashCode()
    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val ID = "com.bgradin.customframe.image.SoftwareBlurTransformation.radius12.passes3.v1"
    }
}
