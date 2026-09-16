package com.courier.pick.util

import com.courier.pick.model.NormalizedRect
import kotlin.math.roundToInt

/**
 * Maps detection coordinates from the raw camera buffer space (0..1, unrotated)
 * into display space (0..1, already rotated to match the preview) given the
 * image's rotation in degrees.
 */
object Transform {

    fun rotateYaw(degrees: Int, nx: Float, ny: Float): Pair<Float, Float> {
        return when (degrees) {
            90 -> Pair(1f - ny, nx)
            180 -> Pair(1f - nx, 1f - ny)
            270 -> Pair(ny, 1f - nx)
            else -> Pair(nx, ny)
        }
    }

    fun rotateRect(degrees: Int, rect: NormalizedRect): NormalizedRect {
        val (x0, y0) = rotateYaw(degrees, rect.x0, rect.y0)
        val (x1, y1) = rotateYaw(degrees, rect.x1, rect.y1)
        return NormalizedRect(
            minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1)
        )
    }

    /** Convert camera buffer (rx * ry) plus pad-top to a letterboxed 640 rect. Kept for completeness. */
    fun fitRect(x1: Float, y1: Float, x2: Float, y2: Float, sw: Int, sh: Int): NormalizedRect {
        val n = NormalizedRect(
            (x1 / sw).coerceIn(0f, 1f),
            (y1 / sh).coerceIn(0f, 1f),
            (x2 / sw).coerceIn(0f, 1f),
            (y2 / sh).coerceIn(0f, 1f)
        )
        return n
    }

    fun prettyConf(c: Float): String = "${(c * 100).roundToInt()}%"
}