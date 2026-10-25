package it.telecast.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.Locale

/**
 * Insieme compatto di domini da bloccare: di ogni dominio si tiene solo un hash a 64 bit,
 * in ordine, e si cerca per bisezione (200.000 domini = 1,6 MB).
 * Ogni voce vale anche per i sottodomini: "ads.com" blocca "x.ads.com", mai "com" da solo.
 */
class DomainSet private constructor(private val hashes: LongArray) {
    val size: Int get() = hashes.size

    fun matches(host: String): Boolean {
        if (hashes.isEmpty() || host.isEmpty()) return false
        var h = host
        while (true) {
            val dot = h.indexOf('.')
            if (dot <= 0) return false
            if (Arrays.binarySearch(hashes, hash(h)) >= 0) return true
            h = h.substring(dot + 1)
        }
    }

    fun writeTo(out: OutputStream, updatedAt: Long) {
        val buf = ByteBuffer.allocate(HEADER + hashes.size * 8)
        buf.putInt(MAGIC).putInt(FORMAT).putLong(updatedAt).putInt(hashes.size)
        buf.asLongBuffer().put(hashes)
        out.write(buf.array())
    }

    /** Raccoglie i domini (anche da interi elenchi scaricati) e poi costruisce l'insieme. */
    class Builder {
        private var arr = LongArray(1 shl 15)
        var count = 0
            private set

        fun add(domain: String) {
            if (count == arr.size) arr = arr.copyOf(arr.size * 2)
            arr[count++] = hash(domain)
        }

        /** Aggiunge le righe valide di un elenco; restituisce quante erano. */
        fun addLines(input: InputStream): Int {
            var n = 0
            input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                parseLine(line)?.let {
                    add(it)
                    n++
                }
            }
            return n
        }

        /** Annulla le aggiunte fatte dopo [mark] (un download interrotto a metà). */
        fun truncate(mark: Int) {
            if (mark in 0..count) count = mark
        }

        fun build(): DomainSet {
            val a = arr.copyOf(count)
            a.sort()
            var m = 0
            for (i in a.indices) if (m == 0 || a[i] != a[m - 1]) a[m++] = a[i]
            return DomainSet(a.copyOf(m))
        }
    }

    class Stored(val set: DomainSet, val updatedAt: Long)

    companion object {
        private const val MAGIC = 0x54434c31 // "TCL1"
        private const val FORMAT = 1
        private const val HEADER = 20
        private val SINK_IPS = setOf("0.0.0.0", "127.0.0.1", "::", "::1")
        private val LOCAL_NAMES = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback")

        val EMPTY = DomainSet(LongArray(0))

        /** FNV-1a a 64 bit: i domini sono ASCII. Se cambia, va cambiato anche FORMAT. */
        fun hash(s: String): Long {
            var h = 0xcbf29ce484222325uL.toLong()
            for (i in 0 until s.length) {
                h = h xor (s[i].code.toLong() and 0xff)
                h *= 0x100000001b3L
            }
            return h
        }

        fun of(vararg domains: String): DomainSet {
            val b = Builder()
            for (d in domains) parseLine(d)?.let { b.add(it) }
            return b.build()
        }

        /**
         * Una riga di un elenco → il dominio, oppure null (commenti, righe non valide).
         * Formati: "dominio", "*.dominio", "0.0.0.0 dominio" (hosts), "||dominio^" (AdBlock).
         */
        fun parseLine(line: String): String? {
            var s = line.trim()
            if (s.isEmpty()) return null
            val c0 = s[0]
            if (c0 == '#' || c0 == '!' || c0 == '[') return null
            val cut = s.indexOf('#')
            if (cut > 0) s = s.substring(0, cut).trim()
            val ws = s.indexOfFirst { it == ' ' || it == '\t' }
            if (ws > 0) {
                if (s.substring(0, ws) !in SINK_IPS) return null
                s = s.substring(ws + 1).trim()
                val ws2 = s.indexOfFirst { it == ' ' || it == '\t' }
                if (ws2 > 0) s = s.substring(0, ws2)
            }
            if (s.startsWith("||")) {
                if (!s.endsWith("^")) return null
                s = s.substring(2, s.length - 1)
            }
            if (s.startsWith("*.")) s = s.substring(2) else if (s.startsWith(".")) s = s.substring(1)
            s = s.lowercase(Locale.ROOT).trimEnd('.')
            if (s in LOCAL_NAMES) return null
            return if (validHost(s)) s else null
        }

        private fun validHost(s: String): Boolean {
            if (s.length < 3 || s.length > 253 || '.' !in s || s[0] == '.' || s[0] == '-' || s.contains("..")) return false
            for (ch in s) if (!(ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_')) return false
            return true
        }

        fun readFrom(bytes: ByteArray): Stored? {
            if (bytes.size < HEADER) return null
            val b = ByteBuffer.wrap(bytes)
            if (b.getInt() != MAGIC || b.getInt() != FORMAT) return null
            val at = b.getLong()
            val n = b.getInt()
            if (n < 0 || bytes.size.toLong() != HEADER + n.toLong() * 8) return null
            val arr = LongArray(n)
            b.asLongBuffer().get(arr)
            for (i in 1 until n) if (arr[i - 1] >= arr[i]) return null
            return Stored(DomainSet(arr), at)
        }
    }
}

/**
 * Blocco pubblicità: un piccolo elenco interno sempre attivo più gli elenchi di HaGeZi
 * (Multi PRO + Pop-Up Ads, circa 210.000 domini) scaricati e rinnovati ogni pochi giorni.
 */
class AdBlocker(
    private val file: File,
    private val sources: List<List<String>> = SOURCES,
    private val fetch: (String) -> InputStream = ::download,
    private val minEntries: Int = 1000,
) {
    private val builtIn = DomainSet.of(*BUILT_IN)
    private val lock = Any()

    @Volatile var list: DomainSet = DomainSet.EMPTY
        private set

    /** Quando è stato scaricato l'elenco completo (ms), 0 se mai. */
    @Volatile var updatedAt: Long = 0L
        private set

    @Volatile var updating: Boolean = false
        private set

    /** Domini nell'elenco in uso (quello completo, se c'è). */
    val size: Int get() = maxOf(list.size, builtIn.size)

    val hasFullList: Boolean get() = list.size > 0

    fun isBlocked(host: String): Boolean {
        if (host.isEmpty()) return false
        val h = if (host.any { it in 'A'..'Z' }) host.lowercase(Locale.ROOT) else host
        return builtIn.matches(h) || list.matches(h)
    }

    /** All'avvio: legge l'elenco salvato l'ultima volta. */
    fun load(): Boolean {
        val bytes = try {
            if (!file.exists()) return false
            file.readBytes()
        } catch (e: Exception) {
            Log.w(TAG, "elenco salvato non leggibile: ${e.message}")
            return false
        }
        val st = DomainSet.readFrom(bytes)
        if (st == null) {
            Log.w(TAG, "elenco salvato danneggiato: lo riscarico")
            return false
        }
        list = st.set
        updatedAt = st.updatedAt
        Log.i(TAG, "elenco pubblicità: ${st.set.size} domini")
        return true
    }

    fun needsUpdate(now: Long = System.currentTimeMillis()): Boolean =
        list.size == 0 || now - updatedAt > MAX_AGE_MS || now < updatedAt

    /**
     * Scarica tutte le fonti e sostituisce l'elenco. Se una fonte non risponde
     * si tiene quello vecchio (o, la prima volta, quello che si è riusciti a scaricare).
     */
    fun update(now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        updating = true
        try {
            val b = DomainSet.Builder()
            var complete = true
            for (alternatives in sources) {
                var ok = false
                for (url in alternatives) {
                    val mark = b.count
                    try {
                        val n = fetch(url).use { b.addLines(it) }
                        if (n < minEntries) throw IOException("solo $n voci")
                        Log.i(TAG, "elenco ${url.substringAfterLast('/')} da ${Sites.hostOf(url)}: $n domini")
                        ok = true
                        break
                    } catch (e: Exception) {
                        b.truncate(mark)
                        Log.w(TAG, "elenco non scaricato da ${Sites.hostOf(url)}: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
                if (!ok) complete = false
            }
            if (!complete && list.size > 0) return false
            val set = b.build()
            if (set.size < minEntries) return false
            if (complete) {
                try {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.path + ".tmp")
                    tmp.outputStream().use { set.writeTo(it, now) }
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        tmp.renameTo(file)
                    }
                    updatedAt = now
                } catch (e: Exception) {
                    Log.w(TAG, "elenco non salvato: ${e.message}")
                }
            }
            list = set
            Log.i(TAG, "blocco pubblicità aggiornato: ${set.size} domini" + if (complete) "" else " (parziale)")
            complete
        } finally {
            updating = false
        }
    }

    companion object {
        private const val TAG = "AdBlock"
        const val MAX_AGE_MS = 4L * 24 * 3600 * 1000

        private const val GH = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/"
        private const val CDN = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/wildcard/"
        val SOURCES = listOf(
            listOf(GH + "pro-onlydomains.txt", CDN + "pro-onlydomains.txt"),
            listOf(GH + "popupads-onlydomains.txt", CDN + "popupads-onlydomains.txt"),
        )

        /** Attivi anche prima del primo download: i circuiti pubblicitari e di pop-up più diffusi. */
        val BUILT_IN = arrayOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "monetag.com", "admaven.com", "richpush.co", "amazon-adsystem.com",
            "33across.com", "a-ads.com", "acint.net", "aclickads.com", "ad-maven.com", "ad.plus", "adblade.com",
            "adcash.com", "adform.net", "admixer.net", "adnxs.com", "adsco.re", "adserverplus.com", "adsession.com",
            "adskeeper.co.uk", "adskeeper.com", "adspirit.de", "adspyglass.com", "adsrvr.org", "adsterra.com",
            "adtelligent.com", "adtng.com", "adxpansion.com", "bebi.com", "bidvertiser.com", "bitmedia.io",
            "clickadu.com", "clickaine.com", "clicksgear.com", "cointraffic.io", "criteo.com", "criteo.net",
            "displayvertising.com", "dolohen.com", "effectivegatecpm.com", "effectiveratecpm.com",
            "ero-advertising.com", "evadav.com", "exdynsrv.com", "exoclick.com", "exosrv.com", "galaksion.com",
            "highperformancecpm.com", "highperformancedisplayformat.com", "highperformanceformat.com",
            "hilltopads.com", "hilltopads.net", "infolinks.com", "juicyads.com", "magsrv.com", "media.net",
            "mgid.com", "moatads.com", "mobiright.com", "notix.io", "onclickads.net", "onclickalgo.com",
            "onclickperformance.com", "onclkds.com", "openx.net", "outbrain.com", "plugrush.com", "pop.dojo.cc",
            "popads.net", "popadscdn.net", "popcash.net", "popmyads.com", "poptm.com", "popunder.net",
            "profitablecpmgate.com", "profitabledisplaynetwork.com", "profitablegatecpm.com", "propellerads.com",
            "propellerclick.com", "propellerpops.com", "propu.sh", "pubmatic.com", "pushnest.com", "realsrv.com",
            "revcontent.com", "revenuehits.com", "richads.com", "seedtag.com", "sharethrough.com",
            "smartadserver.com", "taboola.com", "tapioni.com", "teads.tv", "trafficjunky.com", "trafficjunky.net",
            "trafficstars.com", "tsyndicate.com", "undertone.com", "vidoomy.com", "yieldmo.com", "yllix.com",
            "zedo.com", "zeropark.com",
        )

        fun download(url: String): InputStream {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "TeleCast")
            val code = c.responseCode
            if (code != 200) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            return c.inputStream
        }
    }
}

/** Il "sito" di un indirizzo, per capire se due pagine sono dello stesso sito. */
object Sites {
    private val SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go", "gob", "mil", "nic", "ltd", "plc", "sch")
    private val HOSTING = setOf(
        "github.io", "gitlab.io", "blogspot.com", "altervista.org", "netlify.app", "vercel.app", "pages.dev",
        "web.app", "firebaseapp.com", "herokuapp.com", "appspot.com", "wordpress.com", "wixsite.com",
        "weebly.com", "glitch.me", "onrender.com", "workers.dev", "azurewebsites.net", "cloudfront.net",
        "amazonaws.com", "b-cdn.net", "r2.dev",
    )

    /** "player.sito.co.uk" → "sito.co.uk", "www.rai.it" → "rai.it", "tizio.github.io" resta com'è. */
    fun of(host: String?): String {
        val h = (host ?: "").lowercase(Locale.ROOT).trimEnd('.')
        if (h.isEmpty() || ':' in h) return h
        if (h.all { it.isDigit() || it == '.' }) return h
        val p = h.split('.')
        if (p.size <= 2) return h
        val keep = when {
            p[p.size - 2] + "." + p.last() in HOSTING -> 3
            p.last().length == 2 && p[p.size - 2] in SECOND_LEVEL -> 3
            else -> 2
        }
        return p.takeLast(keep).joinToString(".")
    }

    fun ofUrl(url: String?): String = of(hostOf(url))

    fun hostOf(url: String?): String {
        if (url == null) return ""
        val i = url.indexOf("://")
        if (i < 0) return ""
        var s = url.substring(i + 3)
        val end = s.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        if (end >= 0) s = s.substring(0, end)
        s = s.substringAfterLast('@')
        if (s.startsWith("[")) return s.substringBefore(']').removePrefix("[").lowercase(Locale.ROOT)
        return s.substringBefore(':').lowercase(Locale.ROOT)
    }
}

/**
 * Decide se una navigazione della pagina principale l'ha voluta l'utente oppure è
 * un reindirizzamento pubblicitario (tocchi Play e ti ritrovi su un sito spam).
 */
object NavShield {
    enum class Verdict { ALLOW, AD, UNWANTED }

    enum class Popup { OPEN_HERE, SAME_PAGE, AD, UNWANTED }

    /** Ultimo link toccato davvero dall'utente, registrato dallo script nella pagina. */
    class Tap(val url: String, val at: Long, val overlay: Boolean)

    const val TAP_WINDOW_MS = 5_000L
    const val REDIRECT_WINDOW_MS = 15_000L

    /** Siti che rimandano altrove per mestiere: motori di ricerca e link dei social. */
    private val REDIRECTORS = setOf(
        "bing.com", "duckduckgo.com", "yahoo.com", "ecosia.org", "startpage.com", "qwant.com", "t.co",
        "facebook.com", "instagram.com", "reddit.com", "x.com", "twitter.com", "linkedin.com", "youtube.com",
        "t.me", "telegram.me", "whatsapp.com", "pinterest.com", "tiktok.com",
    )

    fun isRedirector(site: String): Boolean = site.startsWith("google.") || site in REDIRECTORS

    private fun tapMatches(tap: Tap?, site: String, now: Long): Boolean =
        tap != null && !tap.overlay && now - tap.at in 0..TAP_WINDOW_MS && Sites.ofUrl(tap.url) == site

    /**
     * @param currentUrl la pagina aperta adesso (null sulla pagina iniziale)
     * @param isRedirect reindirizzamento del server (HTTP 3xx)
     * @param lastAllowedAt quando è stata lasciata passare l'ultima navigazione
     */
    fun decide(
        targetUrl: String,
        currentUrl: String?,
        isRedirect: Boolean,
        tap: Tap?,
        lastAllowedAt: Long,
        now: Long,
        blocked: (String) -> Boolean,
    ): Verdict {
        val host = Sites.hostOf(targetUrl)
        val site = Sites.of(host)
        val cur = currentUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val curHost = Sites.hostOf(cur)
        val curSite = Sites.of(curHost)
        if (blocked(host)) {
            // il sito stesso è in elenco ma l'utente ha scelto di aprirlo: le sue pagine passano
            return if (cur != null && site == curSite && blocked(curHost)) Verdict.ALLOW else Verdict.AD
        }
        if (cur == null) return Verdict.ALLOW
        if (site == curSite) return Verdict.ALLOW
        if (isRedirect && now - lastAllowedAt in 0..REDIRECT_WINDOW_MS) return Verdict.ALLOW
        if (isRedirector(curSite)) return Verdict.ALLOW
        if (tapMatches(tap, site, now)) return Verdict.ALLOW
        return Verdict.UNWANTED
    }

    /**
     * Una finestra nuova aperta da un tocco. Si apre al posto della pagina solo se è dello stesso sito
     * (spesso il player); verso altri siti sono quasi sempre i finti pulsanti "Play" pubblicitari,
     * quindi restano bloccate e l'utente può aprirle con un tocco se servivano.
     */
    fun popup(url: String, currentUrl: String?, blocked: (String) -> Boolean): Popup {
        val host = Sites.hostOf(url)
        val cur = currentUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        // il trucco del "pop-under": riaprire la stessa pagina in una scheda nuova e coprire quella vecchia
        if (cur != null && url.substringBefore('#').trimEnd('/') == cur.substringBefore('#').trimEnd('/')) return Popup.SAME_PAGE
        if (blocked(host)) return Popup.AD
        if (cur != null && Sites.of(host) == Sites.ofUrl(cur)) return Popup.OPEN_HERE
        return Popup.UNWANTED
    }
}
