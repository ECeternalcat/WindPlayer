package `is`.xyz.mpv

import android.content.Context
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("unused")
object MPVLib {
    init {
        System.loadLibrary("mpv")
        System.loadLibrary("player")
    }

    external fun create(appctx: Context)
    external fun init()
    external fun destroy()
    external fun attachSurface(surface: Surface)
    external fun detachSurface()

    external fun command(cmd: Array<out String>)
    external fun setOptionString(name: String, value: String): Int

    external fun getPropertyString(property: String): String?
    external fun setPropertyString(property: String, value: String)
    external fun getPropertyInt(property: String): Int?
    external fun setPropertyInt(property: String, value: Int)
    external fun getPropertyDouble(property: String): Double?
    external fun setPropertyDouble(property: String, value: Double)
    external fun getPropertyBoolean(property: String): Boolean?
    external fun setPropertyBoolean(property: String, value: Boolean)

    external fun observeProperty(property: String, format: Int)

    // CopyOnWriteArrayList: safe to iterate concurrently with add/remove.
    // This removes the per-event `toList()` snapshot allocation on the JNI
    // event thread (matters during seek bursts of property-change events)
    // and the dispatch can run directly on the live list.
    private val observers = CopyOnWriteArrayList<EventObserver>()

    @JvmStatic
    fun addObserver(o: EventObserver) { observers.addIfAbsent(o) }
    @JvmStatic
    fun removeObserver(o: EventObserver) { observers.remove(o) }

    // Dispatch iterates the COW list directly — no lock held, no allocation.
    // Holding a monitor while calling an observer would deadlock if the
    // observer re-enters MPVLib (e.g. MpvPlayer.inferEndFileReason ->
    // getPropertyString) while another thread holds an internal libplayer
    // lock and waits on `observers`.
    @JvmStatic fun eventProperty(property: String, value: Long) { for (o in observers) o.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String, value: Boolean) { for (o in observers) o.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String, value: Double) { for (o in observers) o.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String, value: String) { for (o in observers) o.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String) { for (o in observers) o.eventProperty(property) }
    @JvmStatic fun event(eventId: Int) { for (o in observers) o.event(eventId) }
    @JvmStatic fun endFile(reason: Int, error: Int, playlistEntryId: Long) {
        for (o in observers) o.endFile(reason, error, playlistEntryId)
    }

    private val log_observers = CopyOnWriteArrayList<LogObserver>()
    @JvmStatic fun addLogObserver(o: LogObserver) { log_observers.addIfAbsent(o) }
    @JvmStatic fun removeLogObserver(o: LogObserver) { log_observers.remove(o) }
    @JvmStatic fun logMessage(prefix: String, level: Int, text: String) {
        for (o in log_observers) o.logMessage(prefix, level, text)
    }

    interface EventObserver {
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: Long)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: String)
        fun eventProperty(property: String, value: Double)
        fun event(eventId: Int)
        fun endFile(reason: Int, error: Int, playlistEntryId: Long)
    }

    interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    object MpvEvent {
        const val MPV_EVENT_FILE_LOADED = 8
        const val MPV_EVENT_END_FILE = 7
        const val MPV_EVENT_IDLE = 11
    }

    object MpvFormat {
        const val MPV_FORMAT_STRING = 1
        const val MPV_FORMAT_FLAG = 3
        const val MPV_FORMAT_INT64 = 4
        const val MPV_FORMAT_DOUBLE = 5
    }
}
