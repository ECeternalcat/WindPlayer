package dev.windplayer

import dev.windplayer.ui.I18n

import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.vfs.formatDurationOsd
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.Canvas
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.SwingUtilities

/**
 * Drag-mode flag used by [CanvasMouseController] to remember which zone a drag
 * started in.
 *
 * 0 = none / not dragging; 1 = horizontal seek (center); 2 = volume (right);
 * 3 = brightness (left).
 */
private const val DRAG_NONE = 0
private const val DRAG_SEEK = 1
private const val DRAG_VOLUME = 2
private const val DRAG_BRIGHTNESS = 3

/**
 * Owns all mouse interaction on the video canvas: zone-based drag gestures
 * (PotPlayer style — left third = brightness, middle = seek, right = volume),
 * single vs double click detection, right-click context menu, middle-click
 * fullscreen toggle, wheel volume, and PiP-window drag.
 *
 * Constructed once and then [attach] is called to install the listeners.
 */
internal class CanvasMouseController(
    private val videoCanvas: Canvas,
    private val player: MpvPlayer,
    private val layoutManager: LayoutManager,
    private val osdEvents: MutableSharedFlow<String>,
    private val playlistToggle: MutableSharedFlow<Unit>,
    private val cheatsheetToggle: MutableSharedFlow<Unit>,
    private val skipNextCallback: () -> (() -> Unit)?
) {
    private var dragMode = DRAG_NONE
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragStartValue = 0.0
    private var dragOccurred = false

    fun attach() {
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
                if (layoutManager.currentScreen != dev.windplayer.ui.AppScreen.PLAYER) return
                if (dragMode == DRAG_NONE) return

                val dx = e.x - dragStartX
                val dy = dragStartY - e.y
                if (Math.abs(dx) > 5 || Math.abs(dy) > 5) dragOccurred = true

                when (dragMode) {
                    DRAG_SEEK -> {
                        val dur = try { player.getPropertyDouble("duration") } catch (_: Exception) { 0.0 }
                        val maxPos = if (dur > 0) dur else Double.MAX_VALUE
                        val newPos = (dragStartValue + dx).coerceIn(0.0, maxPos)
                        try { player.command("seek", "%.3f".format(newPos), "absolute") } catch (_: Exception) {}
                        if (dur > 0) osdEvents.tryEmit(formatDurationOsd(newPos, dur))
                    }
                    DRAG_VOLUME -> {
                        val newVol = (dragStartValue + dy * 0.5).coerceIn(0.0, 100.0).toInt()
                        try { player.setProperty("volume", newVol.toString()) } catch (_: Exception) {}
                        osdEvents.tryEmit("${I18n.get("osd_vol")}: $newVol%")
                    }
                    DRAG_BRIGHTNESS -> {
                        val newBright = (dragStartValue + dy * 0.5).coerceIn(-100.0, 100.0).toInt()
                        try { player.setProperty("brightness", newBright.toString()) } catch (_: Exception) {}
                        osdEvents.tryEmit("${I18n.get("osd_brightness")}: $newBright")
                    }
                }
                layoutManager.onMouseActivity()
            }
        })

        videoCanvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent?) {
                if (e == null || !SwingUtilities.isLeftMouseButton(e)) return
                if (layoutManager.isPip) {
                    try { layoutManager.startDrag(e.locationOnScreen) } catch (_: Exception) {}
                } else if (layoutManager.currentScreen == dev.windplayer.ui.AppScreen.PLAYER) {
                    val w = videoCanvas.width
                    dragMode = when {
                        e.x < w / 3 -> DRAG_BRIGHTNESS
                        e.x > w * 2 / 3 -> DRAG_VOLUME
                        else -> DRAG_SEEK
                    }
                    dragStartX = e.x
                    dragStartY = e.y
                    dragStartValue = when (dragMode) {
                        DRAG_SEEK -> try { player.getPropertyDouble("time-pos") } catch (_: Exception) { 0.0 }
                        DRAG_VOLUME -> try { player.getPropertyLong("volume").toDouble() } catch (_: Exception) { 100.0 }
                        DRAG_BRIGHTNESS -> try { player.getPropertyLong("brightness").toDouble() } catch (_: Exception) { 0.0 }
                        else -> 0.0
                    }
                    dragOccurred = false
                }
            }

            override fun mouseReleased(e: MouseEvent?) {
                dragMode = DRAG_NONE
            }

            override fun mouseClicked(e: MouseEvent?) {
                if (e == null) return
                if (SwingUtilities.isRightMouseButton(e)) {
                    if (layoutManager.currentScreen == dev.windplayer.ui.AppScreen.PLAYER) {
                        showContextMenu(
                            e = e,
                            videoCanvas = videoCanvas,
                            player = player,
                            layoutManager = layoutManager,
                            osdEvents = osdEvents,
                            playlistToggle = playlistToggle,
                            cheatsheetToggle = cheatsheetToggle,
                            skipNextCallback = skipNextCallback()
                        )
                    }
                    return
                }
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    if (layoutManager.currentScreen == dev.windplayer.ui.AppScreen.PLAYER) {
                        layoutManager.toggleFullscreen()
                    }
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
            if (layoutManager.currentScreen == dev.windplayer.ui.AppScreen.PLAYER) {
                val delta = if (e.wheelRotation < 0) 5 else -5
                val vol = player.getPropertyLong("volume")
                val newVol = (vol + delta).coerceIn(0, 100)
                player.setProperty("volume", newVol.toString())
                osdEvents.tryEmit("${I18n.get("osd_vol")}: $newVol%")
                layoutManager.onMouseActivity()
            }
        }
    }
}
