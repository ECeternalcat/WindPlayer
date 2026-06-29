package dev.windplayer

import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.ui.I18n
import dev.windplayer.vfs.formatDuration
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.Canvas
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JMenu
import javax.swing.JPopupMenu

/**
 * Build and show the PotPlayer-style right-click context menu on the video canvas.
 *
 * Menu labels are i18n-driven; certain items (fullscreen/PiP/play-pause/mute) show
 * dynamically based on the current player and layout state.
 */
internal fun showContextMenu(
    e: MouseEvent,
    videoCanvas: Canvas,
    player: MpvPlayer,
    layoutManager: LayoutManager,
    osdEvents: MutableSharedFlow<String>,
    playlistToggle: MutableSharedFlow<Unit>,
    cheatsheetToggle: MutableSharedFlow<Unit>,
    skipNextCallback: (() -> Unit)?
) {
    layoutManager.onMouseActivity()
    val popup = JPopupMenu()

    // ---- Play / Pause (dynamic) ----
    val isPaused = try { player.getPropertyString("pause") == "yes" } catch (_: Exception) { false }
    popup.add(JMenuItem(if (isPaused) I18n.get("play") else I18n.get("pause")).apply {
        addActionListener {
            player.command("cycle", "pause")
            val paused = try { player.getPropertyString("pause") == "yes" } catch (_: Exception) { false }
            osdEvents.tryEmit(if (paused) "|| ${I18n.get("osd_paused")}" else "> ${I18n.get("osd_playing")}")
        }
    })

    popup.addSeparator()

    // ---- Fullscreen (dynamic) ----
    popup.add(JMenuItem(if (layoutManager.isFullscreen) I18n.get("exit_fullscreen") else I18n.get("fullscreen")).apply {
        addActionListener { layoutManager.toggleFullscreen() }
    })

    popup.addSeparator()

    // ---- PiP (dynamic) ----
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

    // ---- Mute (dynamic) ----
    val isMuted = try { player.getPropertyString("mute") == "yes" } catch (_: Exception) { false }
    popup.add(JMenuItem(if (isMuted) I18n.get("unmute") else I18n.get("mute")).apply {
        addActionListener {
            player.command("cycle", "mute")
            val muted = try { player.getPropertyString("mute") == "yes" } catch (_: Exception) { false }
            osdEvents.tryEmit(if (muted) I18n.get("osd_muted") else "${I18n.get("osd_vol")}: ${player.getPropertyLong("volume")}%")
        }
    })

    popup.addSeparator()

    // ---- Subtitle ----
    popup.add(JMenu(I18n.get("subtitle")).apply {
        add(JMenuItem(I18n.get("next_subtitle")).apply {
            addActionListener {
                player.command("cycle", "sid")
                val sid = player.getPropertyString("sid") ?: "no"
                osdEvents.tryEmit(if (sid == "no") I18n.get("osd_subtitle_off") else String.format(I18n.get("osd_subtitle_num"), sid))
            }
        })
        addSeparator()
        add(JMenuItem(I18n.get("sub_delay_neg")).apply {
            addActionListener {
                player.command("add", "sub-delay", "-0.1")
                osdEvents.tryEmit("${I18n.get("osd_sub_delay")}: %+.1fs".format(player.getPropertyDouble("sub-delay")))
            }
        })
        add(JMenuItem(I18n.get("sub_delay_pos")).apply {
            addActionListener {
                player.command("add", "sub-delay", "0.1")
                osdEvents.tryEmit("${I18n.get("osd_sub_delay")}: %+.1fs".format(player.getPropertyDouble("sub-delay")))
            }
        })
        add(JMenuItem(I18n.get("reset_sub_delay")).apply {
            addActionListener {
                player.setProperty("sub-delay", "0")
                osdEvents.tryEmit("${I18n.get("osd_sub_delay")}: +0.0s")
            }
        })
    })

    // ---- Audio ----
    popup.add(JMenu(I18n.get("audio")).apply {
        add(JMenuItem(I18n.get("next_audio")).apply {
            addActionListener {
                player.command("cycle", "aid")
                val aid = player.getPropertyString("aid") ?: "no"
                osdEvents.tryEmit(if (aid == "no") I18n.get("osd_audio_off") else String.format(I18n.get("osd_audio_num"), aid))
            }
        })
        addSeparator()
        add(JMenuItem(I18n.get("audio_delay_neg")).apply {
            addActionListener {
                player.command("add", "audio-delay", "-0.1")
                osdEvents.tryEmit("${I18n.get("osd_audio_delay")}: %+.1fs".format(player.getPropertyDouble("audio-delay")))
            }
        })
        add(JMenuItem(I18n.get("audio_delay_pos")).apply {
            addActionListener {
                player.command("add", "audio-delay", "0.1")
                osdEvents.tryEmit("${I18n.get("osd_audio_delay")}: %+.1fs".format(player.getPropertyDouble("audio-delay")))
            }
        })
        add(JMenuItem(I18n.get("reset_audio_delay")).apply {
            addActionListener {
                player.setProperty("audio-delay", "0")
                osdEvents.tryEmit("${I18n.get("osd_audio_delay")}: +0.0s")
            }
        })
    })

    popup.addSeparator()

    // ---- Speed ----
    popup.add(JMenu(I18n.get("speed")).apply {
        add(JMenuItem(I18n.get("slower")).apply {
            addActionListener {
                val speed = player.getPropertyDouble("speed")
                val newSpeed = maxOf(speed - 0.25, 0.25)
                player.setProperty("speed", "%.2f".format(newSpeed))
                osdEvents.tryEmit("${I18n.get("speed")}: %.2fx".format(newSpeed))
            }
        })
        add(JMenuItem(I18n.get("faster")).apply {
            addActionListener {
                val speed = player.getPropertyDouble("speed")
                val newSpeed = minOf(speed + 0.25, 4.0)
                player.setProperty("speed", "%.2f".format(newSpeed))
                osdEvents.tryEmit("${I18n.get("speed")}: %.2fx".format(newSpeed))
            }
        })
        add(JMenuItem(I18n.get("normal_speed")).apply {
            addActionListener {
                player.setProperty("speed", "1.00")
                osdEvents.tryEmit("${I18n.get("speed")}: 1.00x")
            }
        })
    })

    // ---- A-B Loop ----
    popup.add(JMenu(I18n.get("ab_loop")).apply {
        add(JMenuItem(I18n.get("set_a")).apply {
            addActionListener {
                val pos = player.getPropertyDouble("time-pos")
                player.setProperty("ab-loop-a", "%.3f".format(pos))
                osdEvents.tryEmit("${I18n.get("osd_ab_a")}: ${formatDuration(pos)}")
            }
        })
        add(JMenuItem(I18n.get("set_b")).apply {
            addActionListener {
                val pos = player.getPropertyDouble("time-pos")
                player.setProperty("ab-loop-b", "%.3f".format(pos))
                osdEvents.tryEmit("${I18n.get("osd_ab_b")}: ${formatDuration(pos)}")
            }
        })
        add(JMenuItem(I18n.get("clear_ab")).apply {
            addActionListener {
                player.setProperty("ab-loop-a", "no")
                player.setProperty("ab-loop-b", "no")
                osdEvents.tryEmit(I18n.get("osd_ab_clear"))
            }
        })
    })

    // ---- Video EQ ----
    popup.add(JMenu(I18n.get("video_eq")).apply {
        addEqItem("brightness_neg", "brightness", -5, osdEvents, player)
        addEqItem("brightness_pos", "brightness", 5, osdEvents, player)
        addEqItem("contrast_neg", "contrast", -5, osdEvents, player)
        addEqItem("contrast_pos", "contrast", 5, osdEvents, player)
        addEqItem("saturation_neg", "saturation", -5, osdEvents, player)
        addEqItem("saturation_pos", "saturation", 5, osdEvents, player)
        addEqItem("gamma_neg", "gamma", -5, osdEvents, player)
        addEqItem("gamma_pos", "gamma", 5, osdEvents, player)
        addSeparator()
        add(JMenuItem(I18n.get("reset_all_eq")).apply {
            addActionListener {
                player.setProperty("brightness", "0")
                player.setProperty("contrast", "0")
                player.setProperty("saturation", "0")
                player.setProperty("gamma", "0")
                osdEvents.tryEmit(I18n.get("eq_reset"))
            }
        })
    })

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
            player.command("screenshot", "subtitles")
            osdEvents.tryEmit(I18n.get("osd_screenshot_saved"))
        }
    })
    popup.add(JMenuItem(I18n.get("shortcuts_f1")).apply {
        addActionListener { cheatsheetToggle.tryEmit(Unit) }
    })

    popup.show(videoCanvas, e.x, e.y)
}

/** Helper for the repetitive `add(brightness, "brightness", -5, osd, player)` pattern. */
private fun JMenu.addEqItem(
    key: String,
    property: String,
    delta: Int,
    osdEvents: MutableSharedFlow<String>,
    player: MpvPlayer
) {
    add(JMenuItem(I18n.get(key)).apply {
        val label = when (property) {
            "brightness" -> I18n.get("osd_brightness")
            "contrast" -> I18n.get("osd_contrast")
            "saturation" -> I18n.get("osd_saturation")
            "gamma" -> I18n.get("osd_gamma")
            else -> property.replaceFirstChar { it.uppercase() }
        }
        addActionListener {
            player.command("add", property, delta.toString())
            osdEvents.tryEmit("$label: ${player.getPropertyLong(property)}")
        }
    })
}
