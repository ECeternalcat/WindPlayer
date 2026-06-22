package dev.windplayer.mpv

import com.sun.jna.*

@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
class MpvEventStructure : Structure() {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null
}

interface MpvLibrary : Library {
    companion object {
        val INSTANCE: MpvLibrary by lazy {
            val dllDir = System.getProperty("mpv.lib.path")
                ?: "./lib/mpv-dev"
            System.setProperty("jna.library.path", dllDir)
            Native.load("libmpv-2", MpvLibrary::class.java)
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

    fun mpv_request_log_messages(ctx: Pointer, min_level: String): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): MpvEventStructure?
    fun mpv_free(data: Pointer?)
}
