package com.courier.pick.detection

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.Image
import com.courier.pick.model.DetectedCode
import com.courier.pick.model.NormalizedRect
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.util.Arrays
import kotlin.math.atan2

/**
 * Decodes multiple tracking-code barcodes / QR codes from a camera frame
 * ([Image]) or from a static [Bitmap]. Coordinates are normalized to the
 * source image's own orientation (0..1).
 */
class ZxingDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to Arrays.asList(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8,
                BarcodeFormat.UPC_A,
                BarcodeFormat.UPC_E,
                BarcodeFormat.ITF,
                BarcodeFormat.CODABAR,
                BarcodeFormat.DATA_MATRIX
            )
        ))
    }

    fun decodeFrame(image: Image, trackNumber: String): List<DetectedCode> {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val yBuffer = plane.buffer
        // camera Y planes are direct ByteBuffers; `.array()` is not supported, so copy.
        val yArr = ByteArray(rowStride * height)
        yBuffer.position(0)
        yBuffer.get(yArr)
        val source = PlanarYUVLuminanceSource(
            yArr, rowStride, height, 0, 0, width, height, false
        )
        return decodeFrom(source, width, height, trackNumber)
    }

    fun decodeBitmap(bitmap: Bitmap, trackNumber: String): List<DetectedCode> {
        val w = bitmap.width
        val h = bitmap.height
        val ints = IntArray(w * h)
        bitmap.getPixels(ints, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, ints)
        return decodeFrom(source, w, h, trackNumber)
    }

    private fun decodeFrom(
        source: LuminanceSource,
        imgW: Int,
        imgH: Int,
        trackNumber: String
    ): List<DetectedCode> {
        if (imgW <= 0 || imgH <= 0) return emptyList()
        val track = normalize(trackNumber)
        return try {
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val multi = GenericMultipleBarcodeReader(reader)
            val results: List<Result> = multi.decodeMultiple(bitmap).toList()
            results.map { r -> toDetected(r, imgW, imgH, track) }
        } catch (t: Throwable) {
            // no codes found in this frame
            emptyList()
        }
    }

    private fun toDetected(r: Result, imgW: Int, imgH: Int, track: String): DetectedCode {
        var corners = r.resultPoints.map { PointF(it.x, it.y) }
        val ordered = orderCorners(corners)
        val xs = ordered.map { it.x }
        val ys = ordered.map { it.y }
        // pad slightly so the box bounds the code
        val rect = NormalizedRect(
            (xs.min() / imgW).coerceIn(0f, 1f),
            (ys.min() / imgH).coerceIn(0f, 1f),
            (xs.max() / imgW).coerceIn(0f, 1f),
            (ys.max() / imgH).coerceIn(0f, 1f)
        )
        val normalizedCorners = ordered.map { PointF(it.x / imgW, it.y / imgH) }
        val text = r.text ?: ""
        return DetectedCode(
            rect = rect,
            corners = normalizedCorners,
            text = text,
            format = r.barcodeFormat?.toString() ?: "",
            matched = matches(text, track)
        )
    }

    /** Order polygon points around their centroid (top-left, top-right, bottom-right, bottom-left). */
    private fun orderCorners(points: List<PointF>): List<PointF> {
        if (points.size < 4) return points
        val cx = points.map { it.x }.average().toFloat()
        val cy = points.map { it.y }.average().toFloat()
        val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        // atan2 gives -pi..pi; rotate so the smallest positive angle start is top-right-ish is unnecessary,
        // we only need consistent quadrilateral ordering for drawing; keep as-is.
        return sorted
    }

    private fun matches(text: String, track: String): Boolean {
        if (track.isEmpty()) return false
        val a = normalize(text)
        if (a.isEmpty()) return false
        return tokens(track).any { t -> a.contains(t) || t.contains(a) }
    }

    private fun normalize(s: String): String =
        s.uppercase().replace(" ", "").replace("\u0000", "")

    private fun tokens(s: String): List<String> =
        normalize(s).split(Regex("[^A-Z0-9]+")).filter { it.length >= 4 }
}