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
    // M25: separate saved-state pairs for fullscreen vs PiP. The toggle methods
    // ensure mutual exclusion today, but a future caller entering one while the
    // other is active would clobber the shared pair and corrupt window state.
    private var savedFsStyle = 0
    private var savedFsBounds: Rectangle? = null
    private var savedPipStyle = 0
    private var savedPipBounds: Rectangle? = null
    private var pipWidth = 480
    private var pipHeight = 270
    private var dragStartScreen: Point? = null
    private var dragWindowStart: Point? = null
    private val hiddenCursor: Cursor by lazy {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        Toolkit.getDefaultToolkit().createCustomCursor(img, Point(0, 0), "hidden")
    }

    /**
     * H2: Run [block] on the Swing EDT.
     *
     * In Compose Desktop every callback (Compose `onClick`, keyboard bindings
     * via `JComponent.WHEN_IN_FOCUSED_WINDOW`, mouse listeners, Swing `Timer`)
     * fires on the EDT — so the inline branch is the hot path and there is
     * zero overhead for the existing callers.
     *
     * The wrapper exists as a defensive contract: every public method below
     * mutates either Swing components (`frame.bounds`, `composePanel.setBounds`)
     * or the Win32 HWND (`SetWindowLongW`, `SetWindowPos`), both of which
     * require EDT serialization — Swing by single-thread contract, Win32 by
     * window-message affinity. A future non-EDT caller (e.g. an mpv-event-thread
     * listener) would otherwise silently corrupt window state; this helper
     * serializes it correctly and synchronously instead.
     *
     * `invokeAndWait` is used (not `invokeLater`) so callers observe the side
     * effect before returning — necessary because methods like `toggleFullscreen`
     * flip `isFullscreen` and the next caller relies on the new state.
     */
    private inline fun onEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            try {
                SwingUtilities.invokeAndWait { block() }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw (e.cause ?: e)
            }
        }
    }

    // ------------------------------------------------------------------
    // Screen switches
    // ------------------------------------------------------------------

    fun switchTo(screen: AppScreen) = onEdt {
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
        // Auto-hide controls 5s after entering the player (all modes, not just
        // fullscreen/PiP). Previously the timer was only started in
        // fullscreen/PiP enter paths, so windowed playback kept the bar forever.
        if (screen == AppScreen.PLAYER) {
            resetHideTimer()
        }
    }

    fun setTracksExpanded(expanded: Boolean) = onEdt {
        tracksExpanded = expanded
        if (expanded) {
            controlsVisible = true
            hideTimer?.stop()
        } else {
            // Tracks collapsed: restart auto-hide so controls go away after 5s.
            resetHideTimer()
        }
        applyLayout()
    }

    // ------------------------------------------------------------------
    // Fullscreen / PiP
    // ------------------------------------------------------------------

    fun toggleFullscreen() = onEdt {
        if (currentScreen != AppScreen.PLAYER) return@onEdt
        if (isPip) exitPip()
        if (isFullscreen) exitFullscreen() else enterFullscreen()
    }

    fun togglePip() = onEdt {
        if (currentScreen != AppScreen.PLAYER) return@onEdt
        if (isFullscreen) exitFullscreen()
        if (isPip) exitPip() else enterPip()
    }

    private fun enterFullscreen() {
        isFullscreen = true
        onFullscreenChanged?.invoke(true)

        val hwnd = Native.getComponentPointer(frame)
        savedFsStyle = Win32Api.INSTANCE.GetWindowLongW(hwnd, GWL_STYLE)
        savedFsBounds = frame.bounds

        val strip = WS_CAPTION or WS_THICKFRAME or WS_SYSMENU or WS_MAXIMIZEBOX or WS_MINIMIZEBOX
        win32SetWindowStyle(hwnd, GWL_STYLE, savedFsStyle and strip.inv())
        Win32Api.INSTANCE.SetWindowPos(
            hwnd, Win32Api.HWND_TOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE
        )

        frame.bounds = frame.graphicsConfiguration.bounds

        controlsVisible = true
        resetHideTimer()
        applyLayout()
    }

    fun exitFullscreen() = onEdt {
        if (!isFullscreen) return@onEdt
        isFullscreen = false
        onFullscreenChanged?.invoke(false)
        hideTimer?.stop()
        hideTimer = null

        val hwnd = Native.getComponentPointer(frame)
        win32SetWindowStyle(hwnd, GWL_STYLE, savedFsStyle)
        Win32Api.INSTANCE.SetWindowPos(
            hwnd, Win32Api.HWND_NOTOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE
        )

        frame.bounds = savedFsBounds ?: Rectangle(100, 100, 1280, 720)

        videoCanvas.cursor = Cursor.getDefaultCursor()
        applyLayout()
    }

    private fun enterPip() {
        isPip = true

        val hwnd = Native.getComponentPointer(frame)
        savedPipStyle = Win32Api.INSTANCE.GetWindowLongW(hwnd, GWL_STYLE)
        savedPipBounds = frame.bounds

        val strip = WS_CAPTION or WS_THICKFRAME or WS_SYSMENU or WS_MAXIMIZEBOX or WS_MINIMIZEBOX
        win32SetWindowStyle(hwnd, GWL_STYLE, savedPipStyle and strip.inv())
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

    fun exitPip() = onEdt {
        if (!isPip) return@onEdt
        isPip = false
        hideTimer?.stop()
        hideTimer = null

        val hwnd = Native.getComponentPointer(frame)
        win32SetWindowStyle(hwnd, GWL_STYLE, savedPipStyle)
        Win32Api.INSTANCE.SetWindowPos(
            hwnd, Win32Api.HWND_NOTOPMOST, 0, 0, 0, 0,
            SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE
        )

        frame.bounds = savedPipBounds ?: Rectangle(100, 100, 1280, 720)

        videoCanvas.cursor = Cursor.getDefaultCursor()
        applyLayout()
    }

    fun resizePip(delta: Int) = onEdt {
        if (!isPip) return@onEdt
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

    fun startDrag(screenPoint: Point) = onEdt {
        if (!isPip) return@onEdt
        singleClickTimer?.stop()
        dragStartScreen = screenPoint
        dragWindowStart = frame.location
    }

    fun handleDrag(screenPoint: Point) = onEdt {
        if (!isPip || dragStartScreen == null || dragWindowStart == null) return@onEdt
        val dx = screenPoint.x - dragStartScreen!!.x
        val dy = screenPoint.y - dragStartScreen!!.y
        val newX = dragWindowStart!!.x + dx
        val newY = dragWindowStart!!.y + dy
        // M9: clamp so at least 32px of the PiP stays visible on every edge —
        // without this the user can drag the window entirely off-screen with
        // no way to grab it back.
        val sb = frame.graphicsConfiguration.bounds
        val clampedX = newX.coerceIn(sb.x + 32 - pipWidth, sb.x + sb.width - 32)
        val clampedY = newY.coerceIn(sb.y + 32 - pipHeight, sb.y + sb.height - 32)
        frame.location = Point(clampedX, clampedY)
    }

    // ------------------------------------------------------------------
    // Auto-hide controls / cursor
    // ------------------------------------------------------------------

    fun onMouseActivity() = onEdt {
        // Works in ALL player modes (windowed + fullscreen + PiP). Previously
        // bailed out of windowed mode, which meant moving the mouse could never
        // show the controls once they auto-hid.
        if (currentScreen != AppScreen.PLAYER) return@onEdt
        // Only hide cursor in fullscreen/PiP; in windowed mode the user needs
        // the cursor to reach the title bar / window controls.
        if (isFullscreen || isPip) {
            videoCanvas.cursor = Cursor.getDefaultCursor()
        }
        if (!tracksExpanded && !controlsVisible) {
            controlsVisible = true
            applyLayout()
        }
        resetHideTimer()
    }

    fun cancelHideTimer() = onEdt {
        hideTimer?.stop()
    }

    fun restartHideTimer() = onEdt {
        if (currentScreen == AppScreen.PLAYER) {
            resetHideTimer()
        }
    }

    private fun resetHideTimer() {
        hideTimer?.stop()
        hideTimer = Timer(5000) {
            // Auto-hide in ALL player modes (was fullscreen/PiP only). The
            // 5s delay gives the user time to grab a control before it
            // disappears; moving the mouse brings everything back.
            if (!tracksExpanded && currentScreen == AppScreen.PLAYER) {
                controlsVisible = false
                // Only hide cursor in fullscreen/PiP; windowed mode keeps the
                // cursor visible so the user can still reach the title bar.
                if (isFullscreen || isPip) {
                    videoCanvas.cursor = hiddenCursor
                }
                applyLayout()
            }
        }
        hideTimer?.isRepeats = false
        hideTimer?.start()
    }

    // ------------------------------------------------------------------
    // Click vs double-click detection
    // ------------------------------------------------------------------

    /**
     * Single-click on the video canvas.
     *
     * Behavior (user request 2026-06-30):
     *  - **Controls visible** → hide the controls. Does NOT toggle pause.
     *    The user must click again (with controls hidden) to toggle.
     *  - **Controls hidden** → toggle play/pause as before.
     *
     * Either way, a quick second click is intercepted by
     * [handleCanvasDoubleClick] which cancels this timer and toggles fullscreen.
     */
    fun handleCanvasClick(player: MpvPlayer, osd: MutableSharedFlow<String>) = onEdt {
        if (currentScreen != AppScreen.PLAYER) return@onEdt
        singleClickTimer?.stop()
        singleClickTimer = Timer(250) {
            if (controlsVisible) {
                // First click with visible controls: hide them (don't pause).
                controlsVisible = false
                if (isFullscreen || isPip) {
                    videoCanvas.cursor = hiddenCursor
                }
                hideTimer?.stop()
                applyLayout()
            } else {
                // Click with hidden controls: toggle play/pause.
                player.command("cycle", "pause")
                val paused = player.getPropertyString("pause") == "yes"
                osd.tryEmit(if (paused) "|| ${dev.windplayer.ui.I18n.get("osd_paused")}" else "> ${dev.windplayer.ui.I18n.get("osd_playing")}")
                // User is active — restart the auto-hide timer so controls
                // (if they reappear via mouse move) hide again in 5s.
                resetHideTimer()
            }
        }
        singleClickTimer?.isRepeats = false
        singleClickTimer?.start()
    }

    fun handleCanvasDoubleClick() = onEdt {
        if (currentScreen != AppScreen.PLAYER) return@onEdt
        singleClickTimer?.stop()
        singleClickTimer = null
        if (isPip) exitPip() else toggleFullscreen()
    }

    // ------------------------------------------------------------------
    // Layout application
    // ------------------------------------------------------------------

    fun applyLayout() = onEdt {
        // M18: read dimensions INSIDE the invokeLater so a resize that happens
        // between the outer call and the deferred execution uses the latest
        // panel size, not a stale snapshot.
        SwingUtilities.invokeLater {
            val w = rootPanel.width
            val h = rootPanel.height
            if (w <= 0 || h <= 0) return@invokeLater

            when (currentScreen) {
                AppScreen.BROWSER, AppScreen.SETTINGS -> {
                    videoCanvas.setBounds(0, 0, 1, 1)
                    composePanel.setBounds(0, 0, w, h)
                }
                AppScreen.PLAYER -> {
                    val baseControlH = if (isPip) 80 else 120
                    val controlH = if (tracksExpanded) (h - 40) else baseControlH
                    // Controls hide in ALL modes (was fullscreen/PiP only).
                    // When hidden, the compose panel is moved off-screen so
                    // the video canvas fills the entire window.
                    if (!controlsVisible) {
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
