package it.telecast.core

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Logger minimale e indipendente da Android.
 * Su Android [platform] viene collegato a android.util.Log; le ultime righe restano
 * in memoria per la schermata "Log diagnostico" (utile per capire cosa fa la TV).
 */
object Log {
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var platform: ((level: Char, tag: String, msg: String, t: Throwable?) -> Unit)? = null

    fun d(tag: String, msg: String) = log('D', tag, msg, null)
    fun i(tag: String, msg: String) = log('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = log('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = log('E', tag, msg, t)

    private fun log(level: Char, tag: String, msg: String, t: Throwable?) {
        val p = platform
        if (p != null) p(level, tag, msg, t) else {
            println("$level/$tag: $msg" + (t?.let { " — $it" } ?: ""))
        }
        synchronized(this) {
            val extra = t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            lines.addLast("${fmt.format(Date())} $level/$tag: $msg$extra")
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
    }

    fun dump(): String = synchronized(this) { lines.joinToString("\n") }
}
