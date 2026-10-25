package it.telecast.core

import java.net.URL
import java.net.URLDecoder
import java.util.Locale

enum class MediaKind { HLS, FILE }

/**
 * Un video trovato in una pagina: URL più il "contesto" che il sito si aspetta
 * (Referer, Origin, User-Agent, cookie). La TV non può mandare questi header,
 * quindi li aggiunge il proxy locale del telefono.
 */
class MediaSource(
    val url: String,
    @Volatile var kind: MediaKind,
    val pageUrl: String?,
    val referer: String?,
    val origin: String?,
    val userAgent: String?,
    @Volatile var title: String,
    private val cookies: ((String) -> String?)? = null,
) : HeaderProvider {
    @Volatile var sizeBytes: Long = -1
    @Volatile var durationSec: Double = -1.0
    @Volatile var variants: List<HlsVariant> = emptyList()
    @Volatile var info: String? = null
    @Volatile var probed = false
    @Volatile var error: String? = null
    val foundAt: Long = System.currentTimeMillis()

    val host: String
        get() = try { URL(url).host.removePrefix("www.") } catch (_: Exception) { "" }

    /** Probabile contenuto principale (film/episodio) e non pubblicità o anteprime. */
    val looksMain: Boolean
        get() = durationSec >= 600 || sizeBytes >= 150L * 1024 * 1024

    override fun headersFor(url: String): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = userAgent ?: Http.DEFAULT_UA
        h["Accept"] = "*/*"
        h["Accept-Language"] = "it-IT,it;q=0.9,en;q=0.8"
        referer?.let { h["Referer"] = it }
        origin?.let { h["Origin"] = it }
        try {
            cookies?.invoke(url)?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        } catch (_: Exception) { }
        return h
    }
}

/** Riconosce dagli URL le richieste che sono video (file o playlist HLS). */
object MediaDetector {
    private val FILE_EXT = setOf("mp4", "m4v", "webm", "mkv", "mov")
    private val SKIP_EXT = setOf(
        "ts", "m4s", "m4a", "aac", "mp3", "vtt", "srt", "webvtt", "js", "mjs", "css", "png", "jpg",
        "jpeg", "gif", "webp", "avif", "svg", "ico", "woff", "woff2", "ttf", "otf", "json", "xml",
        "txt", "wasm", "map", "key", "html", "htm", "php",
    )
    private val SEGMENT_NAME = Regex("""(^|[/_\-.])(seg|segment|chunk|frag|fragment|part|init)[-_]?\d*[^/]*\.(mp4|m4v)$""")
    private val CANDIDATE = Regex("""(playlist|manifest|master|/hls/|\.urlset|m3u8)""")
    private val ADS = listOf(
        "doubleclick.", "googlesyndication.", "googleads.", "adservice.", "imasdk.", "adsystem",
        "/ads/", "/vast", "vpaid", "adnxs.", "taboola.", "outbrain.", "popads", "propellerads",
    )

    /** Percorso dell'URL (senza schema, host, query e frammento). */
    fun path(url: String): String {
        val s = url.substringBefore('#').substringBefore('?')
        val i = s.indexOf("://")
        if (i < 0) return s
        val slash = s.indexOf('/', i + 3)
        return if (slash < 0) "/" else s.substring(slash)
    }

    fun extension(url: String): String {
        val last = path(url).substringAfterLast('/')
        return if ('.' in last) last.substringAfterLast('.').lowercase(Locale.ROOT) else ""
    }

    fun fileName(url: String): String {
        val last = path(url).trimEnd('/').substringAfterLast('/')
        val decoded = try { URLDecoder.decode(last, "UTF-8") } catch (_: Exception) { last }
        return decoded.ifBlank { "video" }
    }

    fun isAd(url: String): Boolean {
        val l = url.lowercase(Locale.ROOT)
        return ADS.any { l.contains(it) }
    }

    fun classify(url: String): MediaKind? {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val ext = extension(lower)
        if (ext == "m3u8" || ext == "m3u") return MediaKind.HLS
        if (ext in FILE_EXT) return if (SEGMENT_NAME.containsMatchIn(path(lower))) null else MediaKind.FILE
        if (ext !in SKIP_EXT && path(lower).contains(".m3u8")) return MediaKind.HLS
        return null
    }

    /** URL senza estensione che potrebbe essere una playlist HLS: va verificato scaricandone l'inizio. */
    fun isCandidate(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http")) return false
        val ext = extension(lower)
        if (ext.isNotEmpty() && (ext in SKIP_EXT || ext in FILE_EXT)) return false
        return CANDIDATE.containsMatchIn(path(lower)) || lower.substringAfter('?', "").contains("m3u8")
    }
}

/** Formattazione all'italiana. */
object Fmt {
    fun size(bytes: Long): String {
        if (bytes < 0) return ""
        val units = listOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        val s = if (u >= 2) String.format(Locale.ITALY, "%.1f", v) else v.toLong().toString()
        return "$s ${units[u]}"
    }

    fun clock(sec: Int): String {
        val s = maxOf(0, sec)
        val h = s / 3600
        val m = (s / 60) % 60
        val r = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, r) else String.format(Locale.US, "%d:%02d", m, r)
    }

    fun duration(sec: Double): String {
        if (sec <= 0) return ""
        val s = sec.toInt()
        val h = s / 3600
        val m = (s / 60) % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "$m min"
            else -> "$s s"
        }
    }
}
