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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Bake the small frosted scene once. Replaying a GraphicsLayer with a renderEffect in every pane
 * re-executes its nested blur filters for every card, even when that layer has not been recorded
 * again. An immutable bitmap is just sampled by the panes, however many of them are visible.
 */
internal class FrostBuffer(
    private val mode: FilterTileMode = FilterTileMode.CLAMP,
    retainedFrames: Int = 1,
    private val parallelism: Int = BlurParallelism,
) : AutoCloseable {
    private var source: Surface? = null
    private val outputs = RasterFrames(retainedFrames)
    private var filter: ImageFilter? = null
    private val paint = Paint()
    private val scope = CanvasDrawScope()
    private var dimensions = IntSize.Zero
    private var radius = -1f
    private var stripes = emptyList<BlurStripe>()

    fun prepare(size: IntSize, blurRadius: Float) {
        if (size != dimensions) {
            source?.close()
            source = Surface.makeRasterN32Premul(size.width, size.height)
            outputs.prepare(size)
            stripes.forEach { it.close() }
            val count = if (size.width.toLong() * size.height >= 16_384) parallelism.coerceIn(1, 3).coerceAtMost(size.height) else 1
            // Clip the destination, not the source. Each filter sees the complete source image,
            // including the neighboring rows its Gaussian kernel needs across a stripe boundary.
            stripes = if (count > 1) List(count) { index ->
                val top = size.height * index / count
                val bottom = size.height * (index + 1) / count
                BlurStripe(top, Surface.makeRasterN32Premul(size.width, bottom - top)).also {
                    it.surface.canvas.translate(0f, -top.toFloat())
                    it.paint.imageFilter = filter
                }
            } else emptyList()
            dimensions = size
        }
        if (radius != blurRadius) {
            // Compose desktop's BlurEffect uses this radius-to-sigma conversion.
            val sigma = if (blurRadius > 0f) blurRadius * 0.57735f + 0.5f else 0f
            val next = ImageFilter.makeBlur(sigma, sigma, mode)
            paint.imageFilter = next
            stripes.forEach { it.paint.imageFilter = next }
            filter?.close()
            filter = next
            radius = blurRadius
        }
    }

    fun record(density: Density, direction: LayoutDirection, block: DrawScope.() -> Unit): Image {
        val raw = checkNotNull(source)
        val output = outputs.next()
        raw.canvas.clear(0)
        scope.draw(density, direction, raw.canvas.asComposeCanvas(), Size(dimensions.width.toFloat(), dimensions.height.toFloat()), block)
        output.canvas.clear(0)
        raw.makeImageSnapshot().use { image ->
            if (stripes.isEmpty()) output.canvas.drawImage(image, 0f, 0f, paint)
            else {
                blurStripes(image)
                stripes.forEach { it.surface.draw(output.canvas, 0, it.top, null) }
            }
        }
        return output.makeImageSnapshot()
    }

    private fun blurStripes(image: Image) {
        val done = CountDownLatch(stripes.size)
        val failure = AtomicReference<Throwable?>()
        for (stripe in stripes) {
            try {
                BlurWorkers.execute {
                    try {
                        stripe.surface.canvas.clear(0)
                        stripe.surface.canvas.drawImage(image, 0f, 0f, stripe.paint)
                    } catch (error: Throwable) { failure.compareAndSet(null, error) }
                    finally { done.countDown() }
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                done.countDown()
            }
        }
        // Cancellation must not release the shared source while native worker draws still use it.
        var interrupted = false
        while (true) {
            try { done.await(); break }
            catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
        failure.get()?.let { throw it }
        if (interrupted) throw InterruptedException("Backdrop blur interrupted after its workers completed")
    }

    override fun close() {
        source?.close(); source = null
        outputs.close()
        stripes.forEach { it.close() }; stripes = emptyList()
        paint.close(); filter?.close(); filter = null
    }
}

private class BlurStripe(val top: Int, val surface: Surface) : AutoCloseable {
    val paint = Paint()
    override fun close() { paint.close(); surface.close() }
}

private val BlurParallelism = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(1, 3)
// One bounded pool, reused across frames and window sizes, with room left for the foreground UI.
private val BlurWorkers by lazy {
    Executors.newFixedThreadPool(BlurParallelism) { task -> Thread(task, "yumi-backdrop-blur").apply { isDaemon = true } }
}

/** The visible scene also becomes one small image, so GPU blur passes are not replayed on scroll. */
internal class SceneBuffer : AutoCloseable {
    // Keep the displayed snapshot separate from the surface producing its replacement.
    private val outputs = RasterFrames(2)
    private var dimensions = IntSize.Zero
    private val scope = CanvasDrawScope()

    fun prepare(size: IntSize) {
        if (size == dimensions) return
        outputs.prepare(size)
        dimensions = size
    }

    fun record(density: Density, direction: LayoutDirection, block: DrawScope.() -> Unit): Image {
        val output = outputs.next()
        output.canvas.clear(0)
        scope.draw(density, direction, output.canvas.asComposeCanvas(), Size(dimensions.width.toFloat(), dimensions.height.toFloat()), block)
        return output.makeImageSnapshot()
    }

    override fun close() { outputs.close() }
}

/** Snapshots stay immutable; Skia still safely detaches if a consumer retains older frames. */
private class RasterFrames(count: Int) : AutoCloseable {
    private val surfaces = arrayOfNulls<Surface>(count.also { require(it > 0) })
    private var next = 0

    fun prepare(size: IntSize) {
        close()
        for (i in surfaces.indices) surfaces[i] = Surface.makeRasterN32Premul(size.width, size.height)
        next = 0
    }

    fun next(): Surface = checkNotNull(surfaces[next]).also { next = (next + 1) % surfaces.size }

    override fun close() {
        for (i in surfaces.indices) { surfaces[i]?.close(); surfaces[i] = null }
    }
}
