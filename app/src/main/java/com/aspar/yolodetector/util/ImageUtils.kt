package com.aspar.yolodetector.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

object ImageUtils {

    /** دیکد عکس از گالری با اعمال چرخش EXIF و کوچک‌سازی تا [maxSide] (بیت‌مپ نرم‌افزاری). */
    fun decode(cr: ContentResolver, uri: Uri, maxSide: Int = 1280): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(cr, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // بیت‌مپ HARDWARE را نمی‌شود روی Canvas نرم‌افزاری کشید
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val longest = max(w, h)
                if (longest > maxSide) {
                    val f = maxSide.toFloat() / longest
                    decoder.setTargetSize((w * f).toInt(), (h * f).toInt())
                }
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("دیکد تصویر ناموفق بود")
        val rotation = cr.openInputStream(uri).use { stream ->
            if (stream == null) 0 else ExifInterface(stream).rotationDegrees
        }
        return bmp.rotate(rotation)
    }
}

fun Bitmap.rotate(degrees: Int): Bitmap {
    if (degrees % 360 == 0) return this
    val m = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
}
