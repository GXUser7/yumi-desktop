package com.mydrop.vpn.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.graphics.shapes.RoundedPolygon
import com.mydrop.vpn.core.model.WorldMap
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The world as a square window onto a scrolling map, drawn in pixels.
 *
 * The phone's figure, unchanged in behaviour and re-expressed in the drawing API this platform
 * has: the strip is baked into an [ImageBitmap] instead of an Android bitmap and blitted with
 * [FilterQuality.None], which is the whole point — smoothing a pixel planet is the one thing it
 * must not do.
 *
 * Square rather than round, because the figure it lives in is a rounded square and a circle inside
 * one leaves four corners of nothing. So this is not a globe seen from outside — it is a strip of
 * map running past a window, filling it edge to edge and wrapping at the antimeridian, which is a
 * seam in the array and not in the world.
 *
 * Three colours and no more. Sea is `primaryContainer`, land is `primary`, and the grid between
 * cells is the figure's own background showing through. The one exception is the marker, and it
 * earns its place by being the only thing on screen that is not the planet.
 */
@Composable
fun PixelPlanet(
    mask: ByteArray,
    /** Where the tunnel comes out, or null while there is nowhere to point at. */
    latitude: Double?,
    longitude: Double?,
    connected: Boolean,
    warming: Boolean,
    seaColor: Color,
    landColor: Color,
    gapColor: Color,
    markerColor: Color,
    modifier: Modifier = Modifier,
) {
    val tick by rememberPlanetTicks()

    val strip = remember(mask, seaColor, landColor, gapColor) {
        Planet.strip(mask, seaColor, landColor, gapColor)
    }
    val camera = Planet.camera
    // The one smooth thing on a planet made of squares, and deliberately so. MaterialShapes are
    // normalised into a 0..1 box, so the path is built once and scaled in the draw transform.
    val marker = remember { MaterialShapes.Cookie4Sided.toComposePath() }

    Canvas(modifier.fillMaxSize()) {
        // Reading `tick` here is what keeps the figure moving, and it has to be read *inside* the
        // draw: Compose repeats a draw when something it read has changed, and everything else
        // this one touches lives in [Planet], outside the snapshot system. Declaring the tick and
        // never reading it drew the planet once and left it there.
        //
        // Advanced here rather than in the frame callback, because only the draw knows how big
        // the figure is - and the springs are integrated in pixels. The step is taken once per
        // frame however many times this runs; see [PlanetState.takeStep].
        camera.advance(Planet.stepAt(tick), size.width, size.height, connected, warming, latitude, longitude)
        // Painted under the strip so that scrolling past the top of the map reads as more ocean
        // rather than as a hole. Above the eighty-first parallel it very nearly is.
        drawRect(color = seaColor)
        drawStrip(camera, strip)
        if (connected && latitude != null && longitude != null) {
            drawMarker(Planet.markerDegrees, camera, marker, markerColor)
        }
    }
}

/** Degrees a cell covers: chunky enough to read as pixels, fine enough to read as Europe. */
private const val CELL_DEGREES = 2.0

/**
 * The band of latitude the strip covers: all of it, pole to pole.
 *
 * Bringing a country to the middle of the window means scrolling half a window-height of map above
 * it, and a strip that stops at the eighty-first parallel does not have that much map above Riga.
 * Antarctica is kept out of sight by the idle zoom below rather than by being thrown away, because
 * the same rows are what let Scandinavia reach the centre.
 */
private const val LATITUDE_TOP = 90.0
private const val LATITUDE_BOTTOM = -90.0
private const val LATITUDE_SPAN = LATITUDE_TOP - LATITUDE_BOTTOM

private const val COLUMNS = (360.0 / CELL_DEGREES).toInt()
private const val ROWS = (LATITUDE_SPAN / CELL_DEGREES).toInt()

/** Bitmap pixels per cell; the last row and column of each are the grid gap, baked in. */
private const val CELL_PIXELS = 4

/** How much wider the strip is than tall, which is what makes a square window show a slice. */
private const val STRIP_ASPECT = (360.0 / LATITUDE_SPAN).toFloat()

private fun bakeStrip(mask: ByteArray, sea: Color, land: Color, gap: Color): ImageBitmap {
    val width = COLUMNS * CELL_PIXELS
    val height = ROWS * CELL_PIXELS
    val image = ImageBitmap(width, height)
    val canvas = androidx.compose.ui.graphics.Canvas(image)
    val paint = Paint().apply { isAntiAlias = false }
    paint.color = gap
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    for (row in 0 until ROWS) {
        val latitude = LATITUDE_TOP - (row + 0.5) * CELL_DEGREES
        val top = (row * CELL_PIXELS).toFloat()
        for (column in 0 until COLUMNS) {
            val longitude = -180.0 + (column + 0.5) * CELL_DEGREES
            paint.color = if (WorldMap.isLand(mask, latitude, longitude)) land else sea
            val left = (column * CELL_PIXELS).toFloat()
            canvas.drawRect(left, top, left + CELL_PIXELS - 1f, top + CELL_PIXELS - 1f, paint)
        }
    }
    return image
}

/** A [RoundedPolygon] as a Compose path, in whatever box the polygon itself is normalised into. */
private fun RoundedPolygon.toComposePath(): Path {
    val path = Path()
    cubics.forEachIndexed { index, cubic ->
        if (index == 0) path.moveTo(cubic.anchor0X, cubic.anchor0Y)
        path.cubicTo(
            cubic.control0X, cubic.control0Y,
            cubic.control1X, cubic.control1Y,
            cubic.anchor1X, cubic.anchor1Y,
        )
    }
    path.close()
    return path
}

/**
 * Where the window sits on the strip, integrated from the frame clock.
 *
 * Springs rather than eased tweens, for the reason Material gives for preferring them: a spring
 * can be redirected mid-flight without a seam, and the tunnel changing servers under a running
 * animation is exactly that.
 */
private class MapCamera {
    var stripWidth = 0f
        private set
    var stripHeight = 0f
        private set
    var originX = 0f
        private set
    var originY = 0f
        private set

    /**
     * How far the marker has to come off centre for it to still be over its country.
     *
     * Zero everywhere a server is ever likely to be. It stops being zero only past about the
     * seventieth parallel, where centring the country would mean scrolling off the top of the
     * world — and there the map staying whole matters more than the shape staying dead centre.
     */
    var markerOffsetY = 0f
        private set

    private var scroll = 0.0
    private var scrollSpeed = 0.0
    private var zoom = 1.0
    private var zoomSpeed = 0.0

    /**
     * [step] is seconds since the previous frame, already clamped by [PlanetState]. It arrives as
     * an interval rather than as a reading off a clock because this object now outlives the page
     * it is drawn on: an absolute time would have to be measured against one taken before the user
     * left, and the answer - however long they spent on another page, or in the tray - is a step
     * that flings the springs across the whole world.
     */
    fun advance(
        step: Double,
        width: Float,
        height: Float,
        connected: Boolean,
        warming: Boolean,
        latitude: Double?,
        longitude: Double?,
    ) {
        if (width <= 0f || height <= 0f) return

        val zoomTarget = if (connected) CONNECTED_ZOOM else IDLE_ZOOM
        zoomSpeed += (-STIFFNESS * (zoom - zoomTarget) - DAMPING * zoomSpeed) * step
        // Floored at the idle zoom, so the strip is never shorter than the window. A spring
        // relaxing out of the connected zoom overshoots below its target, and a strip a hair
        // shorter than the window is what turns the clamp below inside out.
        zoom = (zoom + zoomSpeed * step).coerceAtLeast(IDLE_ZOOM)

        stripHeight = (height * zoom).toFloat()
        stripWidth = stripHeight * STRIP_ASPECT

        if (connected && latitude != null && longitude != null) {
            val turn = stripWidth.toDouble()
            var target = (longitude + 180.0) / 360.0 * turn - width / 2.0
            // Unwrapped against where the window actually is, so a switch from Latvia to France
            // scrolls the short way instead of unwinding most of the world.
            while (target - scroll > turn / 2) target -= turn
            while (target - scroll < -turn / 2) target += turn
            scrollSpeed += (-STIFFNESS * (scroll - target) - DAMPING * scrollSpeed) * step
            scroll += scrollSpeed * step
        } else {
            scroll += step * stripWidth * if (warming) FAST_DRIFT else IDLE_DRIFT
            scrollSpeed = 0.0
        }

        originX = -scroll.toFloat()

        val wanted = if (connected && latitude != null) {
            height / 2f - ((LATITUDE_TOP - latitude) / LATITUDE_SPAN * stripHeight).toFloat()
        } else {
            (height - stripHeight) / 2f
        }
        // Held on both sides: the strip carries a baked grid and the sea painted behind it does
        // not, so scrolling past either edge would leave a band of flat colour against a textured
        // map. What gives instead is the marker — whatever the clamp takes, it takes too, so it
        // stays over its country rather than drifting onto the wrong sea.
        originY = wanted.coerceIn((height - stripHeight).coerceAtMost(0f), 0f)
        markerOffsetY = originY - wanted
    }

    private companion object {
        /** Strip widths per second, so the drift reads the same whatever size the figure is. */
        const val IDLE_DRIFT = 0.018
        const val FAST_DRIFT = 0.10

        /**
         * Idle sits a little inside the poles, which is how Antarctica stays off screen without
         * being cut out of the data. Connected is gentle on purpose: a square window over forty
         * degrees is close enough that the coastline stops being recognisable as anywhere.
         */
        const val IDLE_ZOOM = 1.35
        const val CONNECTED_ZOOM = 1.5
        const val STIFFNESS = 24.0
        const val DAMPING = 9.0
    }
}

private fun DrawScope.drawStrip(camera: MapCamera, strip: ImageBitmap) {
    if (camera.stripWidth <= 0f || camera.stripHeight <= 0f) return
    // Rounded to whole device pixels at both edges rather than by offset-plus-size: rounding the
    // size separately lets two adjacent copies disagree by a pixel, and the seam that leaves runs
    // the full height of the figure.
    val top = camera.originY.roundToInt()
    val height = ((camera.originY + camera.stripHeight).roundToInt() - top).coerceAtLeast(1)
    // Start at the copy left of the window and walk right until past its edge. The strip is
    // always wider than the window, so this is two copies — briefly three while a spring
    // overshoots — and never the whole world laid out off-screen.
    var x = camera.originX - floor(camera.originX / camera.stripWidth + 1f) * camera.stripWidth
    while (x < size.width) {
        val left = x.roundToInt()
        val width = ((x + camera.stripWidth).roundToInt() - left).coerceAtLeast(1)
        drawImage(
            image = strip,
            dstOffset = IntOffset(left, top),
            dstSize = IntSize(width, height),
            filterQuality = FilterQuality.None,
        )
        x += camera.stripWidth
    }
}

/**
 * The cookie, planted at the middle of the window and turning.
 *
 * Drawn at the centre rather than at the projected position of the country, and the difference is
 * the point: the marker is the viewfinder and the map is what moves. The camera above scrolls the
 * exit country underneath it, so during the flight the two converge instead of the marker sliding
 * about — and when it settles, the country is exactly under the shape.
 */
private fun DrawScope.drawMarker(degrees: Float, camera: MapCamera, shape: Path, color: Color) {
    if (camera.stripHeight <= 0f) return
    val centre = Offset(size.width / 2f, size.height / 2f + camera.markerOffsetY)
    // Sized off the cell, so the cookie stays the same few cells across at every zoom instead of
    // growing into a lid over the country it is pointing at.
    val side = camera.stripHeight / ROWS * 3.4f

    fun cookie(at: Offset, paint: Color) = withTransform({
        // Clockwise, which is what a positive angle is once y points down. Four lobes means a full
        // turn is four repeats, so the rate is set against the lobe rather than the revolution.
        rotate(degrees, pivot = at)
        translate(at.x - side / 2f, at.y - side / 2f)
        scale(side, side, pivot = Offset.Zero)
    }) {
        drawPath(shape, paint)
    }

    // The shadow is the same shape offset in screen space, not a blurred one. A blur would be the
    // obvious choice anywhere else and is the wrong one here: everything under it is drawn in hard
    // squares, and a soft edge floating over a pixel grid reads as a rendering mistake. Offset
    // down and to the right, which is where the light has been coming from since pixel art began.
    //
    // It also does the work the colour no longer does. The marker is the accent at the opposite
    // lightness of the land, and on a pale theme those two can still sit close; the shadow is what
    // keeps the shape off the coastline whatever the palette does.
    cookie(centre + Offset(side * MARKER_SHADOW_OFFSET, side * MARKER_SHADOW_OFFSET), MarkerShadow)
    cookie(centre, color)
}

private const val MARKER_SHADOW_OFFSET = 0.11f
/**
 * Heavier than it first was, because what it separates changed. Under a dark marker the shadow
 * was a nicety; under a light one sitting on light land it is the only edge the shape has.
 */
private val MarkerShadow = Color.Black.copy(alpha = 0.45f)

private const val MARKER_DEGREES_PER_SECOND = 8f

/**
 * The planet, kept for the life of the process rather than for the life of the page.
 *
 * Where the window sits on the strip, how far it has zoomed in and what angle the marker has
 * turned to are all animation that has already been running. They used to live in `remember`,
 * which ties them to the composition — and the composition ends the moment the user opens another
 * page in the rail. Coming back built a fresh camera at zero, so the map snapped to the
 * antimeridian and the flight to the exit country started over, every single time. A figure that
 * rewinds itself whenever nobody is looking is a screensaver, not a planet.
 *
 * One instance, because there is one planet: it lives in the connection page's figure and nowhere
 * else. Two on screen at once would share a camera and advance it twice a frame, which is why this
 * is private to this file rather than something a caller is handed.
 */
private val Planet = PlanetState()

private class PlanetState {
    val camera = MapCamera()

    /** Where the cookie has turned to, in degrees and kept inside one revolution. */
    var markerDegrees = 0f
        private set

    private var pending = 0.0
    private var lastFrameMillis = 0L
    private var lastDrawnFrame = -1L
    private var stripKey: Any? = null
    private var stripImage: ImageBitmap? = null

    /**
     * Seconds since the previous frame.
     *
     * Clamped at both ends. A first frame has nothing to measure against, and the frame clock is
     * the system's rather than ours — so the gap across a spell on another page, or in the tray, is
     * however long the user spent there, and unclamped it would fling the springs. Fifty
     * milliseconds is three frames at sixty hertz: enough to ride out a stutter, short enough that
     * a minute away resumes where it left off instead of fast-forwarding.
     */
    fun tick(frameMillis: Long) {
        val step =
            if (lastFrameMillis == 0L) 0.0
            else ((frameMillis - lastFrameMillis) / 1000.0).coerceIn(0.0, MAX_STEP_SECONDS)
        lastFrameMillis = frameMillis
        pending += step
        markerDegrees = (markerDegrees + (step * MARKER_DEGREES_PER_SECOND).toFloat()) % 360f
    }

    /**
     * The step for one frame, taken once.
     *
     * Draw can run more than once for a single frame, and the camera integrates what it is given —
     * so a step handed out twice is a planet moving at twice the speed it was asked to. Hence the
     * frame number: the second caller within a frame gets nothing, which is the truth, no time
     * having passed. It is also the value the draw reads to be repeated at all.
     *
     * Clamped again on the way out. The clamp in [tick] is per frame, and frames keep coming while
     * a draw is being skipped — so what has piled up in between is not a step anybody should
     * integrate.
     */
    fun stepAt(frame: Long): Double {
        if (frame == lastDrawnFrame) return 0.0
        lastDrawnFrame = frame
        return pending.coerceAtMost(MAX_STEP_SECONDS).also { pending = 0.0 }
    }

    /**
     * The baked strip, kept across pages for the same reason the camera is.
     *
     * One entry: the key is the mask and the three colours, and the only thing that ever changes
     * them is the theme. Baking is a megabyte of pixels drawn one cell at a time, which is a
     * frame's worth of work — cheap enough to do when the colours change, wasteful to do again
     * every time somebody comes back to the connection page.
     */
    fun strip(mask: ByteArray, sea: Color, land: Color, gap: Color): ImageBitmap {
        val key = listOf(mask, sea, land, gap)
        stripImage?.takeIf { stripKey == key }?.let { return it }
        return bakeStrip(mask, sea, land, gap).also {
            stripKey = key
            stripImage = it
        }
    }
}

/** Three frames at sixty hertz: enough to ride out a stutter, short enough not to fast-forward. */
private const val MAX_STEP_SECONDS = 0.05

/** Ticks [Planet] once per frame, and counts the frames so that the figure redraws with them. */
@Composable
private fun rememberPlanetTicks(): State<Long> = produceState(0L) {
    while (true) {
        withInfiniteAnimationFrameMillis { frame ->
            Planet.tick(frame)
            value += 1
        }
    }
}
