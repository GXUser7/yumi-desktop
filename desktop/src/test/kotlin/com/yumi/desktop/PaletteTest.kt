package com.yumi.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.yumi.desktop.model.Palette
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.*

class PaletteTest {
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    @Test fun newPalettesKeepReadableButtonsAndPageTextInBothModes() {
        val added = Palette.entries.drop(7)
        assertEquals(8, added.size)
        for (palette in added) for (dark in listOf(false, true)) {
            val scheme = palette.scheme(dark)
            assertTrue(contrast(scheme.primary, scheme.onPrimary) >= 4.5f, "$palette/$dark button contrast")
            assertTrue(contrast(scheme.surfaceContainer, scheme.onSurface) >= 7f, "$palette/$dark body contrast")
            assertTrue(contrast(scheme.surfaceContainer, scheme.onSurfaceVariant) >= 4.5f, "$palette/$dark secondary text contrast")
        }
    }

    @Test fun graphiteStaysNeutralIncludingAmoledContainers() {
        for (dark in listOf(false, true)) {
            val scheme = Palette.Graphite.scheme(dark)
            val colors = listOf(scheme.primary, scheme.background, scheme.surfaceContainer)
            for (color in colors) assertTrue(abs(color.red - color.blue) < 0.0001f && abs(color.red - color.green) < 0.0001f)
        }
        val amoled = Palette.Graphite.scheme(true).toAmoled(Palette.Graphite.hue, Palette.Graphite.chroma)
        assertEquals(Color.Black, amoled.background)
        assertEquals(amoled.surfaceContainer.red, amoled.surfaceContainer.blue, 0.0001f)
    }

    @Test fun storedPaletteNamesRemainCompatibleAndNewNamesRoundTrip() {
        assertEquals(listOf("Glacier", "Rose", "Lilac", "Mint", "Peach", "Sand", "Sea"), Palette.entries.take(7).map { it.name })
        for (palette in Palette.entries) {
            val encoded = Json.encodeToString(Palette.serializer(), palette)
            assertEquals("\"${palette.name}\"", encoded)
            assertEquals(palette, Json.decodeFromString(Palette.serializer(), encoded))
        }
    }
}
