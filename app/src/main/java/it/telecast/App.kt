package it.telecast

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import it.telecast.core.CastController
import it.telecast.core.CastStatus
import it.telecast.core.Log
import it.telecast.core.Protocol
import it.telecast.core.Renderer
import it.telecast.core.StreamServer
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class App : Application() {
    val server = StreamServer(8765)
    val cast: CastController by lazy { CastController(server) }
    val bg: ExecutorService = Executors.newFixedThreadPool(4)
    val main = Handler(Looper.getMainLooper())
    val detected = DetectedStore()
    private val statusListeners = CopyOnWriteArrayList<(CastStatus) -> Unit>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.platform = { level, tag, msg, t ->
            val tg = "TeleCast/$tag"
            when (level) {
                'E' -> android.util.Log.e(tg, msg, t)
                'W' -> android.util.Log.w(tg, msg, t)
                'I' -> android.util.Log.i(tg, msg)
                else -> android.util.Log.d(tg, msg)
            }
        }
        cast.listener = { st -> main.post { for (l in statusListeners) l(st) } }
        Log.i("App", "avvio su Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        AdBlock.init(this)
    }

    /** Il listener riceve subito lo stato attuale e poi ogni cambiamento, sempre sul thread principale. */
    fun addStatusListener(l: (CastStatus) -> Unit) {
        statusListeners += l
        l(cast.status)
    }

    fun removeStatusListener(l: (CastStatus) -> Unit) {
        statusListeners -= l
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

/** Una TV già usata (si ricordano le ultime, la più recente per prima). */
data class SavedTv(
    val udn: String,
    val name: String,
    val host: String,
    val protocol: Protocol = Protocol.DLNA,
    val port: Int = 0,
    val model: String = "",
) {
    val kindLabel: String get() = if (protocol == Protocol.CAST) "Chromecast" else "DLNA"
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("telecast", Context.MODE_PRIVATE)

    fun saveTv(c: Context, r: Renderer) {
        val list = tvs(c).filter { it.udn != r.udn && !(it.host == r.host && it.protocol == r.protocol) }.toMutableList()
        list.add(0, SavedTv(r.udn, r.name, r.host, r.protocol, r.port, r.model))
        store(c, list.take(8))
    }

    fun tvs(c: Context): List<SavedTv> {
        val s = sp(c)
        val raw = s.getString("tvs", null)
        if (raw == null) {
            // versione precedente: una sola TV salvata
            val udn = s.getString("tv_udn", null) ?: return emptyList()
            return listOf(SavedTv(udn, s.getString("tv_name", null) ?: "TV", s.getString("tv_host", null) ?: ""))
        }
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SavedTv(
                    udn = o.optString("udn"),
                    name = o.optString("name", "TV"),
                    host = o.optString("host"),
                    protocol = if (o.optString("protocol") == "CAST") Protocol.CAST else Protocol.DLNA,
                    port = o.optInt("port", 0),
                    model = o.optString("model"),
                )
            }.filter { it.udn.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun lastTv(c: Context): SavedTv? = tvs(c).firstOrNull()

    fun forgetTv(c: Context, udn: String) = store(c, tvs(c).filter { it.udn != udn })

    private fun store(c: Context, list: List<SavedTv>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject()
                    .put("udn", t.udn).put("name", t.name).put("host", t.host)
                    .put("protocol", t.protocol.name).put("port", t.port).put("model", t.model),
            )
        }
        sp(c).edit().putString("tvs", arr.toString()).remove("tv_udn").remove("tv_name").remove("tv_host").apply()
    }

    fun askedNotifications(c: Context): Boolean = sp(c).getBoolean("asked_notif", false)

    fun setAskedNotifications(c: Context) {
        sp(c).edit().putBoolean("asked_notif", true).apply()
    }

    /** Siti su cui l'utente ha spento il blocco pubblicità. */
    fun shieldOffSites(c: Context): Set<String> = HashSet(sp(c).getStringSet("shield_off", emptySet()) ?: emptySet())

    fun setShieldOff(c: Context, site: String, off: Boolean) {
        val set = shieldOffSites(c).toMutableSet()
        if (off) set += site else set -= site
        sp(c).edit().putStringSet("shield_off", set).apply()
    }
}
