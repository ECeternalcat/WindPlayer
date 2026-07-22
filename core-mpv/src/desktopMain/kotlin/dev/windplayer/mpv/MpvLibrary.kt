package dev.windplayer.mpv

import com.sun.jna.*

@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
class MpvEventStructure : Structure() {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null
}

@Structure.FieldOrder("name", "format", "data")
class MpvEventProperty(pointer: Pointer) : Structure(pointer) {
    @JvmField var name: Pointer? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null
}

@Structure.FieldOrder("reason", "error", "playlist_entry_id", "playlist_insert_id", "playlist_insert_num_entries")
class MpvEventEndFile(pointer: Pointer) : Structure(pointer) {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    @JvmField var playlist_entry_id: Long = 0
    @JvmField var playlist_insert_id: Long = 0
    @JvmField var playlist_insert_num_entries: Int = 0
}

interface MpvLibrary : Library {
    companion object {
        val INSTANCE: MpvLibrary by lazy {
            val osName = System.getProperty("os.name").lowercase()
            val dllName = when {
                osName.contains("win") -> "libmpv-2.dll"
                osName.contains("mac") || osName.contains("darwin") -> "libmpv.dylib"
                else -> "libmpv.so"
            }

            // Search for the DLL in candidate locations — the working directory
            // varies (project root for `gradlew run`, app-desktop/ for IDE runs,
            // build/ for distributions). Try them all.
            val candidates = listOfNotNull(
                System.getProperty("mpv.lib.path"),
                MpvLibrary::class.java.protectionDomain.codeSource?.location
                    ?.let { runCatching { java.io.File(it.toURI()).parentFile }.getOrNull() }
                    ?.let { java.io.File(it, "mpv-dev").absolutePath },
                "./lib/mpv-dev",                      // cwd = project root
                "../lib/mpv-dev",                     // cwd = app-desktop
                "../../lib/mpv-dev"                   // cwd = app-desktop/build/..
            )

            val dllFile = candidates
                .map { java.io.File(it, dllName) }
                .firstOrNull { it.exists() }

            if (dllFile != null) {
                Native.load(dllFile.absolutePath, MpvLibrary::class.java)
            } else {
                // Last resort: let JNA search by name
                candidates.forEach { System.setProperty("jna.library.path", it) }
                val systemLibraryName = if (osName.contains("win")) "libmpv-2" else "mpv"
                Native.load(systemLibraryName, MpvLibrary::class.java)
            }
        }
    }

    fun mpv_create(): Pointer
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_destroy(ctx: Pointer)
    fun mpv_terminate_destroy(ctx: Pointer)

    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_command_string(ctx: Pointer, args: String): Int

    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_option(ctx: Pointer, name: String, format: Int, data: Any): Int

    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property(ctx: Pointer, name: String, format: Int, data: Any): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: Any): Int

    fun mpv_observe_property(ctx: Pointer, reply: Long, name: String, format: Int): Int
    fun mpv_unobserve_property(ctx: Pointer, id: Long): Int

    fun mpv_request_log_messages(ctx: Pointer, min_level: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): MpvEventStructure?
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_free(data: Pointer?)
}
