package dev.windplayer.mpv

import com.sun.jna.Pointer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
    private val _events = MutableSharedFlow<MpvEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
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
                            _events.tryEmit(MpvEvent.FileLoaded())
                        }
                        MPV_EVENT_IDLE -> _events.tryEmit(MpvEvent.Idle())
                        MPV_EVENT_END_FILE -> {
                            // L13: default to 4 (ERROR) rather than 0 (EOF) so a
                            // null data pointer is distinguishable from natural end.
                            val reason = event.data?.getInt(0) ?: 4
                            LOG.fine("end file, reason=$reason")
                            _events.tryEmit(MpvEvent.EndFile(reason))
                        }
                        MPV_EVENT_PROPERTY_CHANGE -> {
                            // mpv_event_property layout (x86_64):
                            //   offset  0: const char* name
                            //   offset  8: mpv_format format (int)
                            //   offset 16: void* data
                            val data = event.data
                            if (data != null) {
                                try {
                                    val namePtr = data.getPointer(0)
                                    if (namePtr != null) {
                                        val name = namePtr.getString(0)
                                        val format = data.getInt(8)
                                        val valuePtr = data.getPointer(16)
                                        val value: Any? = when (format) {
                                            MPV_FORMAT_STRING -> valuePtr?.getString(0)
                                            MPV_FORMAT_FLAG -> valuePtr != null && valuePtr.getInt(0) != 0
                                            MPV_FORMAT_INT64 -> valuePtr?.getLong(0)
                                            MPV_FORMAT_DOUBLE -> valuePtr?.getDouble(0)
                                            else -> null // NONE / OSD_STRING / NODE / BYTE_ARRAY — not handled
                                        }
                                        _events.tryEmit(MpvEvent.PropertyChange(name, value))
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
        }
        // mpv_terminate_destroy wakes a blocked mpv_wait_event, so the event
        // thread will fall out of its loop promptly. Join BEFORE destroying
        // the context — calling any mpv API after terminate_destroy is UB.
        eventThread?.join(2000)
        // L1: check whether the event thread actually terminated. If it didn't
        // (e.g. stuck inside mpv_wait_event), proceeding to terminate_destroy
        // is a use-after-free risk — log loudly so it's diagnosable.
        if (eventThread?.isAlive == true) {
            LOG.warning("event thread did not terminate within 2s; destroy may race")
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

    actual fun getPropertyLong(name: String): Long {
        synchronized(lock) {
            if (handle == 0L) return 0
            val arr = LongArray(1)
            lib.mpv_get_property(ptr(), name, MPV_FORMAT_INT64, arr)
            return arr[0]
        }
    }

    actual fun getPropertyDouble(name: String): Double {
        synchronized(lock) {
            if (handle == 0L) return 0.0
            val arr = DoubleArray(1)
            lib.mpv_get_property(ptr(), name, MPV_FORMAT_DOUBLE, arr)
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
