package com.yumi.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.Image
import org.jetbrains.skia.FilterTileMode
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals

class FrostBufferTest {
    @Test fun rotatingFrostBuffersPreservePublishedFramesAndMatchTheSingleBufferBlur() {
        for (mode in listOf(FilterTileMode.CLAMP, FilterTileMode.DECAL)) {
            val retained = mutableListOf<Pair<Image, ByteArray>>()
            val reference = FrostBuffer(mode, parallelism = 1)
            val buffered = FrostBuffer(mode, retainedFrames = 2, parallelism = 3)
            try {
                repeat(12) { frame ->
                    val size = if (frame < 6) IntSize(257, 199) else IntSize(301, 211)
                    val radius = listOf(0f, 1.5f, 7f, 28f)[frame % 4]
                    reference.prepare(size, radius)
                    buffered.prepare(size, radius)
                    val draw: DrawScope.() -> Unit = {
                        drawRect(Brush.linearGradient(listOf(Color(0x80143f71), Color(0xffb2d5c8))),
                            topLeft = Offset(3f, 5f), size = Size(size.width - 6f, size.height - 10f))
                        drawCircle(Color.White, 32f, Offset(10f + frame * 17f, 35f + frame * 11f))
                    }
                    val image = buffered.record(Density(1f), LayoutDirection.Ltr, draw)
                    val pixels = image.pixels()
                    reference.record(Density(1f), LayoutDirection.Ltr, draw).use { expected ->
                        assertContentEquals(expected.pixels(), pixels, "Blur changed at frame $frame")
                    }
                    retained += image to pixels
                    retained.forEach { (published, before) -> assertContentEquals(before, published.pixels()) }
                }
            } finally {
                retained.forEach { it.first.close() }
                reference.close(); buffered.close()
            }
        }
    }

    @Test fun sceneFramesClearOldContentAndStayImmutableAfterReuseAndResize() {
        val retained = mutableListOf<Pair<Image, ByteArray>>()
        val buffer = SceneBuffer()
        try {
            repeat(8) { frame ->
                buffer.prepare(if (frame < 4) IntSize(80, 60) else IntSize(100, 90))
                val image = buffer.record(Density(1f), LayoutDirection.Ltr) {
                    drawRect(Color.Red, topLeft = Offset(frame * 5f, 0f), size = Size(10f, 10f))
                }
                image.peekPixels().use { pixels ->
                    checkNotNull(pixels)
                    kotlin.test.assertEquals(0, pixels.getColor(79, 59))
                    kotlin.test.assertEquals(0xffff0000.toInt(), pixels.getColor(frame * 5, 0))
                }
                retained += image to image.pixels()
                retained.forEach { (published, before) -> assertContentEquals(before, published.pixels()) }
            }
        } finally {
            retained.forEach { it.first.close() }
            buffer.close()
        }
    }

    @Test fun interruptedBlurJoinsNativeWorkersBeforeReleasingTheSourceAndCanBeReused() {
        val buffer = FrostBuffer(parallelism = 3)
        val reference = FrostBuffer(parallelism = 1)
        try {
            buffer.prepare(IntSize(301, 211), 28f)
            reference.prepare(IntSize(301, 211), 28f)
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<InterruptedException> {
                    buffer.record(Density(1f), LayoutDirection.Ltr) { drawCircle(Color.Red, 80f) }.close()
                }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
            buffer.record(Density(1f), LayoutDirection.Ltr) { drawCircle(Color.Blue, 40f) }.use { actual ->
                reference.record(Density(1f), LayoutDirection.Ltr) { drawCircle(Color.Blue, 40f) }.use { expected ->
                    assertContentEquals(expected.pixels(), actual.pixels())
                }
            }
        } finally { Thread.interrupted(); buffer.close(); reference.close() }
    }

    private fun Image.pixels(): ByteArray = checkNotNull(peekPixels()).use { pixels ->
        pixels.buffer.use { it.bytes }
    }
}
