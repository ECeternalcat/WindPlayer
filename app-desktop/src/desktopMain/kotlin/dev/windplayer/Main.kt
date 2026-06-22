package dev.windplayer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposePanel
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.ui.App
import dev.windplayer.ui.AppScreen
import dev.windplayer.ui.I18n
import dev.windplayer.ui.PlayerSettings
import dev.windplayer.ui.RecentFile
import dev.windplayer.vfs.VfsManager
import dev.windplayer.vfs.VIDEO_EXTENSIONS
import dev.windplayer.vfs.SUBTITLE_EXTENSIONS
import dev.windplayer.vfs.formatDuration
import dev.windplayer.vfs.formatDurationOsd
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.awt.Canvas
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.TransferHandler

private const val GWL_STYLE = -16
private const val WS_CAPTION = 0x00C00000
private const val WS_THICKFRAME = 0x00040000
private const val WS_SYSMENU = 0x00080000
private const val WS_MAXIMIZEBOX = 0x00010000
private const val WS_MINIMIZEBOX = 0x00020000
private const val SWP_NOSIZE = 0x0001
private const val SWP_NOMOVE = 0x0002
private const val SWP_FRAMECHANGED = 0x0020

private interface Win32Api : Library {
    companion object {
        val INSTANCE: Win32Api by lazy { Native.load("user32", Win32Api::class.java) }
        val HWND_TOPMOST: Pointer = Pointer(-1L)
        val HWND_NOTOPMOST: Pointer = Pointer(-2L)
    }

    fun GetWindowLongW(hWnd: Pointer, nIndex: Int): Int
    fun SetWindowLongW(hWnd: Pointer, nIndex: Int, dwNewLong: Int): Int
    fun SetWindowPos(hWnd: Pointer, hWndInsertAfter: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
}

private val WINDOW_STATE_FILE: String by lazy {
    File(System.getProperty("user.home"), ".windplayer/window.properties").absolutePath
}

private fun loadWindowState(): Rectangle? {
    return try {
        val props = Properties()
        FileInputStream(WINDOW_STATE_FILE).use { props.load(it) }
        val x = props.getProperty("x")?.toIntOrNull() ?: return null
        val y = props.getProperty("y")?.toIntOrNull() ?: return null
        val w = props.getProperty("width")?.toIntOrNull() ?: return null
        val h = props.getProperty("height")?.toIntOrNull() ?: return null
        if (w < 400 || h < 300) return null
        val bounds = Rectangle(x, y, w, h)
        val screenBounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice.defaultConfiguration.bounds
        if (screenBounds.intersects(bounds)) bounds else null
    } catch (_: Exception) { null }
}

private fun saveWindowState(frame: JFrame) {
    try {
        if (frame.extendedState != JFrame.NORMAL) return
        val bounds = frame.bounds
        val dir = File(WINDOW_STATE_FILE).parentFile
        if (!dir.exists()) dir.mkdirs()
        val props = Properties()
        props.setProperty("x", bounds.x.toString())
        props.setProperty("y", bounds.y.toString())
        props.setProperty("width", bounds.width.toString())
        props.setProperty("height", bounds.height.toString())
        FileOutputStream(WINDOW_STATE_FILE).use { props.store(it, "WindPlayer Window State") }
    } catch (_: Exception) {}
}

private val SETTINGS_FILE: String by lazy {
    File(System.getProperty("user.home"), ".windplayer/settings.properties").absolutePath
}

private val RECENT_FILE: String by lazy {
    File(System.getProperty("user.home"), ".windplayer/recent.properties").absolutePath
}

private val BOOKMARKS_FILE: String by lazy {
    File(System.getProperty("user.home"), ".windplayer/bookmarks.properties").absolutePath
}

private fun loadBookmarks(): List<String> {
    return try {
        val props = Properties()
        FileInputStream(BOOKMARKS_FILE).use { props.load(it) }
        val count = props.getProperty("count")?.toIntOrNull() ?: 0
        (0 until count).mapNotNull { i ->
            props.getProperty("bookmark.$i")?.takeIf { it.isNotBlank() }
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveBookmarks(bookmarks: List<String>) {
    try {
        val dir = File(BOOKMARKS_FILE).parentFile
        if (!dir.exists()) dir.mkdirs()
        val props = Properties()
        props.setProperty("count", bookmarks.size.toString())
        bookmarks.forEachIndexed { i, path ->
            props.setProperty("bookmark.$i", path)
        }
        FileOutputStream(BOOKMARKS_FILE).use { props.store(it, "WindPlayer Bookmarks") }
    } catch (_: Exception) {}
}

private fun loadSettings(): PlayerSettings {
    return try {
        val props = Properties()
        FileInputStream(SETTINGS_FILE).use { props.load(it) }
        PlayerSettings(
            defaultVolume = props.getProperty("defaultVolume")?.toIntOrNull() ?: 100,
            hwdecAuto = props.getProperty("hwdecAuto")?.toBooleanStrictOrNull() ?: true,
            subFontSize = props.getProperty("subFontSize")?.toIntOrNull() ?: 55,
            subBorderSize = props.getProperty("subBorderSize")?.toIntOrNull() ?: 3,
            autoPlayNext = props.getProperty("autoPlayNext")?.toBooleanStrictOrNull() ?: true,
            language = props.getProperty("language") ?: "en"
        )
    } catch (_: Exception) { PlayerSettings() }
}

private fun saveSettings(settings: PlayerSettings) {
    try {
        val dir = File(SETTINGS_FILE).parentFile
        if (!dir.exists()) dir.mkdirs()
        val props = Properties()
        props.setProperty("defaultVolume", settings.defaultVolume.toString())
        props.setProperty("hwdecAuto", settings.hwdecAuto.toString())
        props.setProperty("subFontSize", settings.subFontSize.toString())
        props.setProperty("subBorderSize", settings.subBorderSize.toString())
        props.setProperty("autoPlayNext", settings.autoPlayNext.toString())
        props.setProperty("language", settings.language)
        FileOutputStream(SETTINGS_FILE).use { props.store(it, "WindPlayer Settings") }
    } catch (_: Exception) {}
}

private fun applyMpvSettings(player: MpvPlayer, settings: PlayerSettings) {
    try {
        player.setProperty("sub-font-size", settings.subFontSize.toString())
        player.setProperty("sub-border-size", settings.subBorderSize.toString())
        player.setProperty("volume", settings.defaultVolume.toString())
        player.setProperty("hwdec", if (settings.hwdecAuto) "auto" else "no")
    } catch (_: Exception) {}
}

private fun loadRecentFiles(): List<RecentFile> {
    return try {
        val props = Properties()
        FileInputStream(RECENT_FILE).use { props.load(it) }
        val count = props.getProperty("count")?.toIntOrNull() ?: 0
        (0 until count).mapNotNull { i ->
            val parts = props.getProperty("recent.$i")?.split("|") ?: return@mapNotNull null
            if (parts.size < 3) return@mapNotNull null
            RecentFile(
                name = parts[0],
                path = parts[1],
                isLocal = parts[2].toBoolean(),
                serverId = parts.getOrNull(3)?.ifBlank { null },
                timestamp = parts.getOrNull(4)?.toLongOrNull() ?: System.currentTimeMillis(),
                position = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0,
                duration = parts.getOrNull(6)?.toDoubleOrNull() ?: 0.0
            )
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveRecentFiles(files: List<RecentFile>) {
    try {
        val dir = File(RECENT_FILE).parentFile
        if (!dir.exists()) dir.mkdirs()
        val props = Properties()
        props.setProperty("count", files.size.toString())
        files.forEachIndexed { i, f ->
            props.setProperty("recent.$i", "${f.name}|${f.path}|${f.isLocal}|${f.serverId ?: ""}|${f.timestamp}|${f.position}|${f.duration}")
        }
        FileOutputStream(RECENT_FILE).use { props.store(it, "WindPlayer Recent Files") }
    } catch (_: Exception) {}
}

private fun updateRecentFiles(
    current: List<RecentFile>,
    name: String,
    path: String,
    isLocal: Boolean,
    serverId: String?
): List<RecentFile> {
    val existing = current.find { it.path == path }
    val filtered = current.filterNot { it.path == path }
    val entry = RecentFile(
        name = name,
        path = path,
        isLocal = isLocal,
        serverId = serverId,
        timestamp = System.currentTimeMillis(),
        position = existing?.position ?: 0.0,
        duration = existing?.duration ?: 0.0
    )
    return (listOf(entry) + filtered).take(20)
}

private fun updateRecentPosition(
    current: List<RecentFile>,
    path: String,
    position: Double,
    duration: Double
): List<RecentFile> {
    return current.map { f ->
        if (f.path == path) {
            f.copy(
                position = if (position > 0) position else f.position,
                duration = if (duration > 0) duration else f.duration
            )
        } else f
    }
}

class LayoutManager(
    private val frame: JFrame,
    private val rootPanel: JPanel,
    private val videoCanvas: Canvas,
    private val composePanel: ComposePanel
) {
    var currentScreen: AppScreen = AppScreen.BROWSER
        private set
    var tracksExpanded = false
        private set
    var isFullscreen = false
        private set
    var isPip = false
        private set
    var onFullscreenChanged: ((Boolean) -> Unit)? = null

    private var controlsVisible = true
    private var hideTimer: Timer? = null
    private var singleClickTimer: Timer? = null
    private var savedStyle = 0
    private var savedBounds: Rectangle? = null
    private var pipWidth = 480
    private var pipHeight = 270
    private var dragStartScreen: Point? = null
    private var dragWindowStart: Point? = null
    private val hiddenCursor: Cursor by lazy {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        Toolkit.getDefaultToolkit().createCustomCursor(img, Point(0, 0), "hidden")
    }

    fun switchTo(screen: AppScreen) {
        currentScreen = screen
        tracksExpanded = false
        controlsVisible = true
        hideTimer?.stop()
        hideTimer = null
        singleClickTimer?.stop()
        singleClickTimer = null
        if ((screen == AppScreen.BROWSER || screen == AppScreen.SETTINGS)) {
            if (isFullscreen) exitFullscreen()
            if (isPip) exitPip()
        }
        applyLayout()
    }

    fun setTracksExpanded(expanded: Boolean) {
        tracksExpanded = expanded
        if (expanded) {
            controlsVisible = true
            hideTimer?.stop()
        }
        applyLayout()
    }

    fun toggleFullscreen() {
        if (currentScreen != AppScreen.PLAYER) return
        if (isPip) exitPip()
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    fun togglePip() {
        if (currentScreen != AppScreen.PLAYER) return
        if (isFullscreen) exitFullscreen()
        if (isPip) exitPip() else enterPip()
    }

    private fun enterFullscreen() {
        isFullscreen = true
        onFullscreenChanged?.invoke(true)

        val hwnd = Native.getComponentPointer(frame)
        savedStyle = Win32Api.INSTANCE.GetWindowLongW(hwnd, GWL_STYLE)
        savedBounds = frame.bounds

        val strip = WS_CAPTION or WS_THICKFRAME or WS_SYSMENU or WS_MAXIMIZEBOX or WS_MINIMIZEBOX
        Win32Api.INSTANCE.SetWindowLongW(hwnd, GWL_STYLE, savedStyle and strip.inv())
        Win32Api.INSTANCE.SetWindowPos(hwnd, Win32Api.HWND_TOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE)

        frame.bounds = frame.graphicsConfiguration.bounds

        controlsVisible = true
        resetHideTimer()
        applyLayout()
    }

    fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        onFullscreenChanged?.invoke(false)
        hideTimer?.stop()
        hideTimer = null

        val hwnd = Native.getComponentPointer(frame)
        Win32Api.INSTANCE.SetWindowLongW(hwnd, GWL_STYLE, savedStyle)
        Win32Api.INSTANCE.SetWindowPos(hwnd, Win32Api.HWND_NOTOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE)

        frame.bounds = savedBounds ?: Rectangle(100, 100, 1280, 720)

        videoCanvas.cursor = Cursor.getDefaultCursor()
        applyLayout()
    }

    private fun enterPip() {
        isPip = true

        val hwnd = Native.getComponentPointer(frame)
        savedStyle = Win32Api.INSTANCE.GetWindowLongW(hwnd, GWL_STYLE)
        savedBounds = frame.bounds

        val strip = WS_CAPTION or WS_THICKFRAME or WS_SYSMENU or WS_MAXIMIZEBOX or WS_MINIMIZEBOX
        Win32Api.INSTANCE.SetWindowLongW(hwnd, GWL_STYLE, savedStyle and strip.inv())
        Win32Api.INSTANCE.SetWindowPos(hwnd, Win32Api.HWND_TOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE)

        val screenBounds = frame.graphicsConfiguration.bounds
        val x = screenBounds.x + screenBounds.width - pipWidth - 20
        val y = screenBounds.y + screenBounds.height - pipHeight - 20
        frame.bounds = Rectangle(x, y, pipWidth, pipHeight)

        controlsVisible = true
        resetHideTimer()
        applyLayout()
    }

    fun exitPip() {
        if (!isPip) return
        isPip = false
        hideTimer?.stop()
        hideTimer = null

        val hwnd = Native.getComponentPointer(frame)
        Win32Api.INSTANCE.SetWindowLongW(hwnd, GWL_STYLE, savedStyle)
        Win32Api.INSTANCE.SetWindowPos(hwnd, Win32Api.HWND_NOTOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE)

        frame.bounds = savedBounds ?: Rectangle(100, 100, 1280, 720)

        videoCanvas.cursor = Cursor.getDefaultCursor()
        applyLayout()
    }

    fun resizePip(delta: Int) {
        if (!isPip) return
        pipWidth = (pipWidth + delta).coerceIn(320, 960)
        pipHeight = (pipHeight + delta * 9 / 16).coerceIn(180, 540)
        val screenBounds = frame.graphicsConfiguration.bounds
        val x = screenBounds.x + screenBounds.width - pipWidth - 20
        val y = screenBounds.y + screenBounds.height - pipHeight - 20
        frame.bounds = Rectangle(x, y, pipWidth, pipHeight)
        applyLayout()
    }

    fun startDrag(screenPoint: Point) {
        if (!isPip) return
        singleClickTimer?.stop()
        dragStartScreen = screenPoint
        dragWindowStart = frame.location
    }

    fun handleDrag(screenPoint: Point) {
        if (!isPip || dragStartScreen == null || dragWindowStart == null) return
        val dx = screenPoint.x - dragStartScreen!!.x
        val dy = screenPoint.y - dragStartScreen!!.y
        frame.location = Point(dragWindowStart!!.x + dx, dragWindowStart!!.y + dy)
    }

    fun onMouseActivity() {
        if (currentScreen != AppScreen.PLAYER || (!isFullscreen && !isPip)) return
        videoCanvas.cursor = Cursor.getDefaultCursor()
        if (!tracksExpanded && !controlsVisible) {
            controlsVisible = true
            applyLayout()
        }
        resetHideTimer()
    }

    fun cancelHideTimer() {
        hideTimer?.stop()
    }

    fun restartHideTimer() {
        if ((isFullscreen || isPip) && currentScreen == AppScreen.PLAYER) {
            resetHideTimer()
        }
    }

    private fun resetHideTimer() {
        hideTimer?.stop()
        hideTimer = Timer(3000) {
            if (!tracksExpanded && currentScreen == AppScreen.PLAYER && (isFullscreen || isPip)) {
                controlsVisible = false
                videoCanvas.cursor = hiddenCursor
                applyLayout()
            }
        }
        hideTimer?.isRepeats = false
        hideTimer?.start()
    }

    fun handleCanvasClick(player: MpvPlayer, osd: MutableSharedFlow<String>) {
        if (currentScreen != AppScreen.PLAYER) return
        singleClickTimer?.stop()
        singleClickTimer = Timer(250) {
            player.command("cycle", "pause")
            val paused = player.getPropertyString("pause") == "yes"
            osd.tryEmit(if (paused) "|| Paused" else "> Playing")
        }
        singleClickTimer?.isRepeats = false
        singleClickTimer?.start()
    }

    fun handleCanvasDoubleClick() {
        if (currentScreen != AppScreen.PLAYER) return
        singleClickTimer?.stop()
        singleClickTimer = null
        if (isPip) exitPip() else toggleFullscreen()
    }

    fun applyLayout() {
        val w = rootPanel.width
        val h = rootPanel.height
        if (w <= 0 || h <= 0) return

        SwingUtilities.invokeLater {
            when (currentScreen) {
                AppScreen.BROWSER, AppScreen.SETTINGS -> {
                    videoCanvas.setBounds(0, 0, 1, 1)
                    composePanel.setBounds(0, 0, w, h)
                }
                AppScreen.PLAYER -> {
                    val baseControlH = if (isPip) 80 else 120
                    val controlH = if (tracksExpanded) (h - 40) else baseControlH
                    if ((isFullscreen || isPip) && !controlsVisible) {
                        videoCanvas.setBounds(0, 0, w, h)
                        composePanel.setBounds(0, h, w, 0)
                    } else {
                        videoCanvas.setBounds(0, 0, w, h - controlH)
                        composePanel.setBounds(0, h - controlH, w, controlH)
                    }
                }
            }
            rootPanel.revalidate()
            rootPanel.repaint()
        }
    }
}

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
    dev.windplayer.ui.I18n.current = initialSettings.language

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

        frame.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
            }
            override fun importData(support: TransferSupport): Boolean {
                if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                val fileList = support.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: return false
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

        var fullscreenState by mutableStateOf(false)
        var skipNextCallback by mutableStateOf<(() -> Unit)?>(null)
        var settingsState by mutableStateOf(initialSettings)
        var recentFilesState by mutableStateOf(loadRecentFiles())
        var bookmarksState by mutableStateOf(loadBookmarks())
        layoutManager.onFullscreenChanged = { fullscreenState = it }

        rootPanel.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                layoutManager.applyLayout()
            }
        })

        var dragMode = 0
        var dragStartX = 0
        var dragStartY = 0
        var dragStartValue = 0.0
        var dragOccurred = false

        videoCanvas.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent?) {
                layoutManager.onMouseActivity()
            }
            override fun mouseDragged(e: MouseEvent?) {
                if (e == null) return
                if (layoutManager.isPip) {
                    try { layoutManager.handleDrag(e.locationOnScreen) } catch (_: Exception) {}
                    return
                }
                if (layoutManager.currentScreen != AppScreen.PLAYER || dragMode == 0) return
                val dx = e.x - dragStartX
                val dy = dragStartY - e.y
                if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                    dragOccurred = true
                }
                when (dragMode) {
                    1 -> {
                        val dur = try { player.getPropertyDouble("duration") } catch (_: Exception) { 0.0 }
                        val maxPos = if (dur > 0) dur else Double.MAX_VALUE
                        val newPos = (dragStartValue + dx).coerceIn(0.0, maxPos)
                        try { player.command("seek", "%.3f".format(newPos), "absolute") } catch (_: Exception) {}
                        if (dur > 0) osdEvents.tryEmit(formatDurationOsd(newPos, dur))
                    }
                    2 -> {
                        val newVol = (dragStartValue + dy * 0.5).coerceIn(0.0, 100.0).toInt()
                        try { player.setProperty("volume", newVol.toString()) } catch (_: Exception) {}
                        osdEvents.tryEmit("Vol: $newVol%")
                    }
                    3 -> {
                        val newBright = (dragStartValue + dy * 0.5).coerceIn(-100.0, 100.0).toInt()
                        try { player.setProperty("brightness", newBright.toString()) } catch (_: Exception) {}
                        osdEvents.tryEmit("Brightness: $newBright")
                    }
                }
                layoutManager.onMouseActivity()
            }
        })

        fun showContextMenu(e: MouseEvent) {
            layoutManager.onMouseActivity()
            val popup = JPopupMenu()

            val isPaused = try { player.getPropertyString("pause") == "yes" } catch (_: Exception) { false }
            popup.add(JMenuItem(if (isPaused) I18n.get("play") else I18n.get("pause")).apply {
                addActionListener {
                    player.command("cycle", "pause")
                    val paused = try { player.getPropertyString("pause") == "yes" } catch (_: Exception) { false }
                    osdEvents.tryEmit(if (paused) "|| Paused" else "> Playing")
                }
            })

            popup.addSeparator()

            popup.add(JMenuItem(if (layoutManager.isFullscreen) I18n.get("exit_fullscreen") else I18n.get("fullscreen")).apply {
                addActionListener { layoutManager.toggleFullscreen() }
            })

            popup.addSeparator()

            popup.add(JMenuItem(if (layoutManager.isPip) I18n.get("exit_pip") else I18n.get("pip")).apply {
                addActionListener { layoutManager.togglePip() }
            })
            if (layoutManager.isPip) {
                popup.add(JMenuItem(I18n.get("pip_larger")).apply {
                    addActionListener { layoutManager.resizePip(80) }
                })
                popup.add(JMenuItem(I18n.get("pip_smaller")).apply {
                    addActionListener { layoutManager.resizePip(-80) }
                })
            }

            val isMuted = try { player.getPropertyString("mute") == "yes" } catch (_: Exception) { false }
            popup.add(JMenuItem(if (isMuted) I18n.get("unmute") else I18n.get("mute")).apply {
                addActionListener {
                    player.command("cycle", "mute")
                    val muted = try { player.getPropertyString("mute") == "yes" } catch (_: Exception) { false }
                    osdEvents.tryEmit(if (muted) "Muted" else "Vol: ${player.getPropertyLong("volume")}%")
                }
            })

            popup.addSeparator()

            val subMenu = JMenu(I18n.get("subtitle"))
            subMenu.add(JMenuItem(I18n.get("next_subtitle")).apply {
                addActionListener {
                    player.command("cycle", "sid")
                    val sid = player.getPropertyString("sid") ?: "no"
                    osdEvents.tryEmit(if (sid == "no") "Subtitle: Off" else "Subtitle: #$sid")
                }
            })
            subMenu.addSeparator()
            subMenu.add(JMenuItem(I18n.get("sub_delay_neg")).apply {
                addActionListener {
                    player.command("add", "sub-delay", "-0.1")
                    osdEvents.tryEmit("Sub delay: %+.1fs".format(player.getPropertyDouble("sub-delay")))
                }
            })
            subMenu.add(JMenuItem(I18n.get("sub_delay_pos")).apply {
                addActionListener {
                    player.command("add", "sub-delay", "0.1")
                    osdEvents.tryEmit("Sub delay: %+.1fs".format(player.getPropertyDouble("sub-delay")))
                }
            })
            subMenu.add(JMenuItem(I18n.get("reset_sub_delay")).apply {
                addActionListener {
                    player.setProperty("sub-delay", "0")
                    osdEvents.tryEmit("Sub delay: +0.0s")
                }
            })
            popup.add(subMenu)

            val audioMenu = JMenu(I18n.get("audio"))
            audioMenu.add(JMenuItem(I18n.get("next_audio")).apply {
                addActionListener {
                    player.command("cycle", "aid")
                    val aid = player.getPropertyString("aid") ?: "no"
                    osdEvents.tryEmit(if (aid == "no") "Audio: Off" else "Audio: #$aid")
                }
            })
            audioMenu.addSeparator()
            audioMenu.add(JMenuItem(I18n.get("audio_delay_neg")).apply {
                addActionListener {
                    player.command("add", "audio-delay", "-0.1")
                    osdEvents.tryEmit("Audio delay: %+.1fs".format(player.getPropertyDouble("audio-delay")))
                }
            })
            audioMenu.add(JMenuItem(I18n.get("audio_delay_pos")).apply {
                addActionListener {
                    player.command("add", "audio-delay", "0.1")
                    osdEvents.tryEmit("Audio delay: %+.1fs".format(player.getPropertyDouble("audio-delay")))
                }
            })
            audioMenu.add(JMenuItem(I18n.get("reset_audio_delay")).apply {
                addActionListener {
                    player.setProperty("audio-delay", "0")
                    osdEvents.tryEmit("Audio delay: +0.0s")
                }
            })
            popup.add(audioMenu)

            popup.addSeparator()

            val speedMenu = JMenu(I18n.get("speed"))
            speedMenu.add(JMenuItem(I18n.get("slower")).apply {
                addActionListener {
                    val speed = player.getPropertyDouble("speed")
                    val newSpeed = maxOf(speed - 0.25, 0.25)
                    player.setProperty("speed", "%.2f".format(newSpeed))
                    osdEvents.tryEmit("Speed: %.2fx".format(newSpeed))
                }
            })
            speedMenu.add(JMenuItem(I18n.get("faster")).apply {
                addActionListener {
                    val speed = player.getPropertyDouble("speed")
                    val newSpeed = minOf(speed + 0.25, 4.0)
                    player.setProperty("speed", "%.2f".format(newSpeed))
                    osdEvents.tryEmit("Speed: %.2fx".format(newSpeed))
                }
            })
            speedMenu.add(JMenuItem(I18n.get("normal_speed")).apply {
                addActionListener {
                    player.setProperty("speed", "1.00")
                    osdEvents.tryEmit("Speed: 1.00x")
                }
            })
            popup.add(speedMenu)

            val abMenu = JMenu(I18n.get("ab_loop"))
            abMenu.add(JMenuItem(I18n.get("set_a")).apply {
                addActionListener {
                    val pos = player.getPropertyDouble("time-pos")
                    player.setProperty("ab-loop-a", "%.3f".format(pos))
                    osdEvents.tryEmit("A-B Loop A: ${formatDuration(pos)}")
                }
            })
            abMenu.add(JMenuItem(I18n.get("set_b")).apply {
                addActionListener {
                    val pos = player.getPropertyDouble("time-pos")
                    player.setProperty("ab-loop-b", "%.3f".format(pos))
                    osdEvents.tryEmit("A-B Loop B: ${formatDuration(pos)}")
                }
            })
            abMenu.add(JMenuItem(I18n.get("clear_ab")).apply {
                addActionListener {
                    player.setProperty("ab-loop-a", "no")
                    player.setProperty("ab-loop-b", "no")
                    osdEvents.tryEmit("A-B Loop Cleared")
                }
            })
            popup.add(abMenu)

            val eqMenu = JMenu(I18n.get("video_eq"))
            eqMenu.add(JMenuItem(I18n.get("brightness_neg")).apply {
                addActionListener {
                    player.command("add", "brightness", "-5")
                    osdEvents.tryEmit("Brightness: ${player.getPropertyLong("brightness")}")
                }
            })
            eqMenu.add(JMenuItem(I18n.get("brightness_pos")).apply {
                addActionListener {
                    player.command("add", "brightness", "5")
                    osdEvents.tryEmit("Brightness: ${player.getPropertyLong("brightness")}")
                }
            })
            eqMenu.add(JMenuItem(I18n.get("contrast_neg")).apply {
                addActionListener {
                    player.command("add", "contrast", "-5")
                    osdEvents.tryEmit("Contrast: ${player.getPropertyLong("contrast")}")
                }
            })
            eqMenu.add(JMenuItem(I18n.get("contrast_pos")).apply {
                addActionListener {
                    player.command("add", "contrast", "5")
                    osdEvents.tryEmit("Contrast: ${player.getPropertyLong("contrast")}")
                }
            })
            eqMenu.add(JMenuItem(I18n.get("saturation_neg")).apply {
                addActionListener {
                    player.command("add", "saturation", "-5")
                    osdEvents.tryEmit("Saturation: ${player.getPropertyLong("saturation")}")
                }
            })
            eqMenu.add(JMenuItem(I18n.get("saturation_pos")).apply {
                addActionListener {
                    player.command("add", "saturation", "5")
                    osdEvents.tryEmit("Saturation: ${player.getPropertyLong("saturation")}")
                }
            })
            eqMenu.add(JMenuItem(I18n.get("gamma_neg")).apply {
                addActionListener {
                    player.command("add", "gamma", "-5")
                    osdEvents.tryEmit("Gamma: ${player.getPropertyLong("gamma")}")
                }
            })
            eqMenu.add(JMenuItem(I18n.get("gamma_pos")).apply {
                addActionListener {
                    player.command("add", "gamma", "5")
                    osdEvents.tryEmit("Gamma: ${player.getPropertyLong("gamma")}")
                }
            })
            eqMenu.addSeparator()
            eqMenu.add(JMenuItem(I18n.get("reset_all_eq")).apply {
                addActionListener {
                    player.setProperty("brightness", "0")
                    player.setProperty("contrast", "0")
                    player.setProperty("saturation", "0")
                    player.setProperty("gamma", "0")
                    osdEvents.tryEmit("EQ Reset")
                }
            })
            popup.add(eqMenu)

            popup.addSeparator()

            popup.add(JMenuItem(I18n.get("play_next")).apply {
                addActionListener { skipNextCallback?.invoke() }
            })
            popup.add(JMenuItem(I18n.get("playlist")).apply {
                addActionListener { playlistToggle.tryEmit(Unit) }
            })

            popup.addSeparator()

            popup.add(JMenuItem(I18n.get("screenshot")).apply {
                addActionListener {
                    player.command("screenshot")
                    osdEvents.tryEmit("Screenshot saved")
                }
            })
            popup.add(JMenuItem(I18n.get("shortcuts_f1")).apply {
                addActionListener { cheatsheetToggle.tryEmit(Unit) }
            })

            popup.show(videoCanvas, e.x, e.y)
        }

        videoCanvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (e == null || !SwingUtilities.isLeftMouseButton(e)) return
                if (layoutManager.isPip) {
                    try { layoutManager.startDrag(e.locationOnScreen) } catch (_: Exception) {}
                } else if (layoutManager.currentScreen == AppScreen.PLAYER) {
                    val w = videoCanvas.width
                    dragMode = when {
                        e.x < w / 3 -> 3
                        e.x > w * 2 / 3 -> 2
                        else -> 1
                    }
                    dragStartX = e.x
                    dragStartY = e.y
                    dragStartValue = when (dragMode) {
                        1 -> try { player.getPropertyDouble("time-pos") } catch (_: Exception) { 0.0 }
                        2 -> try { player.getPropertyLong("volume").toDouble() } catch (_: Exception) { 100.0 }
                        3 -> try { player.getPropertyLong("brightness").toDouble() } catch (_: Exception) { 0.0 }
                        else -> 0.0
                    }
                    dragOccurred = false
                }
            }
            override fun mouseReleased(e: MouseEvent?) {
                dragMode = 0
            }
            override fun mouseClicked(e: MouseEvent?) {
                if (e == null) return
                if (SwingUtilities.isRightMouseButton(e)) {
                    if (layoutManager.currentScreen == AppScreen.PLAYER) showContextMenu(e)
                    return
                }
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    if (layoutManager.currentScreen == AppScreen.PLAYER) layoutManager.toggleFullscreen()
                    return
                }
                if (dragOccurred) {
                    dragOccurred = false
                    return
                }
                if (e.clickCount >= 2) {
                    layoutManager.handleCanvasDoubleClick()
                } else {
                    layoutManager.handleCanvasClick(player, osdEvents)
                }
            }
        })

        videoCanvas.addMouseWheelListener { e ->
            if (layoutManager.currentScreen == AppScreen.PLAYER) {
                val delta = if (e.wheelRotation < 0) 5 else -5
                val vol = player.getPropertyLong("volume")
                val newVol = (vol + delta).coerceIn(0, 100)
                player.setProperty("volume", newVol.toString())
                osdEvents.tryEmit("Vol: $newVol%")
                layoutManager.onMouseActivity()
            }
        }

        composePanel.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) {
                layoutManager.cancelHideTimer()
            }
            override fun mouseExited(e: MouseEvent?) {
                layoutManager.restartHideTimer()
            }
        })

        val rootPane = frame.rootPane
        val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
        val actionMap = rootPane.actionMap

        fun bindKey(keyStroke: KeyStroke, name: String, action: () -> Unit) {
            inputMap.put(keyStroke, name)
            actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent?) = action()
            })
        }

        fun isPlayer() = layoutManager.currentScreen == AppScreen.PLAYER

        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "togglePause") {
            if (isPlayer()) {
                player.command("cycle", "pause")
                val paused = player.getPropertyString("pause") == "yes"
                osdEvents.tryEmit(if (paused) "|| Paused" else "> Playing")
                layoutManager.onMouseActivity()
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggleFs") {
            if (isPlayer()) layoutManager.toggleFullscreen()
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "toggleFsF11") {
            layoutManager.toggleFullscreen()
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), "togglePip") {
            if (isPlayer()) layoutManager.togglePip()
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "exitFs") {
            if (layoutManager.isFullscreen) layoutManager.exitFullscreen()
            else if (layoutManager.isPip) layoutManager.exitPip()
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "seekBack5") {
            if (isPlayer()) {
                player.command("seek", "-5")
                val pos = player.getPropertyDouble("time-pos")
                val dur = player.getPropertyDouble("duration")
                if (dur > 0 && pos >= 0) osdEvents.tryEmit("<< -5s  ${formatDurationOsd(pos, dur)}")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "seekFwd5") {
            if (isPlayer()) {
                player.command("seek", "5")
                val pos = player.getPropertyDouble("time-pos")
                val dur = player.getPropertyDouble("duration")
                if (dur > 0 && pos >= 0) osdEvents.tryEmit(">> +5s  ${formatDurationOsd(pos, dur)}")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK), "seekBack30") {
            if (isPlayer()) {
                player.command("seek", "-30")
                val pos = player.getPropertyDouble("time-pos")
                val dur = player.getPropertyDouble("duration")
                if (dur > 0 && pos >= 0) osdEvents.tryEmit("<< -30s  ${formatDurationOsd(pos, dur)}")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK), "seekFwd30") {
            if (isPlayer()) {
                player.command("seek", "30")
                val pos = player.getPropertyDouble("time-pos")
                val dur = player.getPropertyDouble("duration")
                if (dur > 0 && pos >= 0) osdEvents.tryEmit(">> +30s  ${formatDurationOsd(pos, dur)}")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "volUp") {
            if (isPlayer()) {
                val vol = player.getPropertyLong("volume")
                val newVol = (vol + 5).coerceIn(0, 100)
                player.setProperty("volume", newVol.toString())
                osdEvents.tryEmit("Vol: $newVol%")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "volDown") {
            if (isPlayer()) {
                val vol = player.getPropertyLong("volume")
                val newVol = (vol - 5).coerceIn(0, 100)
                player.setProperty("volume", newVol.toString())
                osdEvents.tryEmit("Vol: $newVol%")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "toggleMute") {
            if (isPlayer()) {
                player.command("cycle", "mute")
                val muted = player.getPropertyString("mute") == "yes"
                osdEvents.tryEmit(if (muted) "Muted" else "Vol: ${player.getPropertyLong("volume")}%")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_CLOSE_BRACKET, 0), "speedUp") {
            if (isPlayer()) {
                val speed = player.getPropertyDouble("speed")
                val newSpeed = minOf(speed + 0.25, 4.0)
                player.setProperty("speed", "%.2f".format(newSpeed))
                osdEvents.tryEmit("Speed: %.2fx".format(newSpeed))
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, 0), "speedDown") {
            if (isPlayer()) {
                val speed = player.getPropertyDouble("speed")
                val newSpeed = maxOf(speed - 0.25, 0.25)
                player.setProperty("speed", "%.2f".format(newSpeed))
                osdEvents.tryEmit("Speed: %.2fx".format(newSpeed))
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SLASH, 0), "speedReset") {
            if (isPlayer()) {
                player.setProperty("speed", "1.00")
                osdEvents.tryEmit("Speed: 1.00x")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), "screenshot") {
            if (isPlayer()) {
                player.command("screenshot")
                osdEvents.tryEmit("Screenshot saved")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_V, 0), "cycleSub") {
            if (isPlayer()) {
                player.command("cycle", "sid")
                val sid = player.getPropertyString("sid") ?: "no"
                osdEvents.tryEmit(if (sid == "no") "Subtitle: Off" else "Subtitle: #${sid}")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_B, 0), "cycleAudio") {
            if (isPlayer()) {
                player.command("cycle", "aid")
                val aid = player.getPropertyString("aid") ?: "no"
                osdEvents.tryEmit(if (aid == "no") "Audio: Off" else "Audio: #${aid}")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_N, 0), "nextFile") {
            if (isPlayer()) {
                skipNextCallback?.invoke()
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_Z, 0), "subDelayDown") {
            if (isPlayer()) {
                player.command("add", "sub-delay", "-0.1")
                val delay = player.getPropertyDouble("sub-delay")
                osdEvents.tryEmit("Sub delay: %+.1fs".format(delay))
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_X, 0), "subDelayUp") {
            if (isPlayer()) {
                player.command("add", "sub-delay", "0.1")
                val delay = player.getPropertyDouble("sub-delay")
                osdEvents.tryEmit("Sub delay: %+.1fs".format(delay))
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.SHIFT_DOWN_MASK), "subDelayReset") {
            if (isPlayer()) {
                player.setProperty("sub-delay", "0")
                osdEvents.tryEmit("Sub delay: +0.0s")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), "audioDelayDown") {
            if (isPlayer()) {
                player.command("add", "audio-delay", "-0.1")
                val delay = player.getPropertyDouble("audio-delay")
                osdEvents.tryEmit("Audio delay: %+.1fs".format(delay))
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), "audioDelayUp") {
            if (isPlayer()) {
                player.command("add", "audio-delay", "0.1")
                val delay = player.getPropertyDouble("audio-delay")
                osdEvents.tryEmit("Audio delay: %+.1fs".format(delay))
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.SHIFT_DOWN_MASK), "audioDelayReset") {
            if (isPlayer()) {
                player.setProperty("audio-delay", "0")
                osdEvents.tryEmit("Audio delay: +0.0s")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, 0), "frameStep") {
            if (isPlayer()) {
                player.command("frame-step")
                osdEvents.tryEmit("Frame +")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, 0), "frameBackStep") {
            if (isPlayer()) {
                player.command("frame-back-step")
                osdEvents.tryEmit("Frame -")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "togglePlaylist") {
            if (isPlayer()) {
                playlistToggle.tryEmit(Unit)
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "brightDown") {
            if (isPlayer()) {
                player.command("add", "brightness", "-5")
                val v = player.getPropertyLong("brightness")
                osdEvents.tryEmit("Brightness: $v")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "brightUp") {
            if (isPlayer()) {
                player.command("add", "brightness", "5")
                val v = player.getPropertyLong("brightness")
                osdEvents.tryEmit("Brightness: $v")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_3, 0), "contrastDown") {
            if (isPlayer()) {
                player.command("add", "contrast", "-5")
                val v = player.getPropertyLong("contrast")
                osdEvents.tryEmit("Contrast: $v")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_4, 0), "contrastUp") {
            if (isPlayer()) {
                player.command("add", "contrast", "5")
                val v = player.getPropertyLong("contrast")
                osdEvents.tryEmit("Contrast: $v")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_5, 0), "satDown") {
            if (isPlayer()) {
                player.command("add", "saturation", "-5")
                val v = player.getPropertyLong("saturation")
                osdEvents.tryEmit("Saturation: $v")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_6, 0), "satUp") {
            if (isPlayer()) {
                player.command("add", "saturation", "5")
                val v = player.getPropertyLong("saturation")
                osdEvents.tryEmit("Saturation: $v")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_7, 0), "gammaDown") {
            if (isPlayer()) {
                player.command("add", "gamma", "-5")
                val v = player.getPropertyLong("gamma")
                osdEvents.tryEmit("Gamma: $v")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_8, 0), "gammaUp") {
            if (isPlayer()) {
                player.command("add", "gamma", "5")
                val v = player.getPropertyLong("gamma")
                osdEvents.tryEmit("Gamma: $v")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_0, 0), "eqReset") {
            if (isPlayer()) {
                player.setProperty("brightness", "0")
                player.setProperty("contrast", "0")
                player.setProperty("saturation", "0")
                player.setProperty("gamma", "0")
                osdEvents.tryEmit("EQ Reset")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "cheatsheet") {
            if (isPlayer()) {
                cheatsheetToggle.tryEmit(Unit)
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), "abLoopA") {
            if (isPlayer()) {
                val pos = player.getPropertyDouble("time-pos")
                player.setProperty("ab-loop-a", "%.3f".format(pos))
                osdEvents.tryEmit("A-B Loop A: ${formatDuration(pos)}")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.SHIFT_DOWN_MASK), "abLoopB") {
            if (isPlayer()) {
                val pos = player.getPropertyDouble("time-pos")
                player.setProperty("ab-loop-b", "%.3f".format(pos))
                osdEvents.tryEmit("A-B Loop B: ${formatDuration(pos)}")
            }
        }
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.SHIFT_DOWN_MASK), "abLoopClear") {
            if (isPlayer()) {
                player.setProperty("ab-loop-a", "no")
                player.setProperty("ab-loop-b", "no")
                osdEvents.tryEmit("A-B Loop Cleared")
            }
        }

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

        composePanel.setContent {
            App(
                player = player,
                vfsManager = vfsManager,
                onScreenChange = { screen ->
                    layoutManager.switchTo(screen)
                },
                onTracksToggle = { expanded ->
                    layoutManager.setTracksExpanded(expanded)
                },
                onToggleFullscreen = {
                    layoutManager.toggleFullscreen()
                },
                isFullscreen = fullscreenState,
                osdEvents = osdEvents,
                onOsdEmit = { text -> osdEvents.tryEmit(text) },
                dropFilePath = dropEvents,
                playlistToggle = playlistToggle,
                cheatsheetToggle = cheatsheetToggle,
                onSkipNextRegistered = { callback -> skipNextCallback = callback },
                settings = settingsState,
                onSettingsChanged = { newSettings ->
                    settingsState = newSettings
                    I18n.current = newSettings.language
                    saveSettings(newSettings)
                    applyMpvSettings(player, newSettings)
                },
                recentFiles = recentFilesState,
                onFilePlayed = { name, path, isLocal, serverId ->
                    recentFilesState = updateRecentFiles(recentFilesState, name, path, isLocal, serverId)
                    saveRecentFiles(recentFilesState)
                },
                onPositionUpdate = { path, pos, dur ->
                    recentFilesState = updateRecentPosition(recentFilesState, path, pos, dur)
                    saveRecentFiles(recentFilesState)
                },
                bookmarks = bookmarksState,
                onBookmarkAdded = { path ->
                    if (bookmarksState.none { it == path }) {
                        bookmarksState = bookmarksState + path
                        saveBookmarks(bookmarksState)
                    }
                },
                onBookmarkRemoved = { path ->
                    bookmarksState = bookmarksState.filterNot { it == path }
                    saveBookmarks(bookmarksState)
                }
            )
        }

        frame.isVisible = true
    }

    Thread.currentThread().join()
}
