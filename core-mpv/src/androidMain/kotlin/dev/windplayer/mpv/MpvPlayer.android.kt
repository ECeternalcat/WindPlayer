package dev.windplayer.mpv

import android.content.Context
import android.util.Log
import android.view.Surface
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

actual class MpvPlayer actual constructor() {

    private val lock = Any()
    private val _events = MutableSharedFlow<MpvEvent>(extraBufferCapacity = 64)
    actual val events: SharedFlow<MpvEvent> = _events
    private var created = false
    private var initialized = false
    private var fileLoadedBefore = false

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) { _events.tryEmit(MpvEvent.PropertyChange(property, null)) }
        override fun eventProperty(property: String, value: Long) { _events.tryEmit(MpvEvent.PropertyChange(property, value)) }
        override fun eventProperty(property: String, value: Boolean) { _events.tryEmit(MpvEvent.PropertyChange(property, value)) }
        override fun eventProperty(property: String, value: String) { _events.tryEmit(MpvEvent.PropertyChange(property, value)) }
        override fun eventProperty(property: String, value: Double) { _events.tryEmit(MpvEvent.PropertyChange(property, value)) }
        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> { fileLoadedBefore = true; _events.tryEmit(MpvEvent.FileLoaded()) }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> { val r = if (fileLoadedBefore) 0 else 4; fileLoadedBefore = false; _events.tryEmit(MpvEvent.EndFile(r)) }
                MPVLib.MpvEvent.MPV_EVENT_IDLE -> _events.tryEmit(MpvEvent.Idle())
            }
        }
    }

    fun initAndroid(context: Context) { synchronized(lock) { MPVLib.addObserver(observer) } }

    actual fun create() { synchronized(lock) { if (!created) created = true } }

    fun createWithContext(context: Context) {
        synchronized(lock) {
            if (created) return
            MPVLib.create(context)
            created = true
            Log.i(TAG, "mpv created")
        }
    }

    fun attachSurface(surface: Surface) {
        synchronized(lock) { if (created) { MPVLib.attachSurface(surface); Log.i(TAG, "Surface attached") } }
    }

    fun detachSurface() {
        synchronized(lock) { if (created) try { MPVLib.detachSurface() } catch (_: Exception) {} }
    }

    actual fun initialize() {
        synchronized(lock) {
            if (!created || initialized) return
            MPVLib.init()
            initialized = true
            Log.i(TAG, "mpv initialized")
        }
    }

    actual fun dispose() {
        synchronized(lock) {
            if (initialized) { try { MPVLib.destroy() } catch (_: Exception) {}; initialized = false }
            created = false
            try { MPVLib.removeObserver(observer) } catch (_: Exception) {}
        }
    }

    actual fun command(vararg args: String) {
        synchronized(lock) { if (initialized) try { MPVLib.command(args) } catch (_: Exception) {} }
    }

    actual fun setOption(key: String, value: String) {
        synchronized(lock) { if (created) try { MPVLib.setOptionString(key, value) } catch (_: Exception) {} }
    }

    actual fun setOption(key: String, value: Long) {
        synchronized(lock) { if (created) try { MPVLib.setOptionString(key, value.toString()) } catch (_: Exception) {} }
    }

    actual fun setProperty(key: String, value: String) {
        synchronized(lock) { if (initialized) try { MPVLib.setPropertyString(key, value) } catch (_: Exception) {} }
    }

    actual fun setProperty(key: String, value: Long) {
        synchronized(lock) { if (initialized) try { MPVLib.setPropertyString(key, value.toString()) } catch (_: Exception) {} }
    }

    actual fun getPropertyString(name: String): String? {
        synchronized(lock) { if (!initialized) return null; return try { MPVLib.getPropertyString(name) } catch (_: Exception) { null } }
    }

    actual fun getPropertyLong(name: String): Long {
        synchronized(lock) { if (!initialized) return 0L; return try { MPVLib.getPropertyInt(name)?.toLong() ?: 0L } catch (_: Exception) { 0L } }
    }

    actual fun getPropertyDouble(name: String): Double {
        synchronized(lock) { if (!initialized) return 0.0; return try { MPVLib.getPropertyDouble(name) ?: 0.0 } catch (_: Exception) { 0.0 } }
    }

    actual fun observeProperty(name: String, format: MpvFormat) {
        synchronized(lock) { if (initialized) try { MPVLib.observeProperty(name, format.ordinal) } catch (_: Exception) {} }
    }

    fun isCreated(): Boolean = synchronized(lock) { created }

    companion object { private const val TAG = "MpvPlayer" }
}
