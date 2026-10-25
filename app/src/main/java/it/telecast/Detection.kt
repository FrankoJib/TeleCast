package it.telecast

import it.telecast.core.Fmt
import it.telecast.core.HeaderProvider
import it.telecast.core.Hls
import it.telecast.core.Http
import it.telecast.core.Log
import it.telecast.core.MediaDetector
import it.telecast.core.MediaKind
import it.telecast.core.MediaSource
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/** Elenco dei video trovati nella navigazione corrente. */
class DetectedStore {
    private val items = ArrayList<MediaSource>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun strip(u: String) = u.substringBefore('#').substringBefore('?')

    /** Aggiunge un video; false se c'è già (o se è una sotto-playlist di una master già nota). */
    fun add(src: MediaSource): Boolean {
        synchronized(items) {
            if (items.any { it.url == src.url }) return false
            val key = strip(src.url)
            if (items.any { m -> m.variants.any { strip(it.uri) == key } }) return false
            items.add(0, src)
            while (items.size > 40) items.removeAt(items.size - 1)
        }
        changed()
        return true
    }

    fun removeIf(pred: (MediaSource) -> Boolean) {
        val removed = synchronized(items) { items.removeAll(pred) }
        if (removed) changed()
    }

    fun clear() {
        synchronized(items) { items.clear() }
        changed()
    }

    val size: Int get() = synchronized(items) { items.count { it.error == null } }

    /** Prima i contenuti "principali" (lunghi), poi i più recenti; quelli in errore in fondo. */
    fun list(): List<MediaSource> = synchronized(items) {
        items.sortedWith(
            compareBy<MediaSource> { it.error != null }
                .thenByDescending { it.looksMain }
                .thenByDescending { it.foundAt },
        )
    }

    fun changed() {
        App.instance.main.post { for (l in listeners) l() }
    }
}

/** Analizza in background un video trovato: durata, qualità, dimensione. */
object Enricher {
    private const val TAG = "Rileva"

    private fun strip(u: String) = u.substringBefore('#').substringBefore('?')

    fun probe(src: MediaSource, store: DetectedStore) {
        try {
            if (src.kind == MediaKind.FILE) {
                Http.open(src.url, src, extra = mapOf("Range" to "bytes=0-1"), connectTimeoutMs = 8000, readTimeoutMs = 8000).use { up ->
                    if (up.code >= 400) {
                        src.error = "il sito risponde HTTP ${up.code}"
                        return@use
                    }
                    val type = up.contentType?.lowercase(Locale.ROOT) ?: ""
                    if (type.contains("mpegurl")) {
                        src.kind = MediaKind.HLS
                    } else {
                        src.sizeBytes = up.totalLength
                        val ext = MediaDetector.extension(src.url).uppercase(Locale.ROOT).ifEmpty { "VIDEO" }
                        src.info = listOf(ext, Fmt.size(src.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · ")
                    }
                }
                if (src.kind == MediaKind.FILE) return
            }
            val (text, finalUrl) = Http.getText(src.url, src)
            if (!Hls.isPlaylist(text)) {
                src.error = "non è un video"
                return
            }
            if (Hls.isMaster(text)) {
                val m = Hls.parseMaster(text, finalUrl)
                src.variants = m.variants
                // le sotto-playlist (qualità, audio) della stessa master non servono nell'elenco
                val children = (m.variants.map { it.uri } + m.renditions.mapNotNull { it.uri }).map { strip(it) }.toSet()
                store.removeIf { it !== src && strip(it.url) in children }
                val v = Hls.pickVariant(m)
                var dur = ""
                if (v != null) {
                    val (t2, f2) = Http.getText(v.uri, src)
                    val pl = Hls.parsePlaylist(t2, f2)
                    src.durationSec = if (pl.endList) pl.totalDuration else 0.0
                    dur = if (pl.endList) Fmt.duration(pl.totalDuration) else "LIVE"
                }
                val quality = v?.label?.let { if (m.variants.size > 1) "fino a $it" else it }
                src.info = listOfNotNull("HLS", quality, dur.ifEmpty { null }).joinToString(" · ")
            } else {
                val pl = Hls.parsePlaylist(text, finalUrl)
                src.durationSec = if (pl.endList) pl.totalDuration else 0.0
                src.info = listOf("HLS", if (pl.endList) Fmt.duration(pl.totalDuration) else "LIVE").joinToString(" · ")
            }
        } catch (e: Exception) {
            src.error = e.message ?: e.javaClass.simpleName
            Log.d(TAG, "analisi non riuscita (${src.url.take(80)}): ${src.error}")
        } finally {
            src.probed = true
            store.changed()
        }
    }

    private fun readUpTo(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** Un URL senza estensione è una playlist HLS? Lo scopro leggendo i primi byte. */
    fun isHlsPlaylist(url: String, headers: HeaderProvider): Boolean = try {
        Http.open(url, headers, connectTimeoutMs = 6000, readTimeoutMs = 6000).use { up ->
            if (up.code !in 200..299) return false
            val type = up.contentType?.lowercase(Locale.ROOT) ?: ""
            if (type.contains("mpegurl")) return true
            if (type.startsWith("image/") || type.startsWith("text/html") || type.contains("javascript") || type.contains("css")) return false
            val head = ByteArray(64)
            val n = up.body().use { readUpTo(it, head) }
            Hls.isPlaylist(String(head, 0, n, Charsets.UTF_8))
        }
    } catch (_: Exception) {
        false
    }
}
