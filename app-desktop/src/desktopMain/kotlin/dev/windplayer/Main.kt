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

fun main() {
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
        composePanel.background = Color(0xFF0F0F1A.toInt())
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
                        osdEvents.tryEmit("Subtitle added: ${file.name}")
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
                saveWindowState(frame)
                vfsManager.shutdown()
                player.dispose()
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
    }

    Thread.currentThread().join()
}
