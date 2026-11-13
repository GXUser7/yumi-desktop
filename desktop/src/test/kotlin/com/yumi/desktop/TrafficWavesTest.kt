package com.yumi.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.mydrop.vpn.ui.components.TrafficWaves
import com.mydrop.vpn.ui.components.WaveMotion
import com.mydrop.vpn.ui.components.defaultBands
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.*

@Suppress("DEPRECATION")
class TrafficWavesTest {
    private fun phaseDistance(a: Float, b: Float): Double {
        val difference = abs((a - b).toDouble())
        return minOf(difference, abs(PI * 10 - difference))
    }

    @Test fun missingFramesDoNotReplayHoursOfWaveHistory() {
        val motion = WaveMotion(defaultBands())
        var frame = 0L
        repeat(120) { motion.advance(frame, 0.7f, 0.4f); frame += 16_666_667 }
        val before = motion.phaseAt(0)
        frame += 3_600_000_000_000L
        motion.advance(frame, 0.7f, 0.4f)
        assertTrue(phaseDistance(before, motion.phaseAt(0)) <= 0.046)
        val resumed = motion.phaseAt(0)
        motion.advance(frame + 16_666_667, 0.7f, 0.4f)
        assertTrue(phaseDistance(resumed, motion.phaseAt(0)) <= 0.016, "There must be no catch-up debt")
    }

    @Test fun explicitHideFreezesPhaseAndAmplitudeUntilANewVisibleFrame() {
        val motion = WaveMotion(defaultBands())
        repeat(120) { motion.advance(it * 16_666_667L, 0.2f, 0.1f) }
        val phases = List(3, motion::phaseAt)
        val swell = motion.downSwell
        motion.advance(7_200_000_000_000L, 1f, 1f, active = false)
        motion.advance(8_000_000_000_000L, 1f, 1f, active = false)
        assertEquals(phases, List(3, motion::phaseAt))
        assertEquals(swell, motion.downSwell)
        motion.advance(8_000_000_000_001L, 1f, 1f)
        assertEquals(phases, List(3, motion::phaseAt))
        assertEquals(swell, motion.downSwell)
        motion.advance(8_000_016_666_668L, 1f, 1f)
        assertTrue(motion.downSwell - swell in 0f..0.05f)
    }

    @Test fun trafficChangesAfterTwoHoursDoNotRetimeTheWholeAnimation() {
        val motion = WaveMotion(defaultBands())
        var frame = 0L
        repeat(432_000) { motion.advance(frame, 0.15f, 0.1f); frame += 16_666_667 }
        val phases = List(3, motion::phaseAt)
        val swell = motion.downSwell
        motion.advance(frame, 1f, 1f)
        for (index in 0..2) {
            assertTrue(phaseDistance(phases[index], motion.phaseAt(index)) < 0.02)
            assertTrue(abs(motion.phaseAt(index)) < PI * 10)
        }
        assertTrue(motion.downSwell - swell in 0f..0.05f)
    }

    @Test fun anActualRenderedWaveResumesWithoutAVisualPhaseJump() {
        val active = mutableStateOf(true)
        val scene = ImageComposeScene(width = 480, height = 320) {
            CompositionLocalProvider(LocalAnimationsActive provides active.value) {
                TrafficWaves(2_000_000, 300_000, Color(0xff82b2ff), Color(0xffffc17a))
            }
        }
        var frame = 0L
        fun render(): FloatArray {
            Snapshot.sendApplyNotifications()
            return scene.render(frame).use { image ->
                val bitmap = image.toComposeImageBitmap()
                try {
                    val pixels = bitmap.toPixelMap()
                    FloatArray(480 * 320 * 4) { index ->
                        val pixel = pixels[(index / 4) % 480, (index / 4) / 480]
                        when (index % 4) {
                            0 -> pixel.red * pixel.alpha
                            1 -> pixel.green * pixel.alpha
                            2 -> pixel.blue * pixel.alpha
                            else -> pixel.alpha
                        }
                    }
                } finally { bitmap.asSkiaBitmap().close() }
            }
        }
        fun advance(count: Int) { repeat(count) { frame += 16_666_667; Snapshot.sendApplyNotifications(); scene.render(frame).close() } }
        fun difference(a: FloatArray, b: FloatArray) = a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / a.size
        try {
            advance(120)
            val before = render()
            frame += 3_600_000_000_000L
            advance(2)
            val after = render()
            assertTrue(difference(before, after) < 0.01, "Returning from a suspended frame clock must not change the whole wave")
            advance(60)
            assertTrue(difference(after, render()) > 0.00001, "The resumed wave must still animate")
            active.value = false
            advance(3)
            val frozen = render()
            frame += 3_600_000_000_000L
            advance(3)
            assertEquals(0.0, difference(frozen, render()))
            active.value = true
            advance(2)
            assertTrue(difference(frozen, render()) < 0.01)
        } finally { scene.close() }
    }
}
