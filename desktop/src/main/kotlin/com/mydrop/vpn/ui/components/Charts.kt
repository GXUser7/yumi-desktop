package com.mydrop.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/*
 * The phone's two traces, unchanged. Both draw the same way — horizontal-tangent cubics through
 * the samples — because the smoothing a spline would give overshoots, and on a rate chart an
 * overshoot draws throughput that never happened.
 */

/**
 * Rolling history of download/upload rates.
 *
 * Both series share one vertical scale so their relative magnitude stays honest — upload is
 * typically a fraction of download, and normalising each series independently would make a
 * trickle of ACKs look like a matching stream.
 */
@Composable
fun TrafficSparkline(
    downloadHistory: List<Long>,
    uploadHistory: List<Long>,
    downloadColor: Color,
    uploadColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        if (downloadHistory.size < 2) return@Canvas

        val peak = maxOf(
            downloadHistory.maxOrNull() ?: 0L,
            uploadHistory.maxOrNull() ?: 0L,
            1L,
        ).toFloat()

        fun pointsOf(series: List<Long>): List<Offset> {
            val stepX = size.width / (series.size - 1).coerceAtLeast(1)
            return series.mapIndexed { index, value ->
                Offset(index * stepX, size.height - (value / peak) * size.height * 0.92f)
            }
        }

        val downloadPoints = pointsOf(downloadHistory)
        val downloadPath = smoothPath(downloadPoints)

        val fillPath = Path().apply {
            addPath(downloadPath)
            lineTo(downloadPoints.last().x, size.height)
            lineTo(downloadPoints.first().x, size.height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(downloadColor.copy(alpha = 0.28f), downloadColor.copy(alpha = 0f)),
            ),
        )
        drawPath(downloadPath, color = downloadColor, style = Stroke(width = 2.dp.toPx()))

        if (uploadHistory.size >= 2) {
            drawPath(
                path = smoothPath(pointsOf(uploadHistory)),
                color = uploadColor,
                style = Stroke(width = 1.5f.dp.toPx()),
            )
        }
    }
}

/**
 * The live trace: every sample of the phase in flight, drawn as it arrives.
 *
 * A dial shows the present moment and nothing else, so a link that started fast and collapsed
 * halfway through looks exactly like one that was always mediocre. The trace keeps the shape of
 * the measurement, which is often the interesting part: a proxy that bursts and stalls draws a
 * comb, a healthy one draws a plateau.
 */
@Composable
fun SpeedTrace(
    samples: List<Long>,
    accent: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
    /**
     * A live trace advances across a fixed window and marks its head; a finished one is a record
     * of a phase that is over, so it fills the width and has no present moment to point at.
     */
    live: Boolean = true,
) {
    Canvas(modifier) {
        val baseline = size.height

        // A resting grid line, so an empty chart still reads as a chart rather than as a gap.
        drawLine(
            color = gridColor,
            start = Offset(0f, baseline),
            end = Offset(size.width, baseline),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
        )
        if (samples.size < 2) return@Canvas

        val peak = (samples.maxOrNull() ?: 1L).coerceAtLeast(1L).toFloat()
        val span = if (live) WINDOW else samples.size
        val step = size.width / (span - 1).coerceAtLeast(1).toFloat()

        val points = samples.mapIndexed { index, value ->
            Offset(index * step, baseline - (value / peak) * size.height * 0.88f)
        }

        val line = smoothPath(points)
        val fill = Path().apply {
            addPath(line)
            lineTo(points.last().x, baseline)
            lineTo(points.first().x, baseline)
            close()
        }
        drawPath(
            path = fill,
            brush = Brush.verticalGradient(
                listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0f)),
            ),
        )
        drawPath(line, color = accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        // The head of the trace, so the eye finds the present moment without hunting for it.
        if (live) drawCircle(color = accent, radius = 3.dp.toPx(), center = points.last())
    }
}

/** Samples on screen at once: at 150 ms each this is the nine seconds a phase lasts. */
private const val WINDOW = 60

private fun smoothPath(points: List<Offset>): Path = Path().apply {
    moveTo(points.first().x, points.first().y)
    for (index in 1 until points.size) {
        val previous = points[index - 1]
        val current = points[index]
        val midX = (previous.x + current.x) / 2f
        cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
    }
}

/** Fixed-length rolling window of the most recent samples. */
class RateHistory(private val capacity: Int = 48) {
    private val samples = mutableStateListOf<Long>()

    val values: List<Long> get() = samples

    fun push(value: Long) {
        samples.add(value)
        while (samples.size > capacity) samples.removeAt(0)
    }

    fun clear() = samples.clear()
}

@Composable
fun rememberRateHistory(capacity: Int = 48): RateHistory = remember { RateHistory(capacity) }
