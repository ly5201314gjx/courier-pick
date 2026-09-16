package com.courier.pick.ui.overlay

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.shape.RoundedCornerShape
import com.courier.pick.model.DetectedCode
import com.courier.pick.model.DetectedObject
import com.courier.pick.model.FrameResult
import com.courier.pick.ui.theme.OutlineActive
import com.courier.pick.ui.theme.OutlineCode
import com.courier.pick.ui.theme.OutlineObject
import com.courier.pick.ui.theme.OutlinePerson

/**
 * Draws live detection marks on top of the (already transformed) camera preview.
 * - Matched tracking code -> pulsing hot-red outlined box
 * - Other detected codes -> blue box
 * - YOLO objects -> green translucent box
 */
@Composable
fun DetectionOverlay(
    result: FrameResult,
    modifier: Modifier = Modifier
) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing)
        ),
        label = "pulse"
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val A = result.visualAspect.coerceAtLeast(0.01f)
        val canvasA = w / h
        val scaleW: Float
        val scaleH: Float
        val offX: Float
        val offY: Float
        if (canvasA > A) {
            scaleW = h * A; scaleH = h; offX = (w - h * A) / 2f; offY = 0f
        } else {
            scaleW = w; scaleH = w / A; offX = 0f; offY = (h - w / A) / 2f
        }
        val px: (Float) -> Float = { nx -> nx * scaleW + offX }
        val py: (Float) -> Float = { ny -> ny * scaleH + offY }
        // YOLO objects (under): persons drawn in amber, other objects in green
        result.objects.forEach { obj ->
            val isPerson = obj.label.contains("person")
            val color = if (isPerson) OutlinePerson.copy(alpha = 0.30f)
                        else OutlineObject.copy(alpha = 0.30f)
            drawRect(
                color = color,
                topLeft = Offset(px(obj.rect.x0), py(obj.rect.y0)),
                size = androidx.compose.ui.geometry.Size(obj.rect.width * scaleW, obj.rect.height * scaleH)
            )
            // Person counting: highlight head region with a brighter amber outline
            if (isPerson && result.personCount > 0) {
                drawRect(
                    color = OutlinePerson,
                    topLeft = Offset(px(obj.rect.x0), py(obj.rect.y0)),
                    size = androidx.compose.ui.geometry.Size(obj.rect.width * scaleW, obj.rect.height * scaleH * 0.42f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                )
            }
        }
        // All codes
        result.codes.forEach { code ->
            drawCode(code, px, py, pulse)
        }
    }
}

private fun DrawScope.drawCode(
    code: DetectedCode,
    px: (Float) -> Float,
    py: (Float) -> Float,
    pulse: Float
) {
    val color = if (code.matched) OutlineActive.copy(alpha = pulse) else OutlineCode
    val points = code.corners
    if (points.size >= 3) {
        val path = Path()
        var first = true
        points.forEach { p ->
            val o = Offset(px(p.x), py(p.y))
            if (first) {
                path.moveTo(o.x, o.y)
                first = false
            } else path.lineTo(o.x, o.y)
        }
        path.close()
        val stroke = if (code.matched) 4.5f else 3f
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = stroke, cap = StrokeCap.Round
        ))
    } else {
        drawRect(
            color = color,
            topLeft = Offset(px(code.rect.x0), py(code.rect.y0)),
            size = androidx.compose.ui.geometry.Size(
                code.rect.width * (px(1f) - px(0f)),
                code.rect.height * (py(1f) - py(0f))
            ),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
        )
    }
}