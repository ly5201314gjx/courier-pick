package com.courier.pick.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import java.io.InputStream
import java.nio.ByteBuffer

/** Decodes a picked photo into a bounded bitmap for static recognition. */
object ImageLoader {
    private const val MAX_SIDE = 1920

    fun decode(stream: InputStream): Bitmap? {
        val bytes = stream.readBytes()
        return if (Build.VERSION.SDK_INT >= 28) {
            try {
                val src = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(src)
            } catch (_: Throwable) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }?.let { bound(it) }
    }

    private fun bound(src: Bitmap): Bitmap {
        val max = maxOf(src.width, src.height)
        if (max <= MAX_SIDE) return src
        val scale = MAX_SIDE.toFloat() / max
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}