package dev.windplayer

import androidx.compose.ui.awt.ComposePanel
import com.sun.jna.Native
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.ui.AppScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.Canvas
import java.awt.Color
import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Owns the Swing null-layout positioning of [videoCanvas] (mpv render target) and
 * [composePanel] (Material3 controls), plus fullscreen / PiP / control-auto-hide
 * lifecycle.
 *
 * Window style manipulation uses Win32 directly (without `frame.dispose()`) so the
 * underlying HWND — and therefore the mpv `wid` binding — stays valid.
 */
internal class LayoutManager(
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

    // ------------------------------------------------------------------
    // Screen switches
    // ------------------------------------------------------------------

    fun switchTo(screen: AppScreen) {
        currentScreen = screen
        tracksExpanded = false
        controlsVisible = true
        hideTimer?.stop()
        hideTimer = null
        singleClickTimer?.stop()
        singleClickTimer = null
        if (screen == AppScreen.BROWSER || screen == AppScreen.SETTINGS) {
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

    // ------------------------------------------------------------------
    // Fullscreen / PiP
    // ------------------------------------------------------------------

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
        Win32Api.INSTANCE.SetWindowPos(
            hwnd, Win32Api.HWND_TOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE
        )

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
        Win32Api.INSTANCE.SetWindowPos(
            hwnd, Win32Api.HWND_NOTOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE
        )

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
        Win32Api.INSTANCE.SetWindowPos(
            hwnd, Win32Api.HWND_TOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE
        )

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
        Win32Api.INSTANCE.SetWindowPos(
            hwnd, Win32Api.HWND_NOTOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE
        )

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

    // ------------------------------------------------------------------
    // PiP drag
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Auto-hide controls / cursor
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Click vs double-click detection
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Layout application
    // ------------------------------------------------------------------

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
