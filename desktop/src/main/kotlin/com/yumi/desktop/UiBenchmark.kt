package com.yumi.desktop

import com.mydrop.vpn.core.model.ThemeMode
import com.mydrop.vpn.core.model.Visualizer
import kotlinx.coroutines.delay
import java.io.File
import java.lang.management.ManagementFactory
import com.sun.management.OperatingSystemMXBean
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.awt.ComposeWindow
import org.jetbrains.skia.Canvas
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.Component
import java.awt.Container
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

val LocalUiFrameProbe = staticCompositionLocalOf<UiFrameProbe?> { null }

/** Enabled only by --ui-benchmark. Measures actual window draws, without requesting extra frames. */
class UiFrameProbe(private val launchedAt: Long = System.nanoTime()) {
    private var firstFrame = true
    private val frames = ArrayList<Long>()
    private var recording = false
    var servers: LazyListState? = null
    @Synchronized fun draw() {
        val now = System.nanoTime()
        if (firstFrame) { firstFrame = false; println("UI_BENCHMARK startup_ms=${(now - launchedAt) / 1e6}") }
        if (recording) frames.add(now)
    }
    @Synchronized fun start() { frames.clear(); recording = true }
    @Synchronized fun stop(): List<Long> { recording = false; return frames.toList() }

    fun attach(window: ComposeWindow): () -> Unit {
        fun descendants(component: Component): Sequence<Component> = sequence {
            yield(component)
            if (component is Container) component.components.forEach { yieldAll(descendants(it)) }
        }
        val layer = descendants(window).filterIsInstance<SkiaLayer>().single()
        val delegate = checkNotNull(layer.renderDelegate)
        layer.renderDelegate = object : SkikoRenderDelegate {
            override fun onRender(canvas: Canvas, width: Int, height: Int, nanoTime: Long) {
                delegate.onRender(canvas, width, height, nanoTime)
                draw()
            }
        }
        println("UI_BENCHMARK renderer=${layer.renderApi}, ${layer.renderInfo}")
        return { layer.renderDelegate = delegate }
    }
}

/** Isolated demo data and backend: this never connects a real VPN or changes the user's settings. */
suspend fun benchmarkUi(model: DesktopModel, probe: UiFrameProbe, page: (Page) -> Unit, visible: (Boolean) -> Unit) {
    val cpu = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    val lines = mutableListOf("scenario\tframes\tfps\tp95_ms\tp99_ms\tcpu_core_percent")
    model.settings.update { it.copy(themeMode = ThemeMode.Dark) }
    model.toggleConnection().join()
    suspend fun measure(name: String, shown: Page, live: Boolean, figure: Visualizer = Visualizer.Planet, hidden: Boolean = false, scroll: Float = 0f) {
        visible(true)
        page(shown)
        model.settings.update { it.copy(visualizer = figure) }
        model.desktop.update { it.copy(liveBackdrop = live) }
        delay(2500)
        if (scroll > 0f) { checkNotNull(probe.servers).scrollToItem(0); delay(1000) }
        if (hidden) { visible(false); delay(1000) }
        val startCpu = cpu.processCpuTime
        val start = System.nanoTime()
        probe.start()
        coroutineScope {
            val scrolling = if (scroll > 0f) launch {
                checkNotNull(probe.servers).animateScrollBy(scroll, tween(5000, easing = LinearEasing))
            } else null
            delay(5000)
            scrolling?.cancel()
        }
        val frames = probe.stop()
        val elapsed = (System.nanoTime() - start) / 1e9
        val used = (cpu.processCpuTime - startCpu) / 1e9
        val gaps = frames.zipWithNext { a, b -> (b - a) / 1e6 }.sorted()
        fun percentile(fraction: Double) = if (gaps.isEmpty()) 0.0 else gaps[((gaps.size - 1) * fraction).toInt()]
        val line = listOf(name, frames.size, frames.size / elapsed, percentile(0.95), percentile(0.99), used / elapsed * 100).joinToString("\t")
        lines.add(line)
        println("UI_BENCHMARK\t$line")
    }
    measure("planet-live", Page.Connect, true)
    measure("waves-live", Page.Connect, true, Visualizer.Waves)
    measure("servers-600-live", Page.Servers, true)
    measure("servers-600-scroll", Page.Servers, true, scroll = 6000f)
    measure("servers-600-fast-scroll", Page.Servers, true, scroll = 18_000f)
    measure("settings-frozen", Page.Settings, false)
    measure("tray", Page.Connect, true, hidden = true)
    visible(true)
    val output = File(".local/performance/benchmark.tsv").also { it.parentFile.mkdirs() }
    output.writeText(lines.joinToString("\n", postfix = "\n"))
    println("UI_BENCHMARK saved ${output.absolutePath}")
}
