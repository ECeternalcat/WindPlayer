package dev.windplayer.mpv

import android.content.Context
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

actual class MpvPlayer actual constructor() {

    private val lock = Any()
    // Control events must never be silently lost; DROP_OLDEST keeps the newest.
    private val _events = MutableSharedFlow<MpvEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    actual val events: SharedFlow<MpvEvent> = _events
    private var created = false
    private var initialized = false
    // CON-4: read inside the JNI event-thread observer callback (event()),
    // reset by dispose() on the caller thread. Marked @Volatile so the
    // disposing thread's write is visible to the event thread (JMM).
    @Volatile
    private var fileLoadedBefore = false
    // H9: guard prevents duplicate observer registration when MobilePlayerScreen
    // re-enters composition (Browser→Player→Browser→Player). Without it, each
    // entry adds another observer batch and every event fires N times.
    private var observerAdded = false
    // H3: cached eof-reached state, updated by the property observer.
    // Reading this avoids a synchronous JNI re-entry inside the END_FILE
    // event callback (which can deadlock if libplayer.so holds an internal
    // mutex during event dispatch).
    @Volatile
    private var eofReached = false

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) { _events.tryEmit(MpvEvent.PropertyChange(property, null)) }
        override fun eventProperty(property: String, value: Long) { _events.tryEmit(MpvEvent.PropertyChange(property, value)) }
        override fun eventProperty(property: String, value: Boolean) {
            if (property == "eof-reached") eofReached = value
            _events.tryEmit(MpvEvent.PropertyChange(property, value))
        }
        override fun eventProperty(property: String, value: String) { _events.tryEmit(MpvEvent.PropertyChange(property, value)) }
        override fun eventProperty(property: String, value: Double) { _events.tryEmit(MpvEvent.PropertyChange(property, value)) }
        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    fileLoadedBefore = true
                    _events.tryEmit(MpvEvent.FileLoaded())
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    // libplayer.so's `event(int)` JNI callback does NOT pass the
                    // mpv_event_end_file.reason field, so we infer it from the
                    // observable mpv state at the moment END_FILE fires:
                    //   - eof-reached == yes -> reason 0 (EOF, natural end of file)
                    //   - otherwise, file was loaded earlier -> reason 2 (STOP,
                    //     e.g. user pressed back / issued `stop` / loadfile replaced it)
                    //   - otherwise -> reason 4 (ERROR, file failed to open)
                    //
                    // Critical for MobilePlayerScreen auto-play: distinguishing
                    // STOP from EOF prevents auto-playing the next file when the
                    // user explicitly pressed back.
                    //
                    // H3: read the cached `eofReached` instead of synchronously
                    // re-entering JNI via MPVLib.getPropertyString(). The observer
                    // keeps the cache in sync; re-entering JNI inside the event
                    // callback risks a native-side lock-ordering deadlock.
                    val reason = inferEndFileReason(fileLoadedBefore)
                    fileLoadedBefore = false
                    _events.tryEmit(MpvEvent.EndFile(reason))
                }
                MPVLib.MpvEvent.MPV_EVENT_IDLE -> _events.tryEmit(MpvEvent.Idle())
            }
        }
    }

    /**
     * Infer the mpv end_file reason from cached observable state.
     *
     * MPV_END_FILE_REASON values (per `lib/mpv-dev/include/mpv/client.h`):
     *   0 = EOF       — natural end of file
     *   2 = STOP      — external action (stop command / new loadfile)
     *   3 = QUIT      — player shutdown
     *   4 = ERROR     — file failed to load
     *   5 = REDIRECT  — playlist redirect (treated as EOF for our purposes)
     */
    private fun inferEndFileReason(wasLoaded: Boolean): Int {
        // Read the observer-cached flag — NO JNI re-entry.
        return when {
            eofReached -> 0
            wasLoaded -> 2
            else -> 4
        }
    }

    fun initAndroid(context: Context) {
        synchronized(lock) {
            if (observerAdded) return
            MPVLib.addObserver(observer)
            // Observe eof-reached so inferEndFileReason can read the cached
            // value instead of synchronously re-entering JNI from the END_FILE
            // callback (H3).
            if (initialized) {
                try { MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG) } catch (_: Exception) {}
            }
            observerAdded = true
        }
    }

    // M5: the common expect fun create() is a no-op stub on Android. The real
    // entry point is createWithContext(context). Log a warning so callers that
    // accidentally use create() (e.g. shared code) are diagnosable.
    actual fun create() {
        synchronized(lock) { if (!created) { Log.w(TAG, "create() called — use createWithContext(context) on Android"); created = true } }
    }

    fun createWithContext(context: Context) {
        synchronized(lock) {
            if (created) return
            MPVLib.create(context)
            created = true
            Log.i(TAG, "mpv created")
        }
    }

    fun attachSurface(surface: Surface) {
        // CON-8: log failures (was no try/catch — a flaky attach silently
        // aborted init with only a generic "Player init error" upstream).
        synchronized(lock) {
            if (created) try {
                MPVLib.attachSurface(surface); Log.i(TAG, "Surface attached")
            } catch (e: Exception) {
                Log.w(TAG, "attachSurface failed", e)
            }
        }
    }

    fun detachSurface() {
        // CON-8: log failures (was silent swallow, inconsistent with M3 rule).
        synchronized(lock) {
            if (created) try {
                MPVLib.detachSurface()
            } catch (e: Exception) {
                Log.w(TAG, "detachSurface failed", e)
            }
        }
    }

    actual fun initialize() {
        synchronized(lock) {
            if (!created || initialized) return
            MPVLib.init()
            initialized = true
            // If initAndroid() ran before initialize() (typical flow:
            // MobilePlayerScreen.LaunchedEffect fires before MpvRenderView's
            // surfaceCreated), the eof-reached observation was deferred. Do
            // it now that mpv is ready.
            if (observerAdded) {
                try { MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG) } catch (_: Exception) {}
            }
            Log.i(TAG, "mpv initialized")
        }
    }

    actual fun dispose() {
        synchronized(lock) {
            // removeObserver FIRST: after MPVLib.destroy(), the JNI event thread
            // may still deliver in-flight events to the observer, which would
            // call back into MPVLib on a destroyed mpv context.
            try { MPVLib.removeObserver(observer) } catch (_: Exception) {}
            if (initialized) { try { MPVLib.destroy() } catch (_: Exception) {}; initialized = false }
            created = false
            observerAdded = false
            eofReached = false
            fileLoadedBefore = false
        }
    }

    // M3: all post-dispose catch blocks now log at Log.w so "player doesn't
    // respond" is diagnosable from logcat. Previously every exception was
    // silently swallowed with zero signal.
    actual fun command(vararg args: String) {
        synchronized(lock) { if (initialized) try { MPVLib.command(args) } catch (e: Exception) { Log.w(TAG, "command failed", e) } }
    }

    actual fun setOption(key: String, value: String) {
        synchronized(lock) { if (created) try { MPVLib.setOptionString(key, value) } catch (e: Exception) { Log.w(TAG, "setOption($key) failed", e) } }
    }

    actual fun setOption(key: String, value: Long) {
        synchronized(lock) { if (created) try { MPVLib.setOptionString(key, value.toString()) } catch (e: Exception) { Log.w(TAG, "setOption($key=$value) failed", e) } }
    }

    actual fun setProperty(key: String, value: String) {
        synchronized(lock) { if (initialized) try { MPVLib.setPropertyString(key, value) } catch (e: Exception) { Log.w(TAG, "setProperty($key) failed", e) } }
    }

    actual fun setProperty(key: String, value: Long) {
        synchronized(lock) { if (initialized) try { MPVLib.setPropertyString(key, value.toString()) } catch (e: Exception) { Log.w(TAG, "setProperty($key=$value) failed", e) } }
    }

    actual fun getPropertyString(name: String): String? {
        synchronized(lock) { if (!initialized) return null; return try { MPVLib.getPropertyString(name) } catch (e: Exception) { Log.w(TAG, "getPropertyString($name) failed", e); null } }
    }

    actual fun getPropertyLong(name: String): Long {
        synchronized(lock) { if (!initialized) return 0L; return try { MPVLib.getPropertyInt(name)?.toLong() ?: 0L } catch (e: Exception) { Log.w(TAG, "getPropertyLong($name) failed", e); 0L } }
    }

    actual fun getPropertyDouble(name: String): Double {
        synchronized(lock) { if (!initialized) return 0.0; return try { MPVLib.getPropertyDouble(name) ?: 0.0 } catch (e: Exception) { Log.w(TAG, "getPropertyDouble($name) failed", e); 0.0 } }
    }

    actual fun observeProperty(name: String, format: MpvFormat) {
        synchronized(lock) { if (initialized) try { MPVLib.observeProperty(name, format.ordinal) } catch (e: Exception) { Log.w(TAG, "observeProperty($name) failed", e) } }
    }

    actual fun clearPropertyObservers() {
        // Android uses MPVLib.addObserver guarded by `observerAdded` (H9) —
        // property observers don't accumulate the way desktop's do. Just
        // reset the eof cache so a stale value from a previous session
        // doesn't leak into the next END_FILE inference.
        synchronized(lock) { eofReached = false }
    }

    fun isCreated(): Boolean = synchronized(lock) { created }

    companion object { private const val TAG = "MpvPlayer" }
}
