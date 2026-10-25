package it.telecast

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.text.InputType
import android.text.TextUtils
import android.text.method.DigitsKeyListener
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import it.telecast.core.CastProbe
import it.telecast.core.Log
import it.telecast.core.Mdns
import it.telecast.core.Net
import it.telecast.core.Protocol
import it.telecast.core.Renderer
import it.telecast.core.Ssdp
import it.telecast.core.Upnp
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Finestra di scelta della TV. Mostra subito le TV già usate, poi quelle trovate sulla rete:
 * TV DLNA (es. LG) con SSDP e TV Chromecast/Google TV (es. ok.) con mDNS.
 * Se il multicast è bloccato prova indirizzo per indirizzo; in più si può inserire l'IP a mano.
 */
class DevicePicker(
    private val act: Activity,
    private val title: String = "Scegli la TV",
    private val excludeUdn: String? = null,
    private val onPick: (Renderer) -> Unit,
) {
    private val live = CopyOnWriteArrayList<Renderer>()
    private val saved = Prefs.tvs(act).filter { it.udn != excludeUdn }
    private lateinit var listBox: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var spinner: ProgressBar
    private var dialog: AlertDialog? = null
    @Volatile private var cancelled = false
    @Volatile private var searching = false
    private var lock: WifiManager.MulticastLock? = null
    private var nsd: NsdCast? = null

    fun show() {
        val ctx = act
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(20), ctx.dp(4))
        }
        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        spinner = ProgressBar(ctx).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(C.ACCENT)
        }
        statusRow.addView(spinner, LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)))
        statusText = ctx.label("Cerco le TV sulla rete Wi-Fi…", 14f, C.DIM).apply { setPadding(ctx.dp(12), 0, 0, 0) }
        statusRow.addView(statusText, lp(0, WRAP, 1f))
        root.addView(statusRow, lp(MATCH, WRAP, b = ctx.dp(12)))
        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(ctx).apply { addView(listBox) }, lp(MATCH, WRAP))
        root.addView(
            ctx.label("Le TV devono essere accese e sulla stessa rete Wi-Fi del telefono.", 12f, C.DIM),
            lp(MATCH, WRAP, t = ctx.dp(8)),
        )
        val d = ctx.dialog()
            .setTitle(title)
            .setView(root)
            .setNegativeButton("Chiudi", null)
            .setNeutralButton("Inserisci IP", null)
            .setPositiveButton("Cerca di nuovo", null)
            .create()
        d.setOnDismissListener {
            cancelled = true
            nsd?.stop()
            releaseLock(force = true)
        }
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (!searching) search(null) }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { askIp() }
        }
        dialog = d
        d.show()
        render()
        nsd = NsdCast(ctx) { addLive(it) }.also { it.start() }
        search(null)
    }

    private fun post(r: () -> Unit) {
        App.instance.main.post { if (dialog?.isShowing == true) r() }
    }

    /** Aggiunge una TV trovata; se la stessa TV risponde sia DLNA sia Chromecast tengo Chromecast. */
    private fun addLive(r: Renderer) {
        if (r.udn == excludeUdn) return
        synchronized(live) {
            if (live.any { it.udn == r.udn }) return
            val twin = live.firstOrNull { it.host == r.host && it.protocol != r.protocol }
            if (twin != null) {
                if (r.isCast) live.remove(twin) else return
            }
            live += r
        }
        Log.i("Ricerca", "trovata: ${r.name} (${r.kindLabel}, ${r.manufacturer} ${r.model}) su ${r.host}")
        post { render() }
    }

    private fun search(manualHost: String?) {
        searching = true
        cancelled = false
        render()
        spinner.visibility = View.VISIBLE
        statusText.text = if (manualHost != null) "Contatto $manualHost…" else "Cerco le TV sulla rete Wi-Fi…"
        acquireLock()
        thread {
            val local = Net.lanIPv4()
            if (local == null) {
                finish("Il telefono non risulta collegato a una rete Wi-Fi.")
                return@thread
            }
            val unicast = manualHost?.let { listOf(it) } ?: emptyList()
            val done = CountDownLatch(2)
            // TV DLNA (LG e simili)
            thread {
                try {
                    val handle: (String) -> Unit = { loc ->
                        try { Upnp.fetchRenderer(loc)?.let { addLive(it) } } catch (e: Exception) {
                            Log.d("Ricerca", "descrizione non leggibile ($loc): ${e.message}")
                        }
                    }
                    Ssdp.search(local, 3500, unicast, multicast = manualHost == null, cancelled = { cancelled }, onLocation = handle)
                    if (manualHost == null && live.isEmpty() && !cancelled) {
                        post { statusText.text = "Nessuna risposta: provo indirizzo per indirizzo…" }
                        Ssdp.search(local, 3500, Net.subnetHosts(local), multicast = false, cancelled = { cancelled }, onLocation = handle)
                    }
                } catch (e: Exception) {
                    Log.w("Ricerca", "ricerca DLNA", e)
                } finally {
                    done.countDown()
                }
            }
            // TV Chromecast / Google TV (ok. e simili)
            thread {
                try {
                    Mdns.searchCast(local, 3500, unicast, multicast = manualHost == null, cancelled = { cancelled }) { addLive(it) }
                    if (manualHost == null && live.isEmpty() && !cancelled) {
                        Mdns.searchCast(local, 3500, Net.subnetHosts(local), multicast = false, cancelled = { cancelled }) { addLive(it) }
                    }
                } catch (e: Exception) {
                    Log.w("Ricerca", "ricerca Chromecast", e)
                } finally {
                    done.countDown()
                }
            }
            done.await(20, TimeUnit.SECONDS)
            if (manualHost != null && live.none { it.host == manualHost } && !cancelled) {
                CastProbe.probe(manualHost)?.let { addLive(it) }
            }
            finish(
                when {
                    live.isNotEmpty() -> "Tocca la TV su cui guardare."
                    manualHost != null -> "Nessuna TV risponde all'indirizzo $manualHost."
                    else -> "Nessuna TV trovata. Controlla che siano accese e sulla stessa Wi-Fi, poi riprova."
                },
            )
        }
    }

    private fun thread(block: () -> Unit) {
        Thread(block, "tc-ricerca").apply { isDaemon = true }.start()
    }

    private fun finish(msg: String) {
        searching = false
        App.instance.main.post {
            releaseLock()
            if (dialog?.isShowing == true) {
                spinner.visibility = View.GONE
                statusText.text = msg
            }
        }
    }

    /** Righe: prima le TV già usate (accese o no), poi le altre trovate. */
    private fun render() {
        listBox.removeAllViews()
        val liveNow = live.toList()
        val used = HashSet<String>()
        var first = excludeUdn == null
        for (s in saved) {
            val match = liveNow.firstOrNull { it.udn == s.udn } ?: liveNow.firstOrNull { it.host == s.host && it.protocol == s.protocol }
            if (match != null) used += match.udn
            listBox.addView(row(match, s, lastUsed = first), lp(MATCH, WRAP, b = act.dp(8)))
            first = false
        }
        val others = liveNow.filter { it.udn !in used }.sortedWith(compareByDescending<Renderer> { it.isCast }.thenBy { it.name })
        for (r in others) listBox.addView(row(r, null, lastUsed = false), lp(MATCH, WRAP, b = act.dp(8)))
    }

    private fun row(r: Renderer?, s: SavedTv?, lastUsed: Boolean): View {
        val ctx = act
        val online = r != null
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
            background = rippleRect(C.SURFACE2, ctx.dpf(14f), if (lastUsed && online) ctx.dp(1) else 0, C.ACCENT)
            isClickable = true
            setOnClickListener {
                if (r != null) pick(r) else if (s != null) reach(s)
            }
        }
        val icon = ImageView(ctx).apply {
            setImageResource(if ((r?.isCast ?: (s?.protocol == Protocol.CAST))) R.drawable.ic_cast else R.drawable.ic_tv)
            imageTintList = ColorStateList.valueOf(if (online) C.ACCENT else C.DIM)
        }
        row.addView(icon, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)))
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(14), 0, ctx.dp(8), 0)
        }
        texts.addView(
            ctx.label(r?.name ?: s?.name ?: "TV", 16f, if (online) C.TEXT else C.DIM, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val kind = r?.kindLabel ?: s?.kindLabel ?: ""
        val model = (r?.model ?: s?.model ?: "").ifBlank { r?.manufacturer ?: "" }
        val host = r?.host ?: s?.host ?: ""
        texts.addView(
            ctx.label(listOf(kind, model, host).filter { it.isNotBlank() }.joinToString(" · "), 12f, C.DIM).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        row.addView(texts, lp(0, WRAP, 1f))
        val badge = when {
            !online -> "non trovata"
            lastUsed -> "ultima usata"
            else -> null
        }
        if (badge != null) {
            row.addView(
                ctx.label(badge, 11f, if (online) C.ACCENT else C.DIM, bold = true).apply {
                    setPadding(ctx.dp(8), ctx.dp(3), ctx.dp(8), ctx.dp(3))
                    background = roundRect(C.SURFACE, ctx.dpf(10f))
                },
            )
        }
        return row
    }

    private fun pick(r: Renderer) {
        Prefs.saveTv(act, r)
        dialog?.dismiss()
        onPick(r)
    }

    /** TV ricordata ma non ancora vista in questa ricerca: provo a raggiungerla direttamente. */
    private fun reach(s: SavedTv) {
        spinner.visibility = View.VISIBLE
        statusText.text = "Cerco ${s.name}…"
        acquireLock()
        thread {
            val r = Discovery.findSaved(act, s)
            App.instance.main.post {
                if (dialog?.isShowing != true) return@post
                spinner.visibility = if (searching) View.VISIBLE else View.GONE
                if (r != null) {
                    pick(r)
                } else {
                    statusText.text = "${s.name} non risponde: è accesa e sulla stessa Wi-Fi?"
                }
            }
        }
    }

    private fun askIp() {
        val ctx = act
        val input = EditText(ctx).apply {
            hint = "es. 192.168.1.50"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            keyListener = DigitsKeyListener.getInstance("0123456789.")
            setText(saved.firstOrNull()?.host ?: "")
            setTextColor(C.TEXT)
            setHintTextColor(C.DIM)
        }
        val box = FrameLayout(ctx).apply {
            setPadding(ctx.dp(22), ctx.dp(8), ctx.dp(22), 0)
            addView(input)
        }
        ctx.dialog()
            .setTitle("Indirizzo IP della TV")
            .setMessage("Lo trovi sulla TV, nelle impostazioni di rete (connessione Wi-Fi o cavo).")
            .setView(box)
            .setPositiveButton("Cerca") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) search(ip) else ctx.toast("Indirizzo non valido")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun acquireLock() {
        if (lock != null) return
        lock = try {
            val wm = act.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.createMulticastLock("TeleCast:ricerca").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w("Ricerca", "multicast lock non disponibile: ${e.message}")
            null
        }
    }

    private fun releaseLock(force: Boolean = false) {
        if (searching && !force) return
        try { lock?.release() } catch (_: Exception) { }
        lock = null
    }
}

/** Ricerca dei Chromecast anche con il servizio di sistema di Android (NSD), in aggiunta alla nostra. */
private class NsdCast(ctx: Context, private val onFound: (Renderer) -> Unit) {
    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var stopped = false

    fun start() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.d("NSD", "ricerca di sistema non avviata ($errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        try {
            nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, l)
            listener = l
        } catch (e: Exception) {
            Log.d("NSD", "ricerca di sistema non disponibile: ${e.message}")
        }
    }

    fun stop() {
        stopped = true
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) { } }
        listener = null
    }

    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        if (stopped) return
        queue.add(info)
        if (!resolving) next()
    }

    @Suppress("DEPRECATION")
    @Synchronized
    private fun next() {
        val info = queue.poll()
        if (info == null || stopped) {
            resolving = false
            return
        }
        resolving = true
        try {
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) = next()
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        toRenderer(si)?.let(onFound)
                        next()
                    }
                },
            )
        } catch (_: Exception) {
            resolving = false
        }
    }

    @Suppress("DEPRECATION")
    private fun toRenderer(si: NsdServiceInfo): Renderer? {
        val host = si.host?.hostAddress ?: return null
        if (host.contains(':')) return null
        fun attr(k: String): String? = si.attributes[k]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
        return Renderer.cast(attr("id") ?: si.serviceName, attr("fn") ?: si.serviceName, attr("md") ?: "", host, if (si.port > 0) si.port else 8009)
    }
}

/** Ricollegamento a una TV già usata: per DLNA serve ritrovarla (la porta cambia), per Chromecast basta l'IP. */
object Discovery {
    fun findSaved(ctx: Context, saved: SavedTv): Renderer? {
        val local = Net.lanIPv4() ?: return null
        var result: Renderer? = null
        val lock = try {
            (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createMulticastLock("TeleCast:ritrova").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (_: Exception) {
            null
        }
        try {
            val hosts = if (saved.host.isNotBlank()) listOf(saved.host) else emptyList()
            if (saved.protocol == Protocol.CAST) {
                Mdns.searchCast(local, 2500, hosts, multicast = true, cancelled = { result != null }) { r ->
                    if (result == null && (r.udn == saved.udn || r.host == saved.host)) result = r
                }
                if (result == null && saved.host.isNotBlank()) {
                    result = CastProbe.probe(saved.host, if (saved.port > 0) saved.port else 8009)
                        ?.copy(udn = saved.udn, name = saved.name, model = saved.model)
                }
            } else {
                Ssdp.search(local, 2500, hosts, multicast = true, cancelled = { result != null }) { loc ->
                    if (result == null) {
                        val r = try { Upnp.fetchRenderer(loc) } catch (_: Exception) { null }
                        if (r != null && (r.udn == saved.udn || (r.host == saved.host && r.name == saved.name))) result = r
                    }
                }
            }
        } finally {
            try { lock?.release() } catch (_: Exception) { }
        }
        return result
    }
}
