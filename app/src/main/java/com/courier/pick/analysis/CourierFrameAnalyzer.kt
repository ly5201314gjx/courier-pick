package com.courier.pick.analysis

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.Image
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.courier.pick.detection.YoloDetector
import com.courier.pick.detection.ZxingDecoder
import com.courier.pick.model.Algorithm
import com.courier.pick.model.DetectedCode
import com.courier.pick.model.DetectedObject
import com.courier.pick.model.FrameResult
import com.courier.pick.model.NormalizedRect
import com.courier.pick.util.Transform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * CameraX analyzer that runs the full pipeline each accepted frame, switching on [algorithmFlow]:
 * - COURIER: ZXing barcode decode (every frame) + YOLO object detection (throttled)
 * - PERSON : YOLO only, keeps person detections for head/person counting (amber boxes + count)
 * - BARCODE: ZXing only (fastest, maximum barcode throughput)
 * Results (already rotated into display space 0..1) are published to [result].
 */
class CourierFrameAnalyzer(
    private val yolo: YoloDetector,
    private val zxing: ZxingDecoder,
    private val yoloIntervalMs: Long = 200L,
    private val maxCodes: Int = 6,
    private val maxObjects: Int = 12
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)
    private var lastYolo = 0L
    private val _result = MutableStateFlow(FrameResult())
    val result: StateFlow<FrameResult> = _result
    /** The user-entered tracking number, pushed by the UI layer. */
    val trackFlow = MutableStateFlow("")
    /** The active recognition engine, driven by the UI. */
    val algorithmFlow = MutableStateFlow(Algorithm.COURIER)

    // FPS (exponential moving average) + processing latency, surfaced on the live stats bar.
    private var lastPublishMillis = 0L
    private var fpsEma = 0f

    private var rgbPixels: IntArray = IntArray(0)

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        try {
            val started = System.currentTimeMillis()
            val image = imageProxy.image ?: return
            val rotation = imageProxy.imageInfo.rotationDegrees
            val now = System.currentTimeMillis()
            val runYolo = now - lastYolo >= yoloIntervalMs
            val algo = algorithmFlow.value

            val bufferW = image.width
            val bufferH = image.height
            if (bufferW <= 0 || bufferH <= 0) return

            // 1) decode tracking codes (skipped in PERSON mode for speed)
            var codes: List<DetectedCode> = emptyList()
            if (algo != Algorithm.PERSON) {
                try {
                    codes = zxing.decodeFrame(image, "")
                } catch (_: Throwable) {
                }
            }

            // 2) YOLO object / person detection (throttled; skipped in BARCODE mode)
            var objects: List<DetectedObject> = emptyList()
            if (runYolo && algo != Algorithm.BARCODE) {
                lastYolo = now
                try {
                    val thumb = buildRgbThumbnail(image, maxSide = 640)
                    objects = yolo.detect(thumb).take(maxObjects)
                    if (algo == Algorithm.PERSON) {
                        objects = objects.filter { it.label.contains("person") }
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }

            // rotate everything into display space (0..1)
            val rotatedCodes = codes
                .map { rotateCode(it, rotation) }
                .take(maxCodes)
            val rotatedObjects = objects
                .map { DetectedObject(Transform.rotateRect(rotation, it.rect), it.label, it.confidence) }
                .take(maxObjects)

            val track = trackFlow.value
            val mapped = rotatedCodes.map {
                if (it.matched) it else it.copy(matched = matchesText(it.text, track))
            }
            val aspect = if (rotation == 90 || rotation == 270) {
                bufferH.toFloat() / bufferW
            } else bufferW.toFloat() / bufferH

            // FPS + latency
            val latency = System.currentTimeMillis() - started
            if (lastPublishMillis > 0) {
                val dt = started - lastPublishMillis
                if (dt > 0) fpsEma = if (fpsEma <= 0f) 1000f / dt
                else fpsEma * 0.8f + (1000f / dt) * 0.2f
            }
            lastPublishMillis = started

            _result.value = FrameResult(
                codes = mapped,
                objects = rotatedObjects,
                trackNumber = track,
                visualAspect = aspect,
                latencyMs = latency,
                fps = fpsEma,
                ts = started
            )
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            imageProxy.close()
            busy.set(false)
        }
    }

    private fun matchesText(text: String, track: String): Boolean {
        if (track.isBlank()) return false
        val a = normalize(text)
        if (a.isEmpty()) return false
        return tokens(track).any { t -> a.contains(t) || t.contains(a) }
    }

    private fun normalize(s: String): String = s.uppercase().replace(" ", "")

    private fun tokens(s: String): List<String> =
        normalize(s).split(Regex("[^A-Z0-9]+")).filter { it.length >= 4 }

    private fun rotateCode(code: DetectedCode, rotation: Int): DetectedCode {
        if (rotation == 0) return code
        val newCorners = code.corners.map { p ->
            val (x, y) = Transform.rotateYaw(rotation, p.x, p.y)
            PointF(x, y)
        }
        val nx = newCorners.map { it.x }
        val ny = newCorners.map { it.y }
        val rect = NormalizedRect(
            nx.min().coerceIn(0f, 1f), ny.min().coerceIn(0f, 1f),
            nx.max().coerceIn(0f, 1f), ny.max().coerceIn(0f, 1f)
        )
        return code.copy(rect = rect, corners = newCorners)
    }

    /** Build a reusable RGB thumbnail from the YUV frame, preserving buffer orientation. */
    private fun buildRgbThumbnail(image: Image, maxSide: Int): Bitmap {
        val bw = image.width
        val bh = image.height
        val targetW: Int
        var targetH: Int
        if (bw >= bh) {
            targetW = maxSide
            targetH = ((bh.toLong() * maxSide) / bw).toInt().coerceAtLeast(1)
        } else {
            targetH = maxSide
            targetW = ((bw.toLong() * maxSide) / bh).toInt().coerceAtLeast(1)
        }
        val need = targetW * targetH
        if (rgbPixels.size < need) {
            rgbPixels = IntArray(need)
        }
        fillYuvToArgb(image, rgbPixels, targetW, targetH)
        return Bitmap.createBitmap(rgbPixels, targetW, targetH, Bitmap.Config.ARGB_8888)
    }

    private fun fillYuvToArgb(image: Image, out: IntArray, tW: Int, tH: Int) {
        val planes = image.planes
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val yRow = yPlane.rowStride
        val uRow = uPlane.rowStride
        val vRow = vPlane.rowStride
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uPixel = uPlane.pixelStride
        val vPixel = vPlane.pixelStride
        val w = image.width
        val h = image.height
        val ySize = yRow * h
        val yArr = ByteArray(ySize)
        yBuf.position(0); yBuf.get(yArr)
        val maxU = uRow * (h shr 1)
        val maxV = vRow * (h shr 1)
        val uArr = ByteArray(maxU)
        val vArr = ByteArray(maxV)
        uBuf.position(0); uBuf.get(uArr)
        vBuf.position(0); vBuf.get(vArr)

        for (ty in 0 until tH) {
            val sy = (ty.toFloat() * h / tH).toInt().coerceIn(0, h - 1)
            for (tx in 0 until tW) {
                val sx = (tx.toFloat() * w / tW).toInt().coerceIn(0, w - 1)
                val yv = yArr[sy * yRow + sx].toInt() and 0xFF
                val sx2 = (sx shr 1).coerceIn(0, (w shr 1) - 1)
                val sy2 = (sy shr 1).coerceIn(0, (h shr 1) - 1)
                val uv = (uArr[sy2 * uRow + sx2 * uPixel].toInt() and 0xFF) - 128
                val vv = (vArr[sy2 * vRow + sx2 * vPixel].toInt() and 0xFF) - 128
                out[ty * tW + tx] = yuvToArgb(yv, uv, vv)
            }
        }
    }

    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        var r = (y + 1.402f * v)
        var g = (y - 0.344136f * u - 0.714136f * v)
        var b = (y + 1.772f * u)
        r = if (r < 0) 0f else if (r > 255) 255f else r
        g = if (g < 0) 0f else if (g > 255) 255f else g
        b = if (b < 0) 0f else if (b > 255) 255f else b
        return (0xFF shl 24) or (r.roundToInt() shl 16) or (g.roundToInt() shl 8) or b.roundToInt()
    }
}