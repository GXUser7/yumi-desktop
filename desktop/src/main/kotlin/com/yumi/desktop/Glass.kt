package com.yumi.desktop

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import kotlinx.coroutines.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * YouCloud's matte glass, on a desktop.
 *
 * Behind the window drift the Material 3 Expressive shapes — the very polygons the components use —
 * lit from one side, each with a highlight, a rim of light and a soft shadow, the far ones blurred
 * more than the near ones. Every panel in front of them is glass: the scene under it blurred heavily
 * and laid under a veil of the panel's colour. No hairline round the edge — a rim of light round
 * every panel made the glass look drawn on rather than matte. Only what is selected wears a ring.
 *
 * The phone's version has to blur whatever scrolls behind each pane. Here nothing in front of the
 * backdrop overlaps a panel, so the whole scene is blurred once per frame, small, and every pane
 * simply shows its own part of that one picture. A screen full of glass costs one blur.
 *
 * Tilt and shake have no desktop equivalent; the pointer stands in for the tilt. Moving it slides
 * the shapes by depth and swings the light, a little — a window that answers being looked at.
 */

/** Blur under glass. */
val FrostBlur = 28.dp

/**
 * How dense a pane's veil is, before [lightened]. Denser than the phone's 0.58: on a desktop the
 * panes are large and close together, and at the phone's density the window read as a sheet of
 * cellophane over the shapes rather than as panels of frosted glass standing in front of them.
 */
const val GlassAlpha = 0.76f

/** As dense as a page's own veil gets; for the large panes that hold a page's content. */
const val PageGlassAlpha = 0.84f

/** The scene under the window, recorded once per frame for every pane of glass to show. */
@Stable
class BackdropState {
    internal var scene by mutableStateOf<SceneFrame?>(null)
    internal val frost get() = scene?.frost
    /** How many times smaller [frost] is recorded than the window. */
    internal var shrink = 8f
    /** Published frame count, used by the rendering regression test. */
    internal var frame by mutableLongStateOf(0L)
    private var generation = 0L
    // Publish from the producer, never from draw: drawing must not schedule its own next frame.
    internal fun recorded() { frame = ++generation }
    /** The pointer, as -1..1 across the window, for parallax and the light. */
    internal var pointer = Offset.Zero
}

val LocalBackdrop = staticCompositionLocalOf<BackdropState?> { null }

/**
 * Where the window is on the screen, in the AWT's own units, written by the window's move listener
 * (see Main.kt) and read by the backdrop once a frame. Plain volatile fields rather than state: the
 * backdrop polls it in its frame loop, and nothing should recompose because a window moved.
 */
class WindowMotion {
    @Volatile var x = 0
    @Volatile var y = 0
    @Volatile var known = false
}

val LocalWindowMotion = staticCompositionLocalOf { WindowMotion() }

/**
 * The shapes as objects on springs behind the glass.
 *
 * YouCloud reads the accelerometer and lets a shake knock the shapes about. A window has no
 * accelerometer, but it is moved: dragging it carries the glass over the scene, the shapes lag
 * behind by their inertia — the nearer ones more — then swing back and settle. That is when matte
 * glass earns its keep, because it is moving colour under it that shows it is glass at all.
 */
private data class ScenePose(val dx: FloatArray, val dy: FloatArray, val turn: FloatArray)

private class SceneMotion(count: Int) {
    fun pose() = ScenePose(dx.copyOf(), dy.copyOf(), turn.copyOf())
    val dx = FloatArray(count)
    val dy = FloatArray(count)
    val vx = FloatArray(count)
    val vy = FloatArray(count)
    val turn = FloatArray(count)
    val spin = FloatArray(count)

    /** Whether anything is still swinging; the frame loop runs at full rate while it is. */
    var active = false
        private set

    /**
     * Advances [dt] seconds after the window moved by ([movedX], [movedY]) pixels. A shape keeps
     * its place in the world as the window moves, so it is displaced against the move straight
     * away; the spring then pulls it back into its place in the window.
     */
    fun step(dt: Float, movedX: Float, movedY: Float, shapes: List<SceneShape>, limit: Float) {
        var moving = false
        for (i in shapes.indices) {
            val inertia = 0.35f + 0.6f * shapes[i].depth
            dx[i] = (dx[i] - movedX * inertia).coerceIn(-limit, limit)
            dy[i] = (dy[i] - movedY * inertia).coerceIn(-limit, limit)
            spin[i] += movedX * 0.08f * inertia * (if (i % 2 == 0) 1f else -1f)
            // Semi-implicit Euler, in two halves so a long frame cannot fling a shape off.
            repeat(2) {
                val h = dt / 2f
                vx[i] += (-Stiffness * dx[i] - Damping * vx[i]) * h
                vy[i] += (-Stiffness * dy[i] - Damping * vy[i]) * h
                dx[i] += vx[i] * h
                dy[i] += vy[i] * h
                spin[i] += (-Stiffness * turn[i] - Damping * spin[i]) * h
                turn[i] += spin[i] * h
            }
            if (kotlin.math.abs(dx[i]) + kotlin.math.abs(dy[i]) + kotlin.math.abs(vx[i]) + kotlin.math.abs(vy[i]) > 0.5f ||
                kotlin.math.abs(turn[i]) + kotlin.math.abs(spin[i]) > 0.05f) moving = true
        }
        active = moving
    }

    private companion object {
        /** A loose spring with a little overshoot: shapes swing back past their place once. */
        const val Stiffness = 26f
        const val Damping = 4.6f
    }
}

/** Whether the theme is light: light glass on a light scene is a third of the way denser. */
@Composable @ReadOnlyComposable
fun isLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

@Composable @ReadOnlyComposable
fun lightened(alpha: Float): Float = if (isLightTheme()) alpha + (1f - alpha) * 0.3f else alpha

/**
 * The colour glass is veiled in: a calm, dark tone of the palette's own hue rather than its
 * container tone — a large plane at the accent's container tone glowed beside everything else.
 */
@Composable @ReadOnlyComposable
fun glassTint(alpha: Float = GlassAlpha): Color {
    val scheme = MaterialTheme.colorScheme
    // A step lighter than the ground the shapes stand on, the way YouCloud's panes are: frosted
    // glass scatters light, so it reads brighter than what is behind it, not as a hole in it.
    val base = if (isLightTheme()) lerp(scheme.surfaceContainerLowest, scheme.primaryContainer, 0.14f)
    else lerp(scheme.surfaceContainerHighest, scheme.primaryContainer, 0.42f)
    return base.copy(alpha = lightened(alpha))
}

/**
 * Frosted glass cut to [shape]: the backdrop under it, blurred, under [tint]. Content drawn inside
 * is clipped to the shape too, which is what a pane of glass with things on it looks like.
 */
fun Modifier.glass(shape: Shape, tint: Color): Modifier = composed {
    val backdrop = LocalBackdrop.current
    // Where the pane is, read only while drawing: moving it redraws the glass, not the screen.
    val origin = remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { origin.value = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            val frost = backdrop?.frost
            if (backdrop != null && frost != null) {
                val at = origin.value
                translate(-at.x, -at.y) {
                    scale(backdrop.shrink, backdrop.shrink, pivot = Offset.Zero) { drawImage(frost) }
                }
            }
            drawRect(tint)
        }
        // Keep text, charts and controls recorded independently of the moving glass behind them.
        .graphicsLayer()
}

/* ── The scene ────────────────────────────────────────────────────────────────────────────── */

/** One shape of the scene. Positions and sizes are fractions of the window. */
private class SceneShape(
    from: RoundedPolygon,
    to: RoundedPolygon?,
    val cx: Float,
    val cy: Float,
    val size: Float,
    /** 0 = far back, 1 = right behind the glass. */
    val depth: Float,
    /** Degrees per second; the sign sets the direction. */
    val spin: Float,
    val morphSeconds: Float = 0f,
) {
    val morph = to?.let { Morph(from, it) }
    val path = Path().also { if (to == null) Morph(from, from).toPath(0f, it) }
}

/** YouCloud's scene, laid out for a window that is wider than it is tall. */
private fun sceneShapes() = listOf(
    SceneShape(MaterialShapes.SoftBurst, null, cx = 0.92f, cy = 0.78f, size = 0.62f, depth = 0.25f, spin = 3f),
    SceneShape(MaterialShapes.Pentagon, null, cx = 0.34f, cy = 0.06f, size = 0.24f, depth = 0.3f, spin = 9f),
    SceneShape(MaterialShapes.Cookie9Sided, null, cx = 0.96f, cy = 0.08f, size = 0.6f, depth = 0.5f, spin = 4f),
    SceneShape(MaterialShapes.Flower, MaterialShapes.Cookie12Sided, cx = 0.62f, cy = 0.46f, size = 0.32f, depth = 0.65f, spin = -8f, morphSeconds = 11f),
    SceneShape(MaterialShapes.Clover4Leaf, null, cx = 0.06f, cy = 0.42f, size = 0.5f, depth = 0.8f, spin = -6f),
    SceneShape(MaterialShapes.Pill, MaterialShapes.Sunny, cx = 0.22f, cy = 0.96f, size = 0.42f, depth = 0.95f, spin = -3f, morphSeconds = 14f),
)

private data class ScenePalette(val ground: Color, val body: Color, val light: Color, val dark: Color, val darkTheme: Boolean)

@Composable
private fun scenePalette(): ScenePalette {
    val scheme = MaterialTheme.colorScheme
    val darkTheme = !isLightTheme()
    return ScenePalette(
        // A ground a step lighter than the app's background, so the shapes and the panels in front
        // of them have something to stand off from instead of sinking into the same near-black.
        ground = if (darkTheme) scheme.surfaceContainer else scheme.surfaceContainer,
        // One family of tones — the palette's own accent — so the scene reads as one material at
        // different depths rather than a handful of coloured stickers, and changes with the palette.
        body = if (darkTheme) lerp(scheme.surfaceContainerHighest, scheme.primaryContainer, 0.85f)
        else lerp(scheme.primaryContainer, scheme.primary, 0.18f),
        light = if (darkTheme) scheme.primary else Color.White,
        dark = if (darkTheme) Color.Black else scheme.primary,
        darkTheme = darkTheme,
    )
}

/**
 * The window's backdrop, with [content] in front of it. Provides [LocalBackdrop] for glass.
 *
 * [animated] off holds the scene still — no frames at all, so nothing is spent on it — and a
 * window that is not focused drifts at a third of the rate: it is still there to be seen, but
 * nobody is looking at it closely.
 */
@Composable
fun ExpressiveBackdrop(animated: Boolean, content: @Composable () -> Unit) {
    val state = remember { BackdropState() }
    val palette = scenePalette()
    val shapes = remember { sceneShapes() }
    val focused = LocalUiFrameProbe.current != null || LocalWindowInfo.current.isWindowFocused
    val active = LocalAnimationsActive.current
    val running = animated && active
    val window = LocalWindowMotion.current
    val density = LocalDensity.current
    val motion = remember { SceneMotion(shapes.size) }
    val timeline = remember { floatArrayOf(0f) }
    val lighting = remember { arrayOf(Offset(-0.55f, -0.75f)) }
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val dispatcher = LocalBackdropDispatcher.current

    DisposableEffect(state) {
        onDispose { state.scene?.close(); state.scene = null }
    }
    LaunchedEffect(dimensions, palette, density, running, focused, active) {
        if (dimensions.width <= 0 || dimensions.height <= 0) return@LaunchedEffect
        if (!active && state.scene != null) return@LaunchedEffect
        var ownedRenderer: SceneRenderer? = null
        var pending: SceneFrame? = null
        try {
            withContext(dispatcher) { ownedRenderer = SceneRenderer(dimensions, density) }
            val renderer = checkNotNull(ownedRenderer)
            var last = 0L
            var lastX = window.x
            var lastY = window.y
            var wasKnown = window.known
            while (isActive) {
                val started = withInfiniteAnimationFrameNanos { now ->
                    val dt = if (last == 0L || !running) 0f else ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                    last = now
                    timeline[0] += dt
                    val x = window.x
                    val y = window.y
                    val moved = running && window.known && wasKnown
                    motion.step(dt, if (moved) (x - lastX) * density.density else 0f,
                        if (moved) (y - lastY) * density.density else 0f, shapes, min(dimensions.width, dimensions.height) * 0.3f)
                    lastX = x; lastY = y; wasKnown = window.known
                    val target = Offset(-0.55f + state.pointer.x * 0.35f, -0.75f + state.pointer.y * 0.3f)
                    lighting[0] += (target - lighting[0]) * (1f - kotlin.math.exp(-2.5f * dt))
                    now
                }
                val time = timeline[0]
                val light = lighting[0].normalised()
                val pointer = state.pointer
                val pose = motion.pose()
                withContext(dispatcher) { pending = renderer.render(palette, time, light, pointer, pose) }
                val previous = state.scene
                state.scene = checkNotNull(pending)
                pending = null
                state.shrink = renderer.shrink
                state.recorded()
                previous?.close()
                if (!running) awaitCancellation()
                val lightMoving = (Offset(-0.55f + pointer.x * 0.35f, -0.75f + pointer.y * 0.3f) - lighting[0]).getDistance() > 0.005f
                val interval = when { motion.active || lightMoving -> 0L; focused -> 33_333_333L; else -> 100_000_000L }
                val remaining = (started + interval - System.nanoTime()) / 1_000_000L
                if (remaining > 0) delay(remaining)
            }
        } finally {
            withContext(NonCancellable + dispatcher) { pending?.close(); ownedRenderer?.close() }
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { dimensions = it }.pointerInput(running) {
        if (!running) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val position = event.changes.firstOrNull()?.position ?: continue
                state.pointer = Offset((position.x / size.width.coerceAtLeast(1)) * 2f - 1f,
                    (position.y / size.height.coerceAtLeast(1)) * 2f - 1f)
            }
        }
    }) {
        Box(Modifier.matchParentSize().graphicsLayer().drawBehind {
            val scene = state.scene
            if (scene == null) drawRect(palette.ground)
            else scale(2f, 2f, pivot = Offset.Zero) { drawImage(scene.image) }
        })
        CompositionLocalProvider(LocalBackdrop provides state, content = content)
    }
}

/** CPU raster work runs away from the UI thread; a new frame is published only when complete. */
internal val LocalBackdropDispatcher = staticCompositionLocalOf<CoroutineDispatcher> { Dispatchers.Default }

internal class SceneFrame(val image: ImageBitmap, val frost: ImageBitmap) : AutoCloseable {
    override fun close() { image.asSkiaBitmap().close(); frost.asSkiaBitmap().close() }
}

private class SceneRenderer(dimensions: IntSize, private val density: Density) : AutoCloseable {
    private val full = Size(dimensions.width.toFloat(), dimensions.height.toFloat())
    private val shapes = sceneShapes()
    private val farIndices = shapes.indices.filter { shapes[it].depth < 0.6f }
    private val nearIndices = shapes.indices.filter { shapes[it].depth >= 0.6f }
    private val far = FrostBuffer(org.jetbrains.skia.FilterTileMode.DECAL)
    private val near = FrostBuffer(org.jetbrains.skia.FilterTileMode.DECAL)
    private val scene = SceneBuffer()
    private val frost = FrostBuffer()
    val shrink = shrinkFor(FrostBlur.value * density.density)
    init {
        far.prepare(scaled(full, 4f), 8f * density.density / 4f)
        near.prepare(scaled(full, 2f), 3f * density.density / 2f)
        scene.prepare(scaled(full, 2f))
        frost.prepare(scaled(full, shrink), FrostBlur.value * density.density / shrink)
    }
    fun render(palette: ScenePalette, time: Float, light: Offset, pointer: Offset, pose: ScenePose): SceneFrame {
        val pixels = density.density
        val farImage = far.record(density, LayoutDirection.Ltr) {
            scale(0.25f, 0.25f, Offset.Zero) { drawScene(shapes, farIndices, palette, time, light, pointer, pose, full, pixels) }
        }
        val nearImage = near.record(density, LayoutDirection.Ltr) {
            scale(0.5f, 0.5f, Offset.Zero) { drawScene(shapes, nearIndices, palette, time, light, pointer, pose, full, pixels) }
        }
        try {
            val image = scene.record(density, LayoutDirection.Ltr) {
                drawRect(palette.ground)
                scale(2f, 2f, Offset.Zero) { drawImage(farImage) }
                drawImage(nearImage)
            }
            try {
                val frosted = frost.record(density, LayoutDirection.Ltr) {
                    scale(2f / shrink, 2f / shrink, Offset.Zero) { drawImage(image) }
                }
                return SceneFrame(image, frosted)
            } catch (error: Throwable) { image.asSkiaBitmap().close(); throw error }
        } finally { farImage.asSkiaBitmap().close(); nearImage.asSkiaBitmap().close() }
    }
    override fun close() { far.close(); near.close(); scene.close(); frost.close() }
}

private fun shrinkFor(blurPx: Float): Float = (blurPx / 6f).toInt().coerceIn(1, 8).toFloat()

private fun scaled(size: Size, by: Float) = IntSize(ceil(size.width / by).toInt().coerceAtLeast(1), ceil(size.height / by).toInt().coerceAtLeast(1))

private fun Offset.normalised(): Offset {
    val length = sqrt(x * x + y * y).takeIf { it > 0f } ?: return Offset(0f, -1f)
    return Offset(x / length, y / length)
}

/**
 * The shapes at [indices], at their places in a window of [full] size, each lit from [light]'s side:
 * a shadow thrown away from it, the lit face to the shaded one, a soft highlight and a rim of light
 * along the lit edge.
 */
private fun DrawScope.drawScene(
    shapes: List<SceneShape>,
    indices: List<Int>,
    palette: ScenePalette,
    time: Float,
    light: Offset,
    pointer: Offset,
    motion: ScenePose,
    full: Size,
    density: Float,
) {
    // [density] is handed in rather than read off this scope: inside a layer being recorded the
    // scope's own density points back at the node drawing it, and reading it never returns.
    val unit = min(full.width, full.height)
    for (i in indices) {
        val shape = shapes[i]
        val diameter = shape.size * unit
        val radius = diameter / 2f
        // Parallax: nearer shapes slide further when the pointer moves, up to 34 dp.
        val parallax = 34f * density * shape.depth
        val center = Offset(
            shape.cx * full.width - pointer.x * parallax + sin(time * 0.11f + i * 2.1f) * 14f * density * shape.depth + motion.dx[i],
            shape.cy * full.height - pointer.y * parallax + sin(time * 0.35f + i * 1.7f) * 6f * density * shape.depth + motion.dy[i],
        )
        val progress = if (shape.morphSeconds > 0f) (sin(time / shape.morphSeconds * 2f * Math.PI.toFloat()) + 1f) / 2f else 0f
        shape.morph?.let { morph ->
            shape.path.reset()
            morph.toPath(progress, shape.path)
        }
        val angle = time * shape.spin + i * 37f + motion.turn[i]

        val bodyColor = lerp(palette.ground, palette.body, 0.45f + 0.55f * shape.depth)
        val lit = lerp(bodyColor, palette.light, if (palette.darkTheme) 0.22f else 0.5f)
        val shaded = lerp(bodyColor, palette.dark, if (palette.darkTheme) 0.3f else 0.16f)

        fun place(block: DrawScope.() -> Unit) = withTransform({
            translate(center.x - radius, center.y - radius)
            rotate(angle, pivot = Offset(radius, radius))
            scale(diameter, diameter, pivot = Offset.Zero)
        }, block)

        // A shadow thrown away from the light; nearer shapes float higher, so theirs falls further.
        val distance = (8f + 20f * shape.depth) * density
        translate(-light.x * distance, -light.y * distance) {
            place { drawPath(shape.path, Color.Black.copy(alpha = if (palette.darkTheme) 0.38f else 0.12f)) }
        }
        // The light is one light for the whole scene, so it is turned into each shape's own box:
        // whatever the shape's rotation, its lit side faces the same way as every other's.
        val local = rotated(light, -angle)
        val middle = Offset(0.5f, 0.5f)
        place {
            // Lit face to shaded, across the whole shape from the light's side.
            drawPath(shape.path, Brush.linearGradient(listOf(lit, bodyColor, shaded), start = middle + local * 0.5f, end = middle - local * 0.5f))
            // A soft highlight toward the light, inside the shape.
            drawPath(
                shape.path,
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = (if (palette.darkTheme) 0.10f else 0.22f) * (0.4f + shape.depth)), Color.Transparent),
                    center = middle + local * 0.225f,
                    radius = 0.425f,
                ),
            )
            // A rim of light along the lit edge, gone by the middle.
            drawPath(
                shape.path,
                Brush.linearGradient(listOf(lit.copy(alpha = 0.7f), Color.Transparent), start = middle + local * 0.5f, end = middle),
                style = Stroke(width = 1.5f * density / diameter),
            )
        }
    }
}

/** [v] turned by [degrees]: the light's direction as the shape's own box sees it. */
private fun rotated(v: Offset, degrees: Float): Offset {
    val r = Math.toRadians(degrees.toDouble())
    val c = kotlin.math.cos(r).toFloat()
    val s = kotlin.math.sin(r).toFloat()
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}
