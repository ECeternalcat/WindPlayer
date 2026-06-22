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

    @JvmStatic fun eventProperty(property: String, value: Long) { synchronized(observers) { for (o in observers) o.eventProperty(property, value) } }
    @JvmStatic fun eventProperty(property: String, value: Boolean) { synchronized(observers) { for (o in observers) o.eventProperty(property, value) } }
    @JvmStatic fun eventProperty(property: String, value: Double) { synchronized(observers) { for (o in observers) o.eventProperty(property, value) } }
    @JvmStatic fun eventProperty(property: String, value: String) { synchronized(observers) { for (o in observers) o.eventProperty(property, value) } }
    @JvmStatic fun eventProperty(property: String) { synchronized(observers) { for (o in observers) o.eventProperty(property) } }
    @JvmStatic fun event(eventId: Int) { synchronized(observers) { for (o in observers) o.event(eventId) } }

    private val log_observers = mutableListOf<LogObserver>()
    @JvmStatic fun addLogObserver(o: LogObserver) { synchronized(log_observers) { log_observers.add(o) } }
    @JvmStatic fun removeLogObserver(o: LogObserver) { synchronized(log_observers) { log_observers.remove(o) } }
    @JvmStatic fun logMessage(prefix: String, level: Int, text: String) {
        synchronized(log_observers) { for (o in log_observers) o.logMessage(prefix, level, text) }
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
