package com.courier.pick.detection

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.courier.pick.model.DetectedObject
import com.courier.pick.model.NormalizedRect
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded YOLOv8n object detector running on ONNX Runtime (CPU EP).
 * Detections are returned normalized to the source bitmap (0..1).
 */
class YoloDetector(private val context: Context) {

    companion object {
        const val INPUT_SIZE = 640
        const val CONF_THRESHOLD = 0.45f
        const val NMS_THRESHOLD = 0.5f
        private const val MODEL_NAME = "models/yolov8n.onnx"

        private val COCO_LABELS = listOf(
            "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
            "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat","dog",
            "horse","sheep","cow","elephant","bear","zebra","giraffe","backpack","umbrella",
            "handbag","tie","suitcase","frisbee","skis","snowboard","sports ball","kite",
            "baseball bat","baseball glove","skateboard","surfboard","tennis racket","bottle",
            "wine glass","cup","fork","knife","spoon","bowl","banana","apple","sandwich","orange",
            "broccoli","carrot","hot dog","pizza","donut","cake","chair","couch","potted plant",
            "bed","dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone",
            "microwave","oven","toaster","sink","refrigerator","book","clock","vase","scissors",
            "teddy bear","hair drier","toothbrush"
        )
    }

    private var env: ai.onnxruntime.OrtEnvironment? = null
    private var session: ai.onnxruntime.OrtSession? = null
    private var floatBuf: FloatArray = FloatArray(1 * 3 * INPUT_SIZE * INPUT_SIZE)
    private var pixels: IntArray? = null
    private val loaded = AtomicBoolean(false)

    fun isLoaded(): Boolean = loaded.get()

    fun loadIfNeeded() {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            try {
                val file = copyAssetToCache(MODEL_NAME)
                env = ai.onnxruntime.OrtEnvironment.getEnvironment("YOLO")
                val opts = ai.onnxruntime.OrtSession.SessionOptions()
                opts.setIntraOpNumThreads(2)
                session = env?.createSession(file.absolutePath, opts)
                loaded.set(true)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    private fun copyAssetToCache(assetPath: String): File {
        val outFile = File(context.cacheDir, "yolov8n.onnx")
        if (outFile.exists() && outFile.length() > 1000) return outFile
        context.assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    /**
     * Runs detection on [softBitmap] (a downscaled working bitmap).
     * Returns boxes normalized to the bitmap dimensions, in the bitmap's own
     * orientation (caller rotates to display space if needed).
     */
    fun detect(softBitmap: Bitmap): List<DetectedObject> {
        loadIfNeeded()
        val env = env ?: return emptyList()
        val session = session ?: return emptyList()
        // Pre-downscale huge bitmaps (e.g. static photos) so we never allocate
        // multi-megapixel pixel arrays; YOLO works on a letterboxed 640px anyway.
        val src = if (maxOf(softBitmap.width, softBitmap.height) > 1280) {
            val s = 1280f / maxOf(softBitmap.width, softBitmap.height)
            Bitmap.createScaledBitmap(
                softBitmap,
                (softBitmap.width * s).toInt().coerceAtLeast(1),
                (softBitmap.height * s).toInt().coerceAtLeast(1),
                true
            )
        } else softBitmap
        val w = src.width
        val h = src.height
        val total = w * h
        if (pixels == null || pixels!!.size < total) pixels = IntArray(total)
        val px = pixels!!
        src.getPixels(px, 0, w, 0, 0, w, h)

        // --- letterbox to 640x640 ---
        val scale = INPUT_SIZE.toFloat() / maxOf(w, h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        val padX = ((INPUT_SIZE - newW) / 2f)
        val padY = ((INPUT_SIZE - newH) / 2f)

        val fb = floatBuf
        // NCHW layout: [3, 640, 640] with RGB channel planes (model expects channel-first).
        val plane = INPUT_SIZE * INPUT_SIZE
        for (y in 0 until INPUT_SIZE) {
            val fy = y - padY
            val sy = (fy / scale).toInt()
            val rowOk = fy >= 0 && sy in 0 until h
            for (x in 0 until INPUT_SIZE) {
                val fx = x - padX
                val sx = (fx / scale).toInt()
                val base = y * INPUT_SIZE + x
                if (rowOk && fx >= 0 && sx in 0 until w) {
                    val c = px[sy * w + sx]
                    fb[base] = ((c shr 16) and 0xFF) / 255f
                    fb[plane + base] = ((c shr 8) and 0xFF) / 255f
                    fb[2 * plane + base] = (c and 0xFF) / 255f
                } else {
                    // letterbox padding (Ultralytics default fill=114)
                    fb[base] = 114f / 255f
                    fb[plane + base] = 114f / 255f
                    fb[2 * plane + base] = 114f / 255f
                }
            }
        }

        // --- run inference ---
        val shape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        val det: FloatArray
        try {
            ai.onnxruntime.OnnxTensor.createTensor(env, FloatBuffer.wrap(fb), shape).use { input ->
                session.run(mapOf("images" to input)).use { result ->
                    val out = result.get(0) as ai.onnxruntime.OnnxTensor
                    val shapeArr = out.info.shape
                    var n = 1
                    for (i in 1 until shapeArr.size) n = n * shapeArr[i].toInt()
                    val buf = out.floatBuffer
                    det = FloatArray(n)
                    for (i in 0 until n) det[i] = buf.get(i)
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            return emptyList()
        }
        return postProcess(det, scale, padX, padY, w, h)
    }

    private fun postProcess(
        raw: FloatArray,
        scale: Float,
        padX: Float,
        padY: Float,
        w: Int,
        h: Int
    ): List<DetectedObject> {
        val anchors = 8400
        val numCls = 80
        val stride = anchors

        // Candidate boxes are collected in one pass; the box list index is decoupled
        // from the anchor index so NMS lookup never desyncs (previous bug: boxes[anchor]).
        val boxes = ArrayList<FloatArray>(64)
        val scores = ArrayList<Float>(64)
        val classes = ArrayList<Int>(64)

        for (j in 0 until anchors) {
            val cx = raw[j]
            val cy = raw[stride + j]
            val bw = raw[2 * stride + j]
            val bh = raw[3 * stride + j]
            var best = -1f
            var bestC = -1
            for (c in 0 until numCls) {
                val s = raw[(4 + c) * stride + j]
                if (s > best) { best = s; bestC = c }
            }
            if (best < CONF_THRESHOLD) continue
            boxes.add(floatArrayOf(
                cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f
            ))
            scores.add(best)
            classes.add(bestC)
        }
        if (boxes.isEmpty()) return emptyList()

        val keep = nms(scores, boxes)
        val out = ArrayList<DetectedObject>(keep.size)
        for (i in keep) {
            val b = boxes[i]
            val lab = if (classes[i] in COCO_LABELS.indices) COCO_LABELS[classes[i]].lowercase() else "object"
            val rect = letterboxToBitmapRect(b[0], b[1], b[2], b[3], scale, padX, padY, w, h) ?: continue
            out.add(DetectedObject(rect, lab, scores[i]))
        }
        return out
    }

    private fun letterboxToBitmapRect(
        x1: Float, y1: Float, x2: Float, y2: Float,
        scale: Float, padX: Float, padY: Float, w: Int, h: Int
    ): NormalizedRect? {
        var bx1 = (x1 - padX) / scale
        var by1 = (y1 - padY) / scale
        var bx2 = (x2 - padX) / scale
        var by2 = (y2 - padY) / scale
        if (bx2 < 0 || by2 < 0 || bx1 > w || by1 > h) return null
        bx1 = bx1.coerceIn(0f, w.toFloat())
        by1 = by1.coerceIn(0f, h.toFloat())
        bx2 = bx2.coerceIn(0f, w.toFloat())
        by2 = by2.coerceIn(0f, h.toFloat())
        if (bx2 - bx1 < 1f || by2 - by1 < 1f) return null
        return NormalizedRect(bx1 / w, by1 / h, bx2 / w, by2 / h)
    }

    private fun nms(scores: List<Float>, boxes: List<FloatArray>): List<Int> {
        val sorted = scores.indices.sortedByDescending { scores[it] }
        return simpleNms(sorted, boxes)
    }

    private fun simpleNms(sorted: List<Int>, boxes: List<FloatArray>): List<Int> {
        val keep = ArrayList<Int>()
        val used = BooleanArray(boxes.size)
        for (a in sorted) {
            if (used[a]) continue
            keep.add(a)
            for (b in sorted) {
                if (used[b]) continue
                if (a != b && iou(boxes[a], boxes[b]) > NMS_THRESHOLD) used[b] = true
            }
            used[a] = true
        }
        return keep
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix1 = maxOf(a[0], b[0]); val iy1 = maxOf(a[1], b[1])
        val ix2 = minOf(a[2], b[2]); val iy2 = minOf(a[3], b[3])
        val iw = maxOf(0f, ix2 - ix1); val ih = maxOf(0f, iy2 - iy1)
        val inter = iw * ih
        val areaA = (a[2] - a[0]) * (a[3] - a[1])
        val areaB = (b[2] - b[0]) * (b[3] - b[1])
        return inter / (areaA + areaB - inter + 1e-6f)
    }

    fun close() {
        session?.close()
        env?.close()
        loaded.set(false)
    }
}