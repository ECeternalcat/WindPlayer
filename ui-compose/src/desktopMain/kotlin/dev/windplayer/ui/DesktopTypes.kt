package dev.windplayer.ui

import androidx.compose.runtime.Stable
import dev.windplayer.mpv.MpvPlayer
import dev.windplayer.vfs.VfsManager
import kotlinx.coroutines.flow.SharedFlow

/**
 * Read-only state flowing from the desktop bootstrap ([Main.kt]) into the shared
 * [App] composable. Grouped to keep [App]'s parameter list manageable.
 *
 * Mutations flow back through [DesktopAppCallbacks].
 */
@Stable
data class DesktopAppState(
    val player: MpvPlayer,
    val vfsManager: VfsManager,
    val settings: PlayerSettings = PlayerSettings.DEFAULT,
    val isFullscreen: Boolean = false,
    val recentFiles: List<RecentFile> = emptyList(),
    val bookmarks: List<String> = emptyList()
)

/**
 * Events flowing from [App] back to the desktop bootstrap.
 *
 * Implemented as an interface with no-op defaults so call sites only override
 * the callbacks they care about. See [NoOp] for the default empty instance.
 */
@Stable
interface DesktopAppCallbacks {
    fun onScreenChange(screen: AppScreen) {}
    fun onTracksToggle(expanded: Boolean) {}
    fun onToggleFullscreen() {}
    fun onOsdEmit(text: String) {}
    fun onSkipNextRegistered(callback: () -> Unit) {}
    fun onSettingsChanged(newSettings: PlayerSettings) {}
    fun onFilePlayed(name: String, path: String, isLocal: Boolean, serverId: String?) {}
    fun onPositionUpdate(filePath: String, position: Double, duration: Double) {}
    fun onBookmarkAdded(path: String) {}
    fun onBookmarkRemoved(path: String) {}

    companion object NoOp : DesktopAppCallbacks
}

/**
 * Cold [SharedFlow] streams the desktop bootstrap exposes to [App].
 *
 * Grouped because flows are conceptually a different kind of input than
 * snapshot [DesktopAppState] — they emit over time rather than being read at
 * composition time.
 */
@Stable
data class DesktopAppFlows(
    val osdEvents: SharedFlow<String>? = null,
    val dropFilePath: SharedFlow<String>? = null,
    val playlistToggle: SharedFlow<Unit>? = null,
    val cheatsheetToggle: SharedFlow<Unit>? = null
)

// ------------------------------------------------------------------
// PlayerScreen parameter objects
// ------------------------------------------------------------------

/**
 * Events flowing from [PlayerScreen] back to its host ([App]).
 *
 * `onJumpToFile` is invoked for **both** user-initiated jumps (playlist click)
 * and auto-play on EOF — the [PlayerScreen] `autoPlayNext` parameter gates the
 * latter independently.
 */
@Stable
interface PlayerCallbacks {
    fun onBack() {}
    fun onTracksToggle(expanded: Boolean) {}
    fun onToggleFullscreen() {}
    fun onJumpToFile(filePath: String) {}
    fun onOsdEvent(text: String) {}
    fun onPositionUpdate(filePath: String, position: Double, duration: Double) {}

    companion object NoOp : PlayerCallbacks
}

/**
 * Cold [SharedFlow] streams consumed by [PlayerScreen] (OSD feedback, keyboard
 * toggles from Main.kt).
 */
@Stable
data class PlayerFlows(
    val osdEvents: SharedFlow<String>? = null,
    val playlistToggle: SharedFlow<Unit>? = null,
    val cheatsheetToggle: SharedFlow<Unit>? = null
)

