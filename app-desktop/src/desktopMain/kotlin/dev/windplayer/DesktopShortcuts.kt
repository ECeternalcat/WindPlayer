package dev.windplayer

import dev.windplayer.ui.I18n

import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.vfs.formatDuration
import dev.windplayer.vfs.formatDurationOsd
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.KeyStroke

/**
 * Holds the shared state that keyboard actions need to read or mutate.
 *
 * Created once in [main] and passed to [bindDesktopShortcuts]. The
 * `skipNextCallback` is populated at runtime via the `App`'s
 * `onSkipNextRegistered` callback.
 */
internal class DesktopShortcutContext(
    val player: MpvPlayer,
    val layoutManager: LayoutManager,
    val osdEvents: MutableSharedFlow<String>,
    val playlistToggle: MutableSharedFlow<Unit>,
    val cheatsheetToggle: MutableSharedFlow<Unit>
) {
    /**
     * Set by the App composable via `onSkipNextRegistered` (Compose UI thread).
     * Read by the `N` shortcut and context-menu items (Swing EDT).
     * @Volatile ensures the EDT read observes the Compose-side write.
     */
    @Volatile
    var skipNextCallback: (() -> Unit)? = null

    fun isPlayer(): Boolean = layoutManager.currentScreen == dev.windplayer.ui.AppScreen.PLAYER
}

/**
 * Registers every global keyboard shortcut on [rootPane].
 *
 * Each binding is a small inline lambda reading from [ctx]. Player-only actions
 * check [DesktopShortcutContext.isPlayer] before mutating mpv state.
 */
internal fun bindDesktopShortcuts(rootPane: JRootPane, ctx: DesktopShortcutContext) {
    val inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
    val actionMap = rootPane.actionMap

    fun bind(keyStroke: KeyStroke, name: String, action: () -> Unit) {
        inputMap.put(keyStroke, name)
        actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) = action()
        })
    }

    val player = ctx.player
    val osd = ctx.osdEvents
    val lm = ctx.layoutManager
    val playlist = ctx.playlistToggle
    val cheatsheet = ctx.cheatsheetToggle
    val isPlayer = { ctx.isPlayer() }

    // ---------- Playback ----------
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "togglePause") {
        if (isPlayer()) {
            player.command("cycle", "pause")
            val paused = player.getPropertyString("pause") == "yes"
            osd.tryEmit(if (paused) "|| ${I18n.get("osd_paused")}" else "> ${I18n.get("osd_playing")}")
            lm.onMouseActivity()
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "toggleFs") {
        if (isPlayer()) lm.toggleFullscreen()
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "toggleFsF11") {
        lm.toggleFullscreen()
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), "togglePip") {
        if (isPlayer()) lm.togglePip()
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "exitFs") {
        if (lm.isFullscreen) lm.exitFullscreen()
        else if (lm.isPip) lm.exitPip()
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_N, 0), "nextFile") {
        if (isPlayer()) ctx.skipNextCallback?.invoke()
    }

    // ---------- Seek ----------
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "seekBack5") {
        if (isPlayer()) {
            player.command("seek", "-5")
            val pos = player.getPropertyDouble("time-pos")
            val dur = player.getPropertyDouble("duration")
            if (dur > 0 && pos >= 0) osd.tryEmit("<< -5s  ${formatDurationOsd(pos, dur)}")
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "seekFwd5") {
        if (isPlayer()) {
            player.command("seek", "5")
            val pos = player.getPropertyDouble("time-pos")
            val dur = player.getPropertyDouble("duration")
            if (dur > 0 && pos >= 0) osd.tryEmit(">> +5s  ${formatDurationOsd(pos, dur)}")
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK), "seekBack30") {
        if (isPlayer()) {
            player.command("seek", "-30")
            val pos = player.getPropertyDouble("time-pos")
            val dur = player.getPropertyDouble("duration")
            if (dur > 0 && pos >= 0) osd.tryEmit("<< -30s  ${formatDurationOsd(pos, dur)}")
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK), "seekFwd30") {
        if (isPlayer()) {
            player.command("seek", "30")
            val pos = player.getPropertyDouble("time-pos")
            val dur = player.getPropertyDouble("duration")
            if (dur > 0 && pos >= 0) osd.tryEmit(">> +30s  ${formatDurationOsd(pos, dur)}")
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, 0), "frameStep") {
        if (isPlayer()) {
            player.command("frame-step")
            osd.tryEmit(I18n.get("osd_frame_plus"))
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, 0), "frameBackStep") {
        if (isPlayer()) {
            player.command("frame-back-step")
            osd.tryEmit(I18n.get("osd_frame_minus"))
        }
    }

    // ---------- Volume ----------
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "volUp") {
        if (isPlayer()) adjustVolume(player, osd, +5)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "volDown") {
        if (isPlayer()) adjustVolume(player, osd, -5)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_M, 0), "toggleMute") {
        if (isPlayer()) {
            player.command("cycle", "mute")
            val muted = player.getPropertyString("mute") == "yes"
            osd.tryEmit(if (muted) I18n.get("osd_muted") else "${I18n.get("osd_vol")}: ${player.getPropertyLong("volume")}%")
        }
    }

    // ---------- Speed ----------
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_CLOSE_BRACKET, 0), "speedUp") {
        if (isPlayer()) adjustSpeed(player, osd, +0.25)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, 0), "speedDown") {
        if (isPlayer()) adjustSpeed(player, osd, -0.25)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SLASH, 0), "speedReset") {
        if (isPlayer()) {
            player.setProperty("speed", "1.00")
            osd.tryEmit("${I18n.get("speed")}: 1.00x")
        }
    }

    // ---------- Tracks / delays ----------
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_V, 0), "cycleSub") {
        if (isPlayer()) {
            player.command("cycle", "sid")
            val sid = player.getPropertyString("sid") ?: "no"
            osd.tryEmit(if (sid == "no") "Subtitle: Off" else "Subtitle: #$sid")
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_B, 0), "cycleAudio") {
        if (isPlayer()) {
            player.command("cycle", "aid")
            val aid = player.getPropertyString("aid") ?: "no"
            osd.tryEmit(if (aid == "no") "Audio: Off" else "Audio: #$aid")
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, 0), "subDelayDown") {
        if (isPlayer()) adjustDelay(player, osd, "sub-delay", -0.1)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_X, 0), "subDelayUp") {
        if (isPlayer()) adjustDelay(player, osd, "sub-delay", +0.1)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.SHIFT_DOWN_MASK), "subDelayReset") {
        if (isPlayer()) {
            player.setProperty("sub-delay", "0")
            osd.tryEmit("${I18n.get("osd_sub_delay")}: +0.0s")
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), "audioDelayDown") {
        if (isPlayer()) adjustDelay(player, osd, "audio-delay", -0.1)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_H, 0), "audioDelayUp") {
        if (isPlayer()) adjustDelay(player, osd, "audio-delay", +0.1)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.SHIFT_DOWN_MASK), "audioDelayReset") {
        if (isPlayer()) {
            player.setProperty("audio-delay", "0")
            osd.tryEmit("${I18n.get("osd_audio_delay")}: +0.0s")
        }
    }

    // ---------- Video EQ ----------
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "brightDown") {
        if (isPlayer()) adjustEq(player, osd, "brightness", -5)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "brightUp") {
        if (isPlayer()) adjustEq(player, osd, "brightness", +5)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_3, 0), "contrastDown") {
        if (isPlayer()) adjustEq(player, osd, "contrast", -5)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_4, 0), "contrastUp") {
        if (isPlayer()) adjustEq(player, osd, "contrast", +5)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_5, 0), "satDown") {
        if (isPlayer()) adjustEq(player, osd, "saturation", -5)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_6, 0), "satUp") {
        if (isPlayer()) adjustEq(player, osd, "saturation", +5)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_7, 0), "gammaDown") {
        if (isPlayer()) adjustEq(player, osd, "gamma", -5)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_8, 0), "gammaUp") {
        if (isPlayer()) adjustEq(player, osd, "gamma", +5)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_0, 0), "eqReset") {
        if (isPlayer()) {
            player.setProperty("brightness", "0")
            player.setProperty("contrast", "0")
            player.setProperty("saturation", "0")
            player.setProperty("gamma", "0")
            osd.tryEmit(I18n.get("eq_reset"))
        }
    }

    // ---------- A-B Loop ----------
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0), "abLoopA") {
        if (isPlayer()) {
            val pos = player.getPropertyDouble("time-pos")
            player.setProperty("ab-loop-a", "%.3f".format(pos))
            osd.tryEmit("${I18n.get("osd_ab_a")}: ${formatDuration(pos)}")
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.SHIFT_DOWN_MASK), "abLoopB") {
        if (isPlayer()) {
            val pos = player.getPropertyDouble("time-pos")
            player.setProperty("ab-loop-b", "%.3f".format(pos))
            osd.tryEmit("${I18n.get("osd_ab_b")}: ${formatDuration(pos)}")
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.SHIFT_DOWN_MASK), "abLoopClear") {
        if (isPlayer()) {
            player.setProperty("ab-loop-a", "no")
            player.setProperty("ab-loop-b", "no")
            osd.tryEmit(I18n.get("osd_ab_clear"))
        }
    }

    // ---------- Screenshot / Playlist / Cheatsheet ----------
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, 0), "screenshot") {
        if (isPlayer()) {
            player.command("screenshot")
            osd.tryEmit(I18n.get("osd_screenshot_saved"))
        }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "togglePlaylist") {
        if (isPlayer()) playlist.tryEmit(Unit)
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "cheatsheet") {
        if (isPlayer()) cheatsheet.tryEmit(Unit)
    }
}

// ------------------------------------------------------------------
// Shared action helpers (used by both keyboard shortcuts and context menu)
// ------------------------------------------------------------------

internal fun adjustVolume(player: MpvPlayer, osd: MutableSharedFlow<String>, delta: Int) {
    val vol = player.getPropertyLong("volume")
    val newVol = (vol + delta).coerceIn(0, 100)
    player.setProperty("volume", newVol.toString())
    osd.tryEmit("${I18n.get("osd_vol")}: $newVol%")
}

internal fun adjustSpeed(player: MpvPlayer, osd: MutableSharedFlow<String>, delta: Double) {
    val speed = player.getPropertyDouble("speed")
    val newSpeed = (speed + delta).coerceIn(0.25, 4.0)
    player.setProperty("speed", "%.2f".format(newSpeed))
    osd.tryEmit("${I18n.get("speed")}: %.2fx".format(newSpeed))
}

internal fun adjustDelay(player: MpvPlayer, osd: MutableSharedFlow<String>, property: String, delta: Double) {
    player.command("add", property, delta.toString())
    val value = player.getPropertyDouble(property)
    val label = if (property == "sub-delay") "Sub delay" else "Audio delay"
    osd.tryEmit("$label: %+.1fs".format(value))
}

internal fun adjustEq(player: MpvPlayer, osd: MutableSharedFlow<String>, property: String, delta: Int) {
    player.command("add", property, delta.toString())
    val value = player.getPropertyLong(property)
    val label = property.replaceFirstChar { it.uppercase() }
    osd.tryEmit("$label: $value")
}
