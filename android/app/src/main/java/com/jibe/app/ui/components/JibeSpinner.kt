package com.jibe.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.jibe.app.ui.theme.JibePrimary

private const val SPINNER_PERIOD_NS = 900_000_000L
private const val SPINNER_ARC_SWEEP_DEGREES = 260f

/**
 * Frame-clock arc spinner (smooth even when system animator duration scale is 0).
 */
@Composable
fun JibeSpinner(
        modifier: Modifier = Modifier,
        color: Color = JibePrimary,
        strokeWidth: Float = 3f
) {
        var angle by remember { mutableFloatStateOf(0f) }

        LaunchedEffect(Unit) {
                val startNs = withFrameNanos { it }
                while (true) {
                        withFrameNanos { frameNs ->
                                angle =
                                        ((frameNs - startNs) % SPINNER_PERIOD_NS).toFloat() /
                                                SPINNER_PERIOD_NS * 360f
                        }
                }
        }

        Canvas(modifier = modifier) {
                val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                val inset = strokeWidth / 2
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                drawArc(
                        color = color.copy(alpha = 0.15f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = stroke
                )
                drawArc(
                        color = color,
                        startAngle = angle,
                        sweepAngle = SPINNER_ARC_SWEEP_DEGREES,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = stroke
                )
        }
}
