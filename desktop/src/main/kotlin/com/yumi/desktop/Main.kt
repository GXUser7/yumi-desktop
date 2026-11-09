package com.yumi.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.mydrop.vpn.core.model.ThemeMode
import com.yumi.desktop.model.*
import com.yumi.desktop.platform.*
import kotlinx.coroutines.*
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.io.RandomAccessFile
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    val benchmark = "--ui-benchmark" in args
    val preview = "--ui-preview" in args
    val demo = "--ui-demo" in args || benchmark || preview
    val smoke = "--ui-smoke" in args || demo
    if(smoke) System.setProperty("yumi.dataDir",File(if(demo) ".local/ui-demo" else ".local/ui-smoke").absolutePath)
    val directory = DesktopPlatform.dataDirectory()
    if(demo) seedDemo(directory, if (benchmark) 600 else 6)
    val lockFile = RandomAccessFile(File(directory,"instance.lock"),"rw")
    val lock = lockFile.channel.tryLock()
    if(lock==null){File(directory,"activate").writeText(System.nanoTime().toString());lockFile.close();return}
    val backend = when {
        demo -> DemoBackend()
        smoke -> object:TunnelBackend {
            override suspend fun request(request:ServiceRequest)=ServiceStatus(state="unavailable",error="service_unavailable")
        }
        else -> WindowsTunnelBackend()
    }
    val model=DesktopModel(backend,directory)
    application {
        var visible by remember{mutableStateOf("--tray" !in args)}
        var smokePage by remember{mutableStateOf(Page.Connect)}
        val frameProbe = remember { if (benchmark) UiFrameProbe() else null }
        val scope=rememberCoroutineScope()
        val settings by model.settings.settings.collectAsState()
        val status by model.status.collectAsState()
        var closing by remember { mutableStateOf(false) }
        suspend fun launchReadyUpdate(): Boolean {
            val ready = model.updates.readyToInstall() ?: return false
            return withContext(Dispatchers.IO) { DesktopPlatform.installUpdate(ready.file) }
        }
        val close = {
            if (!closing) {
                closing = true
                scope.launch {
                    try {
                        if (!smoke && model.desktop.value.autoUpdate && model.updates.installed) runCatching { launchReadyUpdate() }
                        model.close()
                    } finally { lock.release(); lockFile.close(); exitApplication() }
                }
            }
            Unit
        }
        val installUpdate = {
            if (!closing) {
                closing = true
                scope.launch {
                    if (launchReadyUpdate()) {
                        try { model.close() } finally { lock.release(); lockFile.close(); exitApplication() }
                    } else closing = false
                }
            }
            Unit
        }
        // The app's own mark, the one the phone wears, as the window icon, the tray icon and —
        // through the .ico beside it in the build script — the icon on the executable itself.
        val icon=remember(::yumiMark)
        val traySupported = java.awt.SystemTray.isSupported()
        if(traySupported && !smoke) Tray(icon=icon,tooltip="Yumi",onAction={visible=true}) {
            Item(model.text("Открыть Yumi","Open Yumi"),onClick={visible=true})
            Item(if(status.state in listOf("connected","connecting"))model.text("Отключить","Disconnect") else model.text("Подключиться","Connect"),
                enabled=status.state!="unavailable",onClick={model.toggleConnection()})
            Separator();Item(model.text("Выйти","Exit"),onClick=close)
        }
        val windowState=rememberWindowState(width=1280.dp,height=880.dp)
        Window(onCloseRequest={if(traySupported&&!smoke)visible=false else close()},visible=visible,title="Yumi Desktop",state=windowState,icon=icon) {
            LaunchedEffect(Unit){window.minimumSize=Dimension(680,600);if(smoke){window.isAlwaysOnTop=true;window.toFront()}}
            // The title bar is drawn by Windows, not by us, so the only way it stops being the one
            // undesigned strip of the window is to hand DWM the colours the app is using. Keyed on
            // the palette and the theme, so it follows both.
            val desktop by model.desktop.state.collectAsState()
            val dark = isDark(settings)
            val chrome = desktop.palette.scheme(dark)
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
            LaunchedEffect(Unit) {
                val activation=File(directory,"activate");var stamp=activation.lastModified()
                while(isActive){delay(500);val next=activation.lastModified();if(next!=stamp){stamp=next;visible=true;window.toFront();window.requestFocus()}}
            }
            if(benchmark) LaunchedEffect(Unit) {
                benchmarkUi(model, checkNotNull(frameProbe), { smokePage = it }, { visible = it })
                close()
            }
            if(smoke && !benchmark && !preview) LaunchedEffect(Unit) {
                val output=File(".local/screenshots").also(File::mkdirs)
                val prefix=if(demo) "demo" else "smoke"
                if(demo){delay(1500);model.toggleConnection();delay(3500)}
                for(theme in listOf(ThemeMode.Dark,ThemeMode.Light)) {
                    model.settings.update{it.copy(themeMode=theme)}
                    for(page in Page.entries){
                        smokePage=page;delay(2000)
                        val rectangle=Rectangle(window.contentPane.locationOnScreen,window.contentPane.size)
                        ImageIO.write(Robot().createScreenCapture(rectangle),"png",File(output,"$prefix-${theme.name}-${page.name}.png"))
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
