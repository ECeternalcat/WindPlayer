package `is`.xyz.mpv

import android.content.Context
import android.view.Surface

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

    private val observers = mutableListOf<EventObserver>()

    @JvmStatic
    fun addObserver(o: EventObserver) { synchronized(observers) { observers.add(o) } }
    @JvmStatic
    fun removeObserver(o: EventObserver) { synchronized(observers) { observers.remove(o) } }

    // Snapshot under the lock, dispatch outside. Holding the monitor while
    // calling an observer would deadlock if the observer re-enters MPVLib
    // (e.g. MpvPlayer.inferEndFileReason -> getPropertyString) while another
    // thread holds an internal libplayer lock and waits on `observers`.
    @JvmStatic fun eventProperty(property: String, value: Long) { for (o in snapshot()) o.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String, value: Boolean) { for (o in snapshot()) o.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String, value: Double) { for (o in snapshot()) o.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String, value: String) { for (o in snapshot()) o.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String) { for (o in snapshot()) o.eventProperty(property) }
    @JvmStatic fun event(eventId: Int) { for (o in snapshot()) o.event(eventId) }

    private fun snapshot(): List<EventObserver> = synchronized(observers) { observers.toList() }

    private val log_observers = mutableListOf<LogObserver>()
    @JvmStatic fun addLogObserver(o: LogObserver) { synchronized(log_observers) { log_observers.add(o) } }
    @JvmStatic fun removeLogObserver(o: LogObserver) { synchronized(log_observers) { log_observers.remove(o) } }
    @JvmStatic fun logMessage(prefix: String, level: Int, text: String) {
        val snap = synchronized(log_observers) { log_observers.toList() }
        for (o in snap) o.logMessage(prefix, level, text)
    }

    interface EventObserver {
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: Long)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: String)
        fun eventProperty(property: String, value: Double)
        fun event(eventId: Int)
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
