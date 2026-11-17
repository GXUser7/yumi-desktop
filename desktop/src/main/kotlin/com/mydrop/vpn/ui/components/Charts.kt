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
    val downloadPath = remember { Path() }
    val fillPath = remember { Path() }
    val uploadPath = remember { Path() }

    Canvas(modifier) {
        if (downloadHistory.size < 2) return@Canvas

        val peak = maxOf(
            downloadHistory.maxOrNull() ?: 0L,
            uploadHistory.maxOrNull() ?: 0L,
            1L,
        ).toFloat()

        val downStepX = size.width / (downloadHistory.size - 1).coerceAtLeast(1)
        val (firstX, lastX) = buildSmoothPath(
            path = downloadPath,
            count = downloadHistory.size,
            xAt = { it * downStepX },
            yAt = { size.height - (downloadHistory[it] / peak) * size.height * 0.92f },
        )

        fillPath.reset()
        fillPath.addPath(downloadPath)
        fillPath.lineTo(lastX, size.height)
        fillPath.lineTo(firstX, size.height)
        fillPath.close()
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(downloadColor.copy(alpha = 0.28f), downloadColor.copy(alpha = 0f)),
            ),
        )
        drawPath(downloadPath, color = downloadColor, style = Stroke(width = 2.dp.toPx()))

        if (uploadHistory.size >= 2) {
            val upStepX = size.width / (uploadHistory.size - 1).coerceAtLeast(1)
            buildSmoothPath(
                path = uploadPath,
                count = uploadHistory.size,
                xAt = { it * upStepX },
                yAt = { size.height - (uploadHistory[it] / peak) * size.height * 0.92f },
            )
            drawPath(
                path = uploadPath,
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
    val linePath = remember { Path() }
    val fillPath = remember { Path() }

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

        val (firstX, lastX) = buildSmoothPath(
            path = linePath,
            count = samples.size,
            xAt = { it * step },
            yAt = { baseline - (samples[it] / peak) * size.height * 0.88f },
        )

        fillPath.reset()
        fillPath.addPath(linePath)
        fillPath.lineTo(lastX, baseline)
        fillPath.lineTo(firstX, baseline)
        fillPath.close()
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0f)),
            ),
        )
        drawPath(linePath, color = accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))

        // The head of the trace, so the eye finds the present moment without hunting for it.
        if (live) {
            val headY = baseline - (samples.last() / peak) * size.height * 0.88f
            drawCircle(color = accent, radius = 3.dp.toPx(), center = Offset(lastX, headY))
        }
    }
}

/** Samples on screen at once: at 150 ms each this is the nine seconds a phase lasts. */
private const val WINDOW = 60

private inline fun buildSmoothPath(
    path: Path,
    count: Int,
    xAt: (Int) -> Float,
    yAt: (Int) -> Float,
): Pair<Float, Float> {
    path.reset()
    val firstX = xAt(0)
    var prevX = firstX
    var prevY = yAt(0)
    path.moveTo(prevX, prevY)
    for (index in 1 until count) {
        val currentX = xAt(index)
        val currentY = yAt(index)
        val midX = (prevX + currentX) / 2f
        path.cubicTo(midX, prevY, midX, currentY, currentX, currentY)
        prevX = currentX
        prevY = currentY
    }
    return firstX to prevX
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
