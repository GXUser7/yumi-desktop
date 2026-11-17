package com.yumi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.mydrop.vpn.core.model.ThemeMode
import com.mydrop.vpn.core.model.Visualizer
import com.mydrop.vpn.data.Strings
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.*
import kotlinx.coroutines.*
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val launchedAt = System.nanoTime()
    val benchmark = "--ui-benchmark" in args
    val preview = "--ui-preview" in args
    val demo = "--ui-demo" in args || benchmark || preview
    val smoke = "--ui-smoke" in args || demo
    if (smoke) {
        val name = if (demo) "ui-demo" else "ui-smoke"
        val localDir = File(".local", name).absoluteFile
        val smokeDir = if (isWritableDirectory(localDir)) localDir
        else File(System.getProperty("java.io.tmpdir"), "yumi-$name")
        System.setProperty("yumi.dataDir", smokeDir.absolutePath)
    }
    val directory = DesktopPlatform.dataDirectory()
    val lockFile = RandomAccessFile(File(directory, "instance.lock"), "rw")
    val lock = lockFile.channel.tryLock()
    if (lock == null) {
        File(directory, "activate").writeText(System.nanoTime().toString())
        lockFile.close()
        return
    }
    runCatching { Files.deleteIfExists(File(directory, "activate").toPath()) }
    if (demo) seedDemo(directory, if (benchmark) 600 else 6)
    val backend = when {
        demo -> DemoBackend()
        smoke -> object:TunnelBackend {
            override suspend fun request(request:ServiceRequest)=ServiceStatus(state="unavailable",error="service_unavailable")
        }
        else -> if (DesktopPlatform.isWindows) WindowsTunnelBackend() else UnixTunnelBackend()
    }
    val model=DesktopModel(backend,directory)
    application {
        val traySupported = remember { java.awt.SystemTray.isSupported() }
        var trayFailed by remember { mutableStateOf(false) }
        var visible by remember { mutableStateOf("--tray" !in args || !traySupported) }
        var smokePage by remember{mutableStateOf(Page.Connect)}
        val frameProbe = remember { if (benchmark) UiFrameProbe(launchedAt) else null }
        val scope=rememberCoroutineScope()
        val settings by model.settings.settings.collectAsState()
        val status by model.status.collectAsState()
        var closing by remember { mutableStateOf(false) }
        suspend fun launchReadyUpdate(@Suppress("UNUSED_PARAMETER") relaunch: Boolean): Boolean {
            val ready = model.updates.readyToInstall() ?: return false
            return withContext(Dispatchers.IO) { DesktopPlatform.installUpdate(ready.file) }
        }
        val close = {
            if (!closing) {
                closing = true
                scope.launch {
                    try {
                        val shouldUpdate = !smoke && model.desktop.value.autoUpdate && model.updates.installed
                        model.close()
                        if (shouldUpdate) runCatching { launchReadyUpdate(relaunch = false) }
                    } finally {
                        runCatching { lock.release() }
                        runCatching { lockFile.close() }
                        exitApplication()
                    }
                }
            }
            Unit
        }
        val installUpdate = {
            if (!closing) {
                closing = true
                scope.launch {
                    val launched = runCatching { launchReadyUpdate(relaunch = true) }.getOrDefault(false)
                    if (launched) {
                        try { model.close() } finally {
                            runCatching { lock.release() }
                            runCatching { lockFile.close() }
                            exitApplication()
                        }
                    } else closing = false
                }
            }
            Unit
        }
        // The app's own mark, the one the phone wears, as the window icon, the tray icon and —
        // through the .ico beside it in the build script — the icon on the executable itself.
        val icon=remember(::yumiMark)
        val captureSize = args.firstOrNull { it.startsWith("--ui-size=") }?.substringAfter('=')?.split('x')
        val captureWidth = captureSize?.getOrNull(0)?.toIntOrNull()?.coerceIn(680, 2560) ?: 1280
        val captureHeight = captureSize?.getOrNull(1)?.toIntOrNull()?.coerceIn(600, 1600) ?: 880
        val windowState=rememberWindowState(width=captureWidth.dp,height=captureHeight.dp)
        var activation by remember { mutableIntStateOf(0) }
        val open = {
            visible = true
            windowState.isMinimized = false
            activation++
            Unit
        }
        if (traySupported && !smoke && !trayFailed) YumiTray(
            icon = icon,
            onOpen = open,
            onTrayFailed = { trayFailed = true; visible = true },
        ) {
            val profiles by model.profiles.state.collectAsState()
            val activeNode by model.activeNode.collectAsState()
            val pinging by model.pinging.collectAsState()
            val switching by model.switching.collectAsState()
            val language = settings.language
            val strings = remember(language) { Strings { language } }
            val activeNodeId = (activeNode?.id ?: status.nodeId).takeIf { status.state in listOf("connected", "connecting", "disconnecting") }
            Item(strings.text("Открыть Yumi","Open Yumi"),onClick=open)
            Item(if(status.state in listOf("connected","connecting"))strings.text("Отключить","Disconnect") else strings.text("Подключиться","Connect"),
                enabled=status.state!="unavailable",onClick={model.toggleConnection()})
            Separator()
            TrayServers(
                profiles = profiles,
                strings = strings,
                pinging = pinging,
                switching = switching || status.state == "connecting",
                activeNodeId = activeNodeId,
                onSelect = { model.selectNode(it) },
                onOpen = open,
                onPing = model::pingAll,
            )
            Separator();Item(strings.text("Выйти","Exit"),onClick=close)
        }
        Window(onCloseRequest={if(traySupported&&!smoke&&!trayFailed)visible=false else close()},visible=visible,title="Yumi Desktop",state=windowState,icon=icon) {
            LaunchedEffect(Unit){window.minimumSize=Dimension(680,600);if(smoke){window.isAlwaysOnTop=true;window.toFront()}}
            LaunchedEffect(activation) { if (activation > 0) restoreApplicationWindow(window) }
            // The title bar is drawn by Windows, not by us, so the only way it stops being the one
            // undesigned strip of the window is to hand DWM the colours the app is using. Keyed on
            // the palette and the theme, so it follows both.
            val desktop by model.desktop.state.collectAsState()
            val dark = isDark(settings)
            val chrome = desktop.palette.scheme(dark).let {
                if (dark && settings.amoled) it.toAmoled(desktop.palette.hue, desktop.palette.chroma) else it
            }
            LaunchedEffect(dark, chrome.background, chrome.onSurfaceVariant) {
                DesktopPlatform.applyWindowChrome(window.windowHandle, dark, chrome.background.toArgb(), chrome.onSurfaceVariant.toArgb())
            }
            // The window's place on the screen, for the backdrop: dragging the window knocks the
            // shapes behind the glass about. See SceneMotion in Glass.kt.
            val windowMotion = remember { WindowMotion() }
            DisposableEffect(window) {
                val listener = object : java.awt.event.ComponentAdapter() {
                    override fun componentMoved(e: java.awt.event.ComponentEvent) {
                        windowMotion.x = window.x; windowMotion.y = window.y; windowMotion.known = true
                    }
                }
                windowMotion.x = window.x; windowMotion.y = window.y; windowMotion.known = true
                window.addComponentListener(listener)
                onDispose { window.removeComponentListener(listener) }
            }
            CompositionLocalProvider(
                LocalWindowMotion provides windowMotion,
                LocalAnimationsActive provides (visible && !windowState.isMinimized),
                LocalUiFrameProbe provides frameProbe,
            ) { YumiApp(model,smokePage, onInstallUpdate = installUpdate) }
            if (benchmark) DisposableEffect(window) {
                val detach = checkNotNull(frameProbe).attach(window)
                onDispose(detach)
            }
            LaunchedEffect(directory) {
                val activation = File(directory, "activate").toPath()
                while (isActive) {
                    delay(500)
                    val triggered = withContext(Dispatchers.IO) { runCatching { Files.deleteIfExists(activation) }.getOrDefault(false) }
                    if (triggered) open()
                }
            }
            if(benchmark) LaunchedEffect(Unit) {
                benchmarkUi(model, checkNotNull(frameProbe), { smokePage = it }, { visible = it })
                close()
            }
            if(smoke && !benchmark && !preview) LaunchedEffect(Unit) {
                val output=File(".local/screenshots").also(File::mkdirs)
                val prefix=if(demo) "demo" else "smoke"
                fun capture(name: String) {
                    val rectangle=Rectangle(window.contentPane.locationOnScreen,window.contentPane.size)
                    ImageIO.write(Robot().createScreenCapture(rectangle),"png",File(output,"$name.png"))
                }
                if(demo && "--ui-idle" !in args){delay(1500);model.toggleConnection();delay(3500)}
                if ("--ui-resume" in args) {
                    model.settings.update { it.copy(visualizer = Visualizer.Waves, themeMode = ThemeMode.Dark) }
                    delay(1500)
                    capture("waves-before-hide")
                    visible = false
                    delay(5000)
                    visible = true
                    delay(100)
                    capture("waves-after-resume")
                    delay(500)
                    capture("waves-resumed-moving")
                }
                if ("--ui-palettes" in args) {
                    model.settings.update { it.copy(visualizer = Visualizer.Waves) }
                    for ((mode, amoled) in listOf(ThemeMode.Dark to false, ThemeMode.Dark to true, ThemeMode.Light to false)) {
                        model.settings.update { it.copy(themeMode = mode, amoled = amoled) }
                        for (palette in Palette.entries) {
                            model.desktop.update { it.copy(palette = palette) }
                            delay(400)
                            capture("palette-${mode.name}-${if (amoled) "AMOLED" else "standard"}-${palette.name}")
                        }
                    }
                }
                if ("--ui-resume" in args || "--ui-palettes" in args) { close(); return@LaunchedEffect }
                if ("--ui-resize" in args) {
                    for ((width, height, name) in listOf(Triple(680, 600, "compact"), Triple(1280, 880, "wide"))) {
                        windowState.size = DpSize(width.dp, height.dp)
                        delay(90)
                        capture("resize-$name-transition")
                        delay(350)
                        capture("resize-$name-settled")
                    }
                }
                for(theme in listOf(ThemeMode.Dark,ThemeMode.Light)) {
                    model.settings.update{it.copy(themeMode=theme)}
                    for(page in Page.entries){
                        smokePage=page;delay(2000)
                        capture("$prefix-${theme.name}-${page.name}")
                    }
                }
                close()
            }
        }
    }
}

/**
 * The app's own mark, off the classpath: the window icon, the tray icon, and — through the .ico
 * the build script hands jpackage — the icon on the executable itself.
 *
 * The classpath loaders are deprecated in favour of the Compose resources library, which wants a
 * Gradle plugin and a generated accessor for one file that never changes. Suppressed rather than
 * migrated, and kept in one function so that migrating it later is one edit.
 */
@Suppress("DEPRECATION")
private fun yumiMark(): Painter = useResource("icon/yumi.svg") { loadSvgPainter(it, Density(1f)) }

private fun isWritableDirectory(dir: File): Boolean = runCatching {
    if (!dir.exists() && !dir.mkdirs()) return@runCatching false
    if (!dir.isDirectory) return@runCatching false
    val probe = Files.createTempFile(dir.toPath(), ".write-probe-", null)
    Files.deleteIfExists(probe)
    true
}.getOrDefault(false)
