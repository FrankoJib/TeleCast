package it.telecast.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Fornisce gli header da usare verso il sito di origine (Referer, cookie, User-Agent...). */
fun interface HeaderProvider {
    fun headersFor(url: String): Map<String, String>
}

class HttpStatusException(val code: Int, val url: String) : IOException("HTTP $code")

/** Risposta di un server di origine; va chiusa con close() (o use {}). */
class Upstream internal constructor(
    private val conn: HttpURLConnection,
    val code: Int,
    val finalUrl: String,
) : Closeable {
    val contentType: String? get() = conn.contentType
    val contentLength: Long get() = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
    fun header(name: String): String? = conn.getHeaderField(name)

    fun body(): InputStream =
        if (code >= 400) conn.errorStream ?: ByteArrayInputStream(ByteArray(0)) else conn.inputStream

    /** Lunghezza totale della risorsa, anche quando la risposta è parziale (206). */
    val totalLength: Long
        get() {
            if (code == 206) {
                val cr = header("Content-Range") ?: return -1
                return cr.substringAfterLast('/', "").trim().toLongOrNull() ?: -1
            }
            return contentLength
        }

    override fun close() {
        try { conn.disconnect() } catch (_: Exception) { }
    }
}

object Http {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    /**
     * Apre una connessione seguendo i redirect a mano (anche https -> http), ricalcolando
     * gli header per ogni salto, così i cookie non finiscono su domini diversi.
     */
    fun open(
        url: String,
        headers: HeaderProvider?,
        method: String = "GET",
        extra: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 25_000,
    ): Upstream {
        var current = url
        var hops = 0
        while (true) {
            val u = URL(current)
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.useCaches = false
            var hasUa = false
            for ((k, v) in headers?.headersFor(current).orEmpty()) {
                if (v.isEmpty()) continue
                if (k.equals("User-Agent", ignoreCase = true)) hasUa = true
                c.setRequestProperty(k, v)
            }
            if (!hasUa) c.setRequestProperty("User-Agent", DEFAULT_UA)
            c.setRequestProperty("Accept-Encoding", "identity")
            for ((k, v) in extra) c.setRequestProperty(k, v)
            val code = try {
                c.responseCode
            } catch (e: IOException) {
                c.disconnect()
                throw e
            }
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc.isNullOrBlank() || ++hops > 8) throw IOException("Redirect non valido (HTTP $code)")
                current = URL(u, loc).toString()
                continue
            }
            return Upstream(c, code, current)
        }
    }

    fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("Risposta troppo grande")
        }
        return out.toByteArray()
    }

    /** GET testuale (playlist). Ritorna testo e URL finale dopo i redirect. */
    fun getText(url: String, headers: HeaderProvider?, maxBytes: Int = 8 shl 20, timeoutMs: Int = 15_000): Pair<String, String> {
        open(url, headers, connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs).use { r ->
            if (r.code !in 200..299) throw HttpStatusException(r.code, url)
            val bytes = r.body().use { readLimited(it, maxBytes) }
            return String(bytes, Charsets.UTF_8) to r.finalUrl
        }
    }

    fun getBytes(url: String, headers: HeaderProvider?, maxBytes: Int = 64 shl 20, timeoutMs: Int = 20_000): ByteArray {
        open(url, headers, connectTimeoutMs = timeoutMs, readTimeoutMs = timeoutMs).use { r ->
            if (r.code !in 200..299) throw HttpStatusException(r.code, url)
            return r.body().use { readLimited(it, maxBytes) }
        }
    }
}
