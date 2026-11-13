package com.yumi.desktop

import androidx.compose.ui.geometry.Size
import org.jetbrains.skia.Image
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface

/**
 * Bake the small frosted scene once. Replaying a GraphicsLayer with a renderEffect in every pane
 * re-executes its nested blur filters for every card, even when that layer has not been recorded
 * again. An immutable bitmap is just sampled by the panes, however many of them are visible.
 */
internal class FrostBuffer(private val mode: FilterTileMode = FilterTileMode.CLAMP) : AutoCloseable {
    private var source: Surface? = null
    private var blurred: Surface? = null
    private var filter: ImageFilter? = null
    private val paint = Paint()
    private val scope = CanvasDrawScope()
    private var dimensions = IntSize.Zero
    private var radius = -1f

    fun prepare(size: IntSize, blurRadius: Float) {
        if (size != dimensions) {
            source?.close(); blurred?.close()
            source = Surface.makeRasterN32Premul(size.width, size.height)
            blurred = Surface.makeRasterN32Premul(size.width, size.height)
            dimensions = size
        }
        if (radius != blurRadius) {
            // Compose desktop's BlurEffect uses this radius-to-sigma conversion.
            val sigma = if (blurRadius > 0f) blurRadius * 0.57735f + 0.5f else 0f
            val next = ImageFilter.makeBlur(sigma, sigma, mode)
            paint.imageFilter = next
            filter?.close()
            filter = next
            radius = blurRadius
        }
    }

    fun record(density: Density, direction: LayoutDirection, block: DrawScope.() -> Unit): Image {
        val raw = checkNotNull(source)
        val output = checkNotNull(blurred)
        raw.canvas.clear(0)
        scope.draw(density, direction, raw.canvas.asComposeCanvas(), Size(dimensions.width.toFloat(), dimensions.height.toFloat()), block)
        output.canvas.clear(0)
        raw.makeImageSnapshot().use { output.canvas.drawImage(it, 0f, 0f, paint) }
        return output.makeImageSnapshot()
    }

    override fun close() {
        source?.close(); source = null
        blurred?.close(); blurred = null
        paint.close(); filter?.close(); filter = null
    }
}

/** The visible scene also becomes one small image, so GPU blur passes are not replayed on scroll. */
internal class SceneBuffer : AutoCloseable {
    private var surface: Surface? = null
    private var dimensions = IntSize.Zero
    private val scope = CanvasDrawScope()

    fun prepare(size: IntSize) {
        if (size == dimensions) return
        surface?.close()
        surface = Surface.makeRasterN32Premul(size.width, size.height)
        dimensions = size
    }

    fun record(density: Density, direction: LayoutDirection, block: DrawScope.() -> Unit): Image {
        val output = checkNotNull(surface)
        output.canvas.clear(0)
        scope.draw(density, direction, output.canvas.asComposeCanvas(), Size(dimensions.width.toFloat(), dimensions.height.toFloat()), block)
        return output.makeImageSnapshot()
    }

    override fun close() { surface?.close(); surface = null }
}
