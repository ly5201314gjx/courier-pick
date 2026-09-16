package com.courier.pick.model

import android.graphics.PointF

/** Selectable recognition engine preset. */
enum class Algorithm(val label: String) {
    COURIER("快递找件"),
    PERSON("人头计数"),
    BARCODE("条码聚焦")
}

/** One expandable processing step surfaced under static analysis. */
data class AnalysisLog(
    val step: String,
    val detail: String = "",
    val elapsedMs: Long = 0,
    val ok: Boolean = true,
    val ts: Long = System.currentTimeMillis()
)

/** A normalized rectangle in display space (0..1). */
data class NormalizedRect(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width: Float get() = x1 - x0
    val height: Float get() = y1 - y0
    fun mapWidth(w: Float): Float = (x1 - x0) * w
    fun mapHeight(h: Float): Float = (y1 - y0) * h
}

/** A barcode / QR / tracking code found by ZXing. */
data class DetectedCode(
    val rect: NormalizedRect,
    val corners: List<PointF>, // normalized corners, for nicer polygon drawing
    val text: String,
    val format: String,
    val matched: Boolean
)

/** An object detected by the embedded YOLO neural network. */
data class DetectedObject(
    val rect: NormalizedRect,
    val label: String,
    val confidence: Float
)

/** A persisted scan history / favorite entry. */
data class ScanEntry(
    val id: Long,
    val ts: Long,
    val title: String,
    val detail: String,
    val matched: Boolean,
    val algorithm: String
)

/** Result emitted by the analyzer for one frame. */
data class FrameResult(
    val codes: List<DetectedCode> = emptyList(),
    val objects: List<DetectedObject> = emptyList(),
    val trackNumber: String = "",
    /** aspect ratio (w/h) of the visual/displayed image, used to center-crop overlay. */
    val visualAspect: Float = 1f,
    val latencyMs: Long = 0,
    val fps: Float = 0f,
    val ts: Long = System.currentTimeMillis()
) {
    val matched: Boolean get() = codes.any { it.matched }
    val matchedCodes: List<DetectedCode> get() = codes.filter { it.matched }
    val anyCode: Boolean get() = codes.isNotEmpty()
    val persons: List<DetectedObject> get() = objects.filter { it.label.contains("person") }
    val personCount: Int get() = persons.size
    val courierObjects: List<DetectedObject> get() = objects.filter { !it.label.contains("person") }
}