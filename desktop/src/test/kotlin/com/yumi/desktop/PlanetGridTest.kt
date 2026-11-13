package com.yumi.desktop

import com.mydrop.vpn.ui.components.MapCamera
import kotlin.math.abs
import kotlin.math.round
import kotlin.test.*

class PlanetGridTest {
    @Test fun idleMapKeepsFullHeightAndAnEvenPhysicalPixelGridAtCommonDisplayScales() {
        for (height in listOf(180f, 320f, 565f, 777f, 1130f)) {
            val camera = MapCamera()
            repeat(1200) { camera.advance(1.0 / 120, 800f, height, false, false, null, null) }
            val pitch = camera.stripHeight / 90
            assertEquals(round(pitch), pitch, "Fractional cells shimmer on scroll at height $height")
            assertTrue(camera.originY <= 0f)
            assertTrue(camera.originY + camera.stripHeight >= height, "No empty strip above/below the idle map")
            assertTrue(camera.stripHeight / height in 1.2f..1.6f, "Preserve the idle map's vertical coverage")
            assertEquals(2 * camera.stripHeight, camera.stripWidth)
        }
    }

    @Test fun aConnectedCountryStaysOnTheMapAfterZoomSettles() {
        val camera = MapCamera()
        repeat(1200) { camera.advance(1.0 / 120, 700f, 565f, true, false, 64.0, 26.0) }
        assertEquals(round(camera.stripHeight / 90), camera.stripHeight / 90)
        assertTrue(camera.originY in (565f - camera.stripHeight)..0f)
        assertTrue(camera.markerOffsetY.isFinite())
    }
    @Test fun thePixelAlignedZoomDoesNotJumpWhenTheSpringFinishes() {
        val camera = MapCamera()
        repeat(1200) { camera.advance(1.0 / 120, 700f, 565f, false, false, null, null) }
        var previousHeight = camera.stripHeight
        repeat(1200) {
            camera.advance(1.0 / 120, 700f, 565f, true, false, 50.0, 10.0)
            assertTrue(abs(camera.stripHeight - previousHeight) < 5f, "The final snap must not jump half a cell across all 90 rows")
            previousHeight = camera.stripHeight
        }
        assertEquals(round(camera.stripHeight / 90), camera.stripHeight / 90)
    }

}
