package com.mydrop.vpn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Yumi mark, drawn rather than pasted.
 *
 * The icon file is a violet tile with a white glyph on it. That is the right thing on a taskbar
 * and the wrong thing inside the app: in the rail it would sit there in its own two colours while
 * everything around it followed the palette, and under a pastel theme that reads as a sticker
 * someone left on the window. So the geometry is kept and the two colours are handed in.
 *
 * The plate is not decoration. Set loose without it the glyph is four squares running diagonally
 * across an empty box, which at rail size does not read as a mark at all — it reads as something
 * drawn askew. The tile is what tells the eye where the figure begins and ends.
 *
 * Three shapes, filled one at a time — the way the artwork itself is built, where they are three
 * separate `<rect>`s and a `<path>`.
 *
 * They were merged into a single Path once, on the theory that a shared non-zero winding would
 * union them. It does not: `addRoundRect` walks its outline counter-clockwise and the staircase
 * parsed from the path string walks clockwise, so in the strip where the staircase crosses the
 * upper right tile the two windings cancelled and ate a hole through it. The tile rendered as a
 * 79-by-40 rectangle among squares — subtle enough to survive review and obvious once seen.
 *
 * Filling them separately cannot express that bug at all, which is worth more than the two draw
 * calls it costs.
 */
@Composable
fun YumiMark(plate: Color, glyph: Color, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val tile = remember { platePath() }
    val marks = remember { markPaths() }
    Canvas(modifier.size(size)) {
        withTransform({
            scale(this@Canvas.size.width / SIDE, this@Canvas.size.height / SIDE, pivot = Offset.Zero)
        }) {
            drawPath(tile, plate)
            marks.forEach { drawPath(it, glyph) }
        }
    }
}

/** The artboard the coordinates below are in; the transform above maps it onto whatever is asked. */
private const val SIDE = 366f

private fun platePath(): Path = Path().apply {
    addRoundRect(RoundRect(0f, 0f, SIDE, SIDE, CornerRadius(71f)))
}

private fun markPaths(): List<Path> = listOf(
    Path().apply { addRoundRect(RoundRect(81f, 65f, 160f, 144f, CornerRadius(11f))) },
    Path().apply { addRoundRect(RoundRect(208.219f, 65.3521f, 287.219f, 144.3521f, CornerRadius(11f))) },
    PathParser().parsePathString(STAIRCASE).toPath(),
)

private const val STAIRCASE =
    "M130.219 252.352C130.219 258.427 135.144 263.352 141.219 263.352H169.219V291.352C169.219 " +
        "297.427 164.294 302.352 158.219 302.352H101.219C95.144 302.352 90.2191 297.427 90.2191 " +
        "291.352V234.352C90.2191 228.277 95.144 223.352 101.219 223.352H130.219V252.352ZM169.219 " +
        "212.352C169.219 218.427 174.144 223.352 180.219 223.352H209.219V252.352C209.219 258.427 " +
        "204.294 263.352 198.219 263.352H169.219V234.352C169.219 228.277 164.294 223.352 158.219 " +
        "223.352H130.219V195.352C130.219 189.277 135.144 184.352 141.219 184.352H169.219V212.352Z" +
        "M208.219 173.352C208.219 179.427 213.144 184.352 219.219 184.352H248.219V212.352C248.219 " +
        "218.427 243.294 223.352 237.219 223.352H209.219V195.352C209.219 189.277 204.294 184.352 " +
        "198.219 184.352H169.219V155.352C169.219 149.277 174.144 144.352 180.219 144.352H208.219V" +
        "173.352ZM276.219 105.352C282.294 105.352 287.219 110.277 287.219 116.352L287.219 173.352" +
        "C287.219 179.427 282.294 184.352 276.219 184.352H248.219V155.352C248.219 149.277 243.294 " +
        "144.352 237.219 144.352H208.219V116.352C208.219 110.277 213.144 105.352 219.219 105.352H" +
        "276.219Z"
