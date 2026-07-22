package dev.windplayer.mpv

import kotlinx.coroutines.flow.Flow

expect class MpvPlayer() {
    fun create()
    fun initialize()
    fun dispose()
    fun command(vararg args: String)
    fun setOption(key: String, value: String)
    fun setOption(key: String, value: Long)
    fun setProperty(key: String, value: String)
    fun setProperty(key: String, value: Long)
    fun getPropertyString(name: String): String?
    fun getPropertyLong(name: String): Long?
    fun getPropertyDouble(name: String): Double?
    fun observeProperty(name: String, format: MpvFormat)
    /**
     * Remove all property observers registered via [observeProperty].
     * Call on player screen teardown to prevent observer accumulation
     * across repeated Browser→Player transitions (H8).
     */
    fun clearPropertyObservers()
    val events: Flow<MpvEvent>
}

enum class MpvFormat {
    NONE, STRING, OSD_STRING, FLAG, INT64, DOUBLE, NODE, NODE_ARRAY, NODE_MAP, BYTE_ARRAY
}

sealed class MpvEvent {
    data class Idle(val dummy: Unit = Unit) : MpvEvent()
    data class FileLoaded(val dummy: Unit = Unit) : MpvEvent()
    data class EndFile(
        val reason: Int,
        val error: Int = 0,
        val playlistEntryId: Long = 0
    ) : MpvEvent()
    data class Error(val message: String) : MpvEvent()
    data class PropertyChange(val name: String, val value: Any?) : MpvEvent()
}

data class TrackInfo(
    val id: Int,
    val type: String,
    val codec: String?,
    val lang: String?,
    val title: String?,
    val isDefault: Boolean,
    val isForced: Boolean,
    val isSelected: Boolean,
    val isExternal: Boolean
)

data class MediaInfo(
    val duration: Double,
    val position: Double,
    val paused: Boolean,
    val tracks: List<TrackInfo>
)
