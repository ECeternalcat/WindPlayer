package dev.windplayer.mpv

import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

actual class MpvPlayer actual constructor() {

    private var handle: Long = 0
    private var eventThread: Thread? = null
    private var running = false
    private val _events = MutableSharedFlow<MpvEvent>(extraBufferCapacity = 64)
    actual val events: SharedFlow<MpvEvent> = _events

    private val lib: MpvLibrary by lazy { MpvLibrary.INSTANCE }

    private fun ptr(): Pointer {
        check(handle != 0L) { "mpv not initialized" }
        return Pointer(handle)
    }

    actual fun create() {
        check(handle == 0L) { "mpv already created" }
        val ctx = lib.mpv_create()
        if (ctx == Pointer.NULL) throw IllegalStateException("Failed to create mpv context")
        handle = Pointer.nativeValue(ctx)
        println("[MpvPlayer] context created, handle=$handle")
    }

    actual fun initialize() {
        check(handle != 0L) { "mpv not created, call create() first" }
        checkError(lib.mpv_initialize(ptr()))
        running = true
        println("[MpvPlayer] initialized")
        startEventLoop()
    }

    private fun startEventLoop() {
        eventThread = Thread({
            val ctxPtr = Pointer(handle)
            while (running) {
                val event = lib.mpv_wait_event(ctxPtr, 0.1)
                if (event == null) continue
                when (event.event_id) {
                    MPV_EVENT_FILE_LOADED -> {
                        println("[MpvPlayer] file loaded")
                        _events.tryEmit(MpvEvent.FileLoaded())
                    }
                    MPV_EVENT_IDLE -> _events.tryEmit(MpvEvent.Idle())
                    MPV_EVENT_END_FILE -> {
                        val reason = event.data?.getInt(0) ?: 0
                        println("[MpvPlayer] end file, reason=$reason")
                        _events.tryEmit(MpvEvent.EndFile(reason))
                    }
                }
            }
        }, "mpv-event-thread").also { it.isDaemon = true; it.start() }
    }

    actual fun dispose() {
        running = false
        if (handle != 0L) {
            lib.mpv_terminate_destroy(Pointer(handle))
            handle = 0
        }
    }

    actual fun command(vararg args: String) {
        if (handle == 0L) return
        val cmdStr = args.joinToString(" ")
        val code = lib.mpv_command_string(ptr(), cmdStr)
        if (code < 0) {
            println("[MpvPlayer] command($cmdStr) failed, error=$code")
        }
    }

    actual fun setOption(key: String, value: String) {
        if (handle == 0L) return
        val code = lib.mpv_set_option_string(ptr(), key, value)
        if (code < 0) println("[MpvPlayer] setOption($key=$value) failed, error=$code")
    }

    actual fun setOption(key: String, value: Long) {
        if (handle == 0L) return
        val code = lib.mpv_set_option(ptr(), key, MPV_FORMAT_INT64, longArrayOf(value))
        if (code < 0) println("[MpvPlayer] setOption($key=$value) failed, error=$code")
    }

    actual fun setProperty(key: String, value: String) {
        if (handle == 0L) return
        val code = lib.mpv_set_property_string(ptr(), key, value)
        if (code < 0) println("[MpvPlayer] setProperty($key=$value) failed, error=$code")
    }

    actual fun setProperty(key: String, value: Long) {
        if (handle == 0L) return
        val code = lib.mpv_set_property(ptr(), key, MPV_FORMAT_INT64, longArrayOf(value))
        if (code < 0) println("[MpvPlayer] setProperty($key=$value) failed, error=$code")
    }

    actual fun getPropertyString(name: String): String? {
        if (handle == 0L) return null
        val ptr = lib.mpv_get_property_string(ptr(), name) ?: return null
        val result = ptr.getString(0)
        lib.mpv_free(ptr)
        return result
    }

    actual fun getPropertyLong(name: String): Long {
        if (handle == 0L) return 0
        val arr = LongArray(1)
        lib.mpv_get_property(ptr(), name, MPV_FORMAT_INT64, arr)
        return arr[0]
    }

    actual fun getPropertyDouble(name: String): Double {
        if (handle == 0L) return 0.0
        val arr = DoubleArray(1)
        lib.mpv_get_property(ptr(), name, MPV_FORMAT_DOUBLE, arr)
        return arr[0]
    }

    actual fun observeProperty(name: String, format: MpvFormat) {
        if (handle == 0L) return
        lib.mpv_observe_property(ptr(), 0, name, format.ordinal)
    }

    private fun checkError(code: Int) {
        if (code < 0) throw RuntimeException("mpv error: $code")
    }

    companion object {
        const val MPV_EVENT_START_FILE = 6
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_IDLE = 11
        const val MPV_EVENT_TICK = 14
        const val MPV_EVENT_LOG_MESSAGE = 2
        const val MPV_EVENT_PROPERTY_CHANGE = 22
        const val MPV_EVENT_SEEK = 20
        const val MPV_EVENT_PLAYBACK_RESTART = 21
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
    }
}
