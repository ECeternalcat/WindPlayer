package dev.windplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposePanel
import com.sun.jna.Native
import com.sun.jna.Pointer
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.ui.App
import dev.windplayer.ui.AppScreen
import dev.windplayer.ui.DesktopAppCallbacks
import dev.windplayer.ui.DesktopAppFlows
import dev.windplayer.ui.DesktopAppState
import dev.windplayer.ui.I18n
import dev.windplayer.ui.PlayerSettings
import dev.windplayer.vfs.VfsManager
import dev.windplayer.vfs.SUBTITLE_EXTENSIONS
import dev.windplayer.vfs.VIDEO_EXTENSIONS
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.TransferHandler

fun main(args: Array<String>) {
    // --register: register video file associations in Windows registry.
    if (args.any { it == "--register" } && System.getProperty("os.name").lowercase().contains("windows")) {
        registerVideoExtensions()
        println("Video file extensions registered. You can now open video files with WindPlayer.")
        return
    }

    // If a video file path was passed as an argument, queue it for opening.
    val initialFile = args.firstOrNull { File(it).exists() && File(it).isFile }

    val player = MpvPlayer()
    val vfsManager = VfsManager()
    val osdEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val dropEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val playlistToggle = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cheatsheetToggle = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    Runtime.getRuntime().addShutdownHook(Thread {
        vfsManager.shutdown()
        player.dispose()
    })

    val initialSettings = loadSettings()
    I18n.current = initialSettings.language
    // Apply the colour palette BEFORE the first Compose frame so dark mode
    // doesn't flash light on startup. App.kt keeps it in sync at runtime.
    val initialDark = when (initialSettings.themeMode) {
        dev.windplayer.ui.ThemeMode.LIGHT -> false
        dev.windplayer.ui.ThemeMode.DARK -> true
        dev.windplayer.ui.ThemeMode.SYSTEM -> dev.windplayer.ui.DesktopSystemTheme.isSystemDark()
    }
    dev.windplayer.ui.WindColors.applyDark(initialDark)

    SwingUtilities.invokeLater {
        val frame = JFrame("Wind Player")
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.background = Color.BLACK

        val rootPanel = JPanel()
        rootPanel.layout = null
        rootPanel.background = Color.BLACK

        val videoCanvas = Canvas()
        videoCanvas.background = Color.BLACK
        rootPanel.add(videoCanvas)

        val composePanel = ComposePanel()
        // Match the Swing panel to the resolved theme (set above) so resize/load
        // never reveals a stale cream rectangle in dark mode.
        composePanel.background = Color(
            dev.windplayer.ui.WindColors.CanvasCream.red,
            dev.windplayer.ui.WindColors.CanvasCream.green,
            dev.windplayer.ui.WindColors.CanvasCream.blue
        )
        rootPanel.add(composePanel)

        frame.contentPane.add(rootPanel)
        val savedBounds = loadWindowState()
        if (savedBounds != null) {
            frame.bounds = savedBounds
        } else {
            frame.size = Dimension(1280, 720)
        }

        val layoutManager = LayoutManager(frame, rootPanel, videoCanvas, composePanel)

        // ---------- Drag & drop ----------
        frame.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean =
                support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)

            override fun importData(support: TransferSupport): Boolean {
                if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                val fileList = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                    ?: return false
                for (item in fileList) {
                    val file = item as? File ?: continue
                    val ext = file.name.substringAfterLast('.', "").lowercase()
                    if (ext in VIDEO_EXTENSIONS) {
                        dropEvents.tryEmit(file.absolutePath)
                        return true
                    }
                    if (ext in SUBTITLE_EXTENSIONS && layoutManager.currentScreen == AppScreen.PLAYER) {
                        player.command("sub-add", file.absolutePath)
                        osdEvents.tryEmit(String.format(I18n.get("subtitle_added"), file.name))
                        return true
                    }
                }
                return false
            }
        }

        // ---------- Observable state shared with the App composable ----------
        var fullscreenState by mutableStateOf(false)
        var skipNextCallback by mutableStateOf<(() -> Unit)?>(null)
        var settingsState by mutableStateOf(initialSettings)
        var recentFilesState by mutableStateOf(loadRecentFiles())
        var bookmarksState by mutableStateOf(loadBookmarks())
        layoutManager.onFullscreenChanged = { fullscreenState = it }

        // ---------- Resize ----------
        rootPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                layoutManager.applyLayout()
            }
        })

        // ---------- Mouse interactions ----------
        val mouseController = CanvasMouseController(
            videoCanvas = videoCanvas,
            player = player,
            layoutManager = layoutManager,
            osdEvents = osdEvents,
            playlistToggle = playlistToggle,
            cheatsheetToggle = cheatsheetToggle,
            skipNextCallback = { skipNextCallback }
        )
        mouseController.attach()

        composePanel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent?) {
                layoutManager.cancelHideTimer()
            }
            override fun mouseExited(e: java.awt.event.MouseEvent?) {
                layoutManager.restartHideTimer()
            }
        })

        // ---------- Keyboard shortcuts ----------
        val shortcutCtx = DesktopShortcutContext(
            player = player,
            layoutManager = layoutManager,
            osdEvents = osdEvents,
            playlistToggle = playlistToggle,
            cheatsheetToggle = cheatsheetToggle
        )
        bindDesktopShortcuts(frame.rootPane, shortcutCtx)

        // ---------- Window lifecycle ----------
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent?) {
                videoCanvas.requestFocus()
                val hwnd = Native.getComponentPointer(videoCanvas)
                val wid = Pointer.nativeValue(hwnd)

                player.create()
                player.setOption("wid", wid)
                player.setOption("vo", "gpu")
                player.setOption("hwdec", if (settingsState.hwdecAuto) "auto" else "no")
                player.setOption("keep-open", "yes")
                player.setOption("idle", "yes")
                player.setOption("sub-font-size", settingsState.subFontSize.toString())
                player.setOption("sub-border-size", settingsState.subBorderSize.toString())
                player.initialize()

                layoutManager.switchTo(AppScreen.BROWSER)
            }

            override fun windowClosing(e: WindowEvent?) {
                // try/finally: any exception in saveWindowState/shutdown must
                // not skip player.dispose() (would leak the mpv context + JNI
                // memory + event thread).
                try {
                    saveWindowState(frame)
                    vfsManager.shutdown()
                } finally {
                    player.dispose()
                }
            }
        })

        // ---------- Compose content ----------
        composePanel.setContent {
            App(
                state = DesktopAppState(
                    player = player,
                    vfsManager = vfsManager,
                    settings = settingsState,
                    isFullscreen = fullscreenState,
                    recentFiles = recentFilesState,
                    bookmarks = bookmarksState
                ),
                callbacks = object : DesktopAppCallbacks {
                    override fun onScreenChange(screen: AppScreen) = layoutManager.switchTo(screen)
                    override fun onTracksToggle(expanded: Boolean) = layoutManager.setTracksExpanded(expanded)
                    override fun onToggleFullscreen() = layoutManager.toggleFullscreen()
                    override fun onOsdEmit(text: String) { osdEvents.tryEmit(text) }
                    override fun onSkipNextRegistered(callback: () -> Unit) {
                        skipNextCallback = callback
                        shortcutCtx.skipNextCallback = callback
                    }
                    override fun onSettingsChanged(newSettings: PlayerSettings) {
                        settingsState = newSettings
                        I18n.current = newSettings.language
                        saveSettings(newSettings)
                        applyMpvSettings(player, newSettings)
                    }
                    override fun onFilePlayed(name: String, path: String, isLocal: Boolean, serverId: String?) {
                        recentFilesState = updateRecentFiles(recentFilesState, name, path, isLocal, serverId)
                        saveRecentFiles(recentFilesState)
                    }
                    override fun onPositionUpdate(filePath: String, position: Double, duration: Double) {
                        recentFilesState = updateRecentPosition(recentFilesState, filePath, position, duration)
                        saveRecentFiles(recentFilesState)
                    }
                    override fun onBookmarkAdded(path: String) {
                        if (bookmarksState.none { it == path }) {
                            bookmarksState = bookmarksState + path
                            saveBookmarks(bookmarksState)
                        }
                    }
                    override fun onBookmarkRemoved(path: String) {
                        bookmarksState = bookmarksState.filterNot { it == path }
                        saveBookmarks(bookmarksState)
                    }
                },
                flows = DesktopAppFlows(
                    osdEvents = osdEvents,
                    dropFilePath = dropEvents,
                    playlistToggle = playlistToggle,
                    cheatsheetToggle = cheatsheetToggle
                )
            )
        }

        frame.isVisible = true

        // Open file passed via command line (e.g. double-click in explorer).
        if (initialFile != null) {
            dropEvents.tryEmit(initialFile)
        }

        // No Thread.currentThread().join() — self-join throws IllegalArgumentException
        // and the non-daemon Swing EDT keeps the JVM alive on its own.
    }
}

/**
 * Register common video file extensions to "Open With" WindPlayer on Windows
 * by writing to HKEY_CURRENT_USER\Software\Classes.
 */
private fun registerVideoExtensions() {
    val appPath = System.getProperty("java.class.path")
        .split(java.io.File.pathSeparator).firstOrNull()
        ?.let { java.io.File(it).absolutePath }
        ?: return

    // For a packaged app, the executable is the launcher; for dev, it's the jar.
    // We register the java command that launches the app.
    val exePath = if (appPath.endsWith(".jar")) {
        "javaw -jar \"$appPath\" \"%1\""
    } else {
        "\"$appPath\" \"%1\""
    }

    val extensions = VIDEO_EXTENSIONS
    val keyRoot = "HKCU\\Software\\Classes"
    val appName = "WindPlayer"

    fun regAdd(key: String, value: String) {
        val cmd = arrayOf("reg", "add", key, "/ve", "/d", value, "/f")
        Runtime.getRuntime().exec(cmd).waitFor()
    }
    fun regAddVal(key: String, name: String, value: String) {
        val cmd = arrayOf("reg", "add", key, "/v", name, "/d", value, "/f")
        Runtime.getRuntime().exec(cmd).waitFor()
    }

    // Register the application ProgID
    regAdd("$keyRoot\\$appName\\shell\\open\\command", exePath)
    regAdd("$keyRoot\\$appName", "WindPlayer Video")

    // Associate each extension
    for (ext in extensions) {
        val extKey = "$keyRoot\\.$ext"
        regAddVal(extKey, "PerceivedType", "video")
        regAdd("$extKey\\OpenWithProgids", appName)
    }
}
