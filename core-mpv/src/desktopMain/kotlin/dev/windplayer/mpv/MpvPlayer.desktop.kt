package dev.windplayer.mpv

import com.sun.jna.Pointer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.logging.Level
import java.util.logging.Logger

private val LOG = Logger.getLogger("dev.windplayer.mpv.MpvPlayer")

actual class MpvPlayer actual constructor() {

    @Volatile private var handle: Long = 0
    @Volatile private var eventThread: Thread? = null
    @Volatile private var running = false
    private val lock = Any()
    // Control events (FileLoaded/EndFile/error) must never be silently lost.
    // DROP_OLDEST keeps the most recent events when a slow collector backs up;
    // 256 is large enough for bursts of property changes during seek.
    private val eventChannel = Channel<MpvEvent>(Channel.UNLIMITED)
    actual val events: Flow<MpvEvent> = eventChannel.receiveAsFlow()

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
        LOG.info("context created, handle=$handle")
    }

    actual fun initialize() {
        check(handle != 0L) { "mpv not created, call create() first" }
        checkError(lib.mpv_initialize(ptr()))
        running = true
        LOG.info("initialized")
        startEventLoop()
    }

    private fun startEventLoop() {
        // Idempotent: never spawn a second event thread on duplicate initialize() calls.
        // mpv requires exactly one thread to call mpv_wait_event per context.
        synchronized(lock) {
            if (eventThread != null) return
            val t = Thread({
                val ctxPtr = Pointer(handle)
                while (running) {
                    val event = lib.mpv_wait_event(ctxPtr, 0.1)
                    if (event == null) continue
                    when (event.event_id) {
                        MPV_EVENT_FILE_LOADED -> {
                            LOG.fine("file loaded")
                            eventChannel.trySend(MpvEvent.FileLoaded())
                        }
                        MPV_EVENT_IDLE -> eventChannel.trySend(MpvEvent.Idle())
                        MPV_EVENT_END_FILE -> {
                            val end = event.data?.let { MpvEventEndFile(it).apply { read() } }
                            val reason = end?.reason ?: 4
                            val error = end?.error ?: -20
                            LOG.fine("end file, reason=$reason, error=$error")
                            eventChannel.trySend(MpvEvent.EndFile(reason, error, end?.playlist_entry_id ?: 0))
                        }
                        MPV_EVENT_PROPERTY_CHANGE -> {
                            val data = event.data
                            if (data != null) {
                                try {
                                    val property = MpvEventProperty(data).apply { read() }
                                    property.name?.let { namePtr ->
                                        val name = namePtr.getString(0)
                                        val valuePtr = property.data
                                        val format = property.format
                                        val value: Any? = when (format) {
                                            MPV_FORMAT_STRING -> valuePtr?.getPointer(0)?.getString(0)
                                            MPV_FORMAT_FLAG -> valuePtr != null && valuePtr.getInt(0) != 0
                                            MPV_FORMAT_INT64 -> valuePtr?.getLong(0)
                                            MPV_FORMAT_DOUBLE -> valuePtr?.getDouble(0)
                                            else -> null // NONE / OSD_STRING / NODE / BYTE_ARRAY — not handled
                                        }
                                        eventChannel.trySend(MpvEvent.PropertyChange(name, value))
                                    }
                                } catch (e: Exception) {
                                    LOG.log(Level.WARNING, "malformed property event", e)
                                }
                            }
                        }
                    }
                }
            }, "mpv-event-thread").also { it.isDaemon = true }
            eventThread = t
            t.start()
        }
    }

    actual fun dispose() {
        synchronized(lock) {
            running = false
            if (handle != 0L) lib.mpv_wakeup(Pointer(handle))
        }
        eventThread?.join(2000)
        if (eventThread?.isAlive == true) {
            LOG.severe("event thread did not terminate after mpv_wakeup; preserving native context to avoid use-after-free")
            return
        }
        synchronized(lock) {
            eventThread = null
            if (handle != 0L) {
                lib.mpv_terminate_destroy(Pointer(handle))
                handle = 0
            }
        }
    }

    actual fun command(vararg args: String) {
        synchronized(lock) {
            if (handle == 0L) return
            // Use the array form (mpv_command) with a NULL terminator rather
            // than mpv_command_string. The string form splits on spaces, so
            // any path containing a space (very common: "C:/My Videos/x.mkv")
            // would be mis-parsed as multiple arguments and fail to load.
            val cmdArr = arrayOfNulls<String>(args.size + 1)
            System.arraycopy(args, 0, cmdArr, 0, args.size)
            // cmdArr[args.size] stays null — mpv requires the NULL sentinel.
            val code = lib.mpv_command(ptr(), cmdArr)
            if (code < 0) {
                LOG.warning("command(${args.joinToString(" ")}) failed, error=$code")
            }
        }
    }

    actual fun setOption(key: String, value: String) {
        synchronized(lock) {
            if (handle == 0L) return
            val code = lib.mpv_set_option_string(ptr(), key, value)
            if (code < 0) LOG.warning("setOption($key=$value) failed, error=$code")
        }
    }

    actual fun setOption(key: String, value: Long) {
        synchronized(lock) {
            if (handle == 0L) return
            val code = lib.mpv_set_option(ptr(), key, MPV_FORMAT_INT64, longArrayOf(value))
            if (code < 0) LOG.warning("setOption($key=$value) failed, error=$code")
        }
    }

    actual fun setProperty(key: String, value: String) {
        synchronized(lock) {
            if (handle == 0L) return
            val code = lib.mpv_set_property_string(ptr(), key, value)
            if (code < 0) LOG.warning("setProperty($key=$value) failed, error=$code")
        }
    }

    actual fun setProperty(key: String, value: Long) {
        synchronized(lock) {
            if (handle == 0L) return
            val code = lib.mpv_set_property(ptr(), key, MPV_FORMAT_INT64, longArrayOf(value))
            if (code < 0) LOG.warning("setProperty($key=$value) failed, error=$code")
        }
    }

    actual fun getPropertyString(name: String): String? {
        synchronized(lock) {
            if (handle == 0L) return null
            val ptr = lib.mpv_get_property_string(ptr(), name) ?: return null
            val result = ptr.getString(0)
            lib.mpv_free(ptr)
            return result
        }
    }

    actual fun getPropertyLong(name: String): Long? {
        synchronized(lock) {
            if (handle == 0L) return null
            val arr = LongArray(1)
            if (lib.mpv_get_property(ptr(), name, MPV_FORMAT_INT64, arr) < 0) return null
            return arr[0]
        }
    }

    actual fun getPropertyDouble(name: String): Double? {
        synchronized(lock) {
            if (handle == 0L) return null
            val arr = DoubleArray(1)
            if (lib.mpv_get_property(ptr(), name, MPV_FORMAT_DOUBLE, arr) < 0) return null
            return arr[0]
        }
    }

    actual fun observeProperty(name: String, format: MpvFormat) {
        synchronized(lock) {
            if (handle == 0L) return
            lib.mpv_observe_property(ptr(), 0, name, format.ordinal)
        }
    }

    actual fun clearPropertyObservers() {
        synchronized(lock) {
            if (handle == 0L) return
            // reply_userdata=0 is used by all observeProperty() calls above;
            // mpv_unobserve_property(handle, 0) removes every observer in that
            // group. Prevents observer accumulation across PlayerScreen re-entries.
            lib.mpv_unobserve_property(ptr(), 0)
        }
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
        const val MPV_FORMAT_NONE = 0
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
    }
}
