package it.telecast.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class Request(
    val method: String,
    val path: String,
    val version: String,
    val headers: Map<String, String>,
    val remote: String,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]
}

/** Stato di un video servito alla TV (una sessione per ogni "trasmetti"). */
class ServeSession(val id: String, val source: MediaSource) {
    // file singolo (mp4, webm, mkv...)
    @Volatile var fileMime: String = "video/mp4"
    @Volatile var fileLength: Long = -1

    // HLS
    @Volatile var variantIndex: Int? = null
    @Volatile var mediaPlaylistUrl: String? = null
    @Volatile var playlist: HlsPlaylist? = null
    @Volatile var separateAudio = false
    @Volatile var fmp4 = false
    @Volatile var remuxMime: String = "video/mpeg"
    @Volatile var playlistMime: String = "application/vnd.apple.mpegurl"
    @Volatile var quality: String? = null

    val remuxable: Boolean
        get() = playlist?.let { !it.encrypted && !it.byteRange && it.segments.isNotEmpty() } == true && !separateAudio

    private val refs = ArrayList<String>()
    private val refTypes = ArrayList<Hls.RefType>()
    private val refIndex = HashMap<String, Int>()

    fun ref(url: String, type: Hls.RefType): Int = synchronized(refs) {
        refIndex[url] ?: run {
            refs += url
            refTypes += type
            (refs.size - 1).also { refIndex[url] = it }
        }
    }

    fun refAt(n: Int): Pair<String, Hls.RefType>? = synchronized(refs) {
        if (n in refs.indices) refs[n] to refTypes[n] else null
    }

    val bytesServed = AtomicLong()
    val requests = AtomicInteger()
    @Volatile var lastRequestAt = 0L
}

/**
 * Piccolo server HTTP sul telefono. La TV chiede i dati qui; il server li prende dal sito
 * con gli header giusti e glieli passa. Gli URL che vede la TV sono brevi e solo ASCII
 * (le LG rifiutano URL lunghi o "strani" con errore UPnP 716).
 */
class StreamServer(private val preferredPort: Int = 8765) {
    private val tag = "Proxy"
    @Volatile private var server: ServerSocket? = null
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "tc-proxy").apply { isDaemon = true } }
    private val sessions = ConcurrentHashMap<String, ServeSession>()
    private val order = ConcurrentLinkedDeque<String>()

    val port: Int get() = server?.localPort ?: -1

    @Synchronized
    fun start() {
        if (server?.isClosed == false) return
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort), 64)
            }
        } catch (e: IOException) {
            Log.w(tag, "porta $preferredPort occupata, ne uso una libera")
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0), 64)
            }
        }
        server = s
        Thread({ acceptLoop(s) }, "tc-accept").apply { isDaemon = true }.start()
        Log.i(tag, "proxy in ascolto sulla porta ${s.localPort}")
    }

    @Synchronized
    fun stop() {
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    fun register(s: ServeSession) {
        sessions[s.id] = s
        order.addLast(s.id)
        while (order.size > 4) order.pollFirst()?.let { sessions.remove(it) }
    }

    fun session(id: String): ServeSession? = sessions[id]

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (_: IOException) { break }
            try {
                pool.execute { handle(c) }
            } catch (e: Exception) {
                try { c.close() } catch (_: Exception) { }
            }
        }
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { c ->
                c.soTimeout = 30_000
                c.tcpNoDelay = true
                val input = BufferedInputStream(c.getInputStream())
                val req = readRequest(input, c.inetAddress?.hostAddress ?: "?") ?: return
                val out = BufferedOutputStream(c.getOutputStream(), 64 * 1024)
                route(req, out)
                out.flush()
            }
        } catch (_: SocketException) {
            // la TV ha chiuso la connessione (succede spesso: seek, probe, stop)
        } catch (_: SocketTimeoutException) {
        } catch (e: IOException) {
            Log.d(tag, "connessione interrotta: ${e.message}")
        } catch (e: Exception) {
            Log.e(tag, "errore nella richiesta", e)
        }
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream(1024)
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A || (last4 and 0xFFFF) == 0x0A0A) break
            if (buf.size() > 32 * 1024) throw IOException("intestazione troppo lunga")
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readRequest(input: InputStream, remote: String): Request? {
        val head = readHead(input) ?: return null
        val lines = head.lines().filter { it.isNotBlank() }
        val parts = lines.firstOrNull()?.trim()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase(Locale.ROOT)] = l.substring(i + 1).trim()
        }
        return Request(
            method = parts[0].uppercase(Locale.ROOT),
            path = parts[1].substringBefore('?'),
            version = parts.getOrNull(2)?.uppercase(Locale.ROOT) ?: "HTTP/1.0",
            headers = headers,
            remote = remote,
        )
    }

    private fun route(req: Request, out: OutputStream) {
        val range = req.header("range")?.let { " [$it]" } ?: ""
        Log.d(tag, "${req.remote} ${req.method} ${req.path}$range")
        if (req.method == "OPTIONS") {
            // "preflight" CORS: il ricevitore Chromecast è una pagina web e lo chiede prima di leggere l'HLS
            writeHead(
                out, 204,
                listOf(
                    "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                    "Access-Control-Allow-Headers" to (req.header("access-control-request-headers") ?: "Range, Content-Type"),
                    "Access-Control-Max-Age" to "600",
                    "Content-Length" to "0",
                ),
            )
            return
        }
        if (req.method != "GET" && req.method != "HEAD") {
            simple(out, 405, "Metodo non supportato")
            return
        }
        val seg = req.path.trim('/').split('/')
        if (seg.size < 3) {
            simple(out, 404, "Non trovato")
            return
        }
        val s = sessions[seg[1]] ?: run {
            simple(out, 404, "Sessione scaduta")
            return
        }
        s.requests.incrementAndGet()
        s.lastRequestAt = System.currentTimeMillis()
        when (seg[0]) {
            "v" -> serveFile(s, req, out)
            "h" -> serveHls(s, req, out, seg)
            "t" -> serveRemux(s, req, out, seg)
            else -> simple(out, 404, "Non trovato")
        }
    }

    // ---------- risposta HTTP ----------

    private fun reason(code: Int) = when (code) {
        200 -> "OK"
        204 -> "No Content"
        206 -> "Partial Content"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        416 -> "Range Not Satisfiable"
        502 -> "Bad Gateway"
        else -> "Status"
    }

    private fun writeHead(out: OutputStream, code: Int, headers: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
        sb.append("Server: Linux/4.0 UPnP/1.0 DLNADOC/1.50 TeleCast/1.0\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun simple(out: OutputStream, code: Int, msg: String) {
        val b = msg.toByteArray(Charsets.UTF_8)
        writeHead(out, code, listOf("Content-Type" to "text/plain; charset=utf-8", "Content-Length" to "${b.size}"))
        out.write(b)
    }

    private fun dlna(seekable: Boolean) = listOf(
        "transferMode.dlna.org" to "Streaming",
        "contentFeatures.dlna.org" to Didl.features(seekable),
    )

    private fun pump(inp: InputStream, out: OutputStream, s: ServeSession) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = inp.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            s.bytesServed.addAndGet(n.toLong())
        }
        out.flush()
    }

    private fun skipFully(inp: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw IOException("fine dati durante lo skip")
            left -= r
        }
    }

    private fun parseRangeStart(range: String?): Long {
        if (range == null) return 0
        return Regex("""bytes\s*=\s*(\d+)-""").find(range)?.groupValues?.get(1)?.toLongOrNull() ?: 0
    }

    private fun baseMime(t: String?): String? = t?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)?.ifEmpty { null }

    /** I server dei video spesso dichiarano tipi generici: in quel caso usiamo quello che sappiamo noi. */
    private fun chooseMime(upstream: String?, fallback: String): String {
        val m = baseMime(upstream) ?: return fallback
        return if (m.startsWith("video/") || m.startsWith("audio/")) m else fallback
    }

    // ---------- file singolo ----------

    private fun serveFile(s: ServeSession, req: Request, out: OutputStream) {
        val src = s.source
        if (req.method == "HEAD" && s.fileLength > 0) {
            writeHead(out, 200, listOf("Content-Type" to s.fileMime, "Content-Length" to "${s.fileLength}", "Accept-Ranges" to "bytes") + dlna(true))
            return
        }
        val range = req.header("range")
        val extra = if (range != null) mapOf("Range" to range) else emptyMap()
        Http.open(src.url, src, method = if (req.method == "HEAD") "HEAD" else "GET", extra = extra).use { up ->
            when (up.code) {
                200, 206 -> {
                    val h = ArrayList<Pair<String, String>>()
                    h += "Content-Type" to chooseMime(up.contentType, s.fileMime)
                    h += "Accept-Ranges" to "bytes"
                    val len = up.contentLength
                    var code = up.code
                    var skip = 0L
                    if (up.code == 206) {
                        up.header("Content-Range")?.let { h += "Content-Range" to it }
                        if (len >= 0) h += "Content-Length" to "$len"
                    } else {
                        // l'origine ignora Range: se la TV ha chiesto un punto preciso lo emuliamo saltando i byte
                        val start = parseRangeStart(range)
                        if (start > 0 && len > 0 && start < len) {
                            code = 206
                            skip = start
                            h += "Content-Range" to "bytes $start-${len - 1}/$len"
                            h += "Content-Length" to "${len - start}"
                        } else if (len >= 0) {
                            h += "Content-Length" to "$len"
                        }
                    }
                    h += dlna(true)
                    writeHead(out, code, h)
                    if (req.method == "HEAD") return
                    up.body().use { inp ->
                        if (skip > 0) skipFully(inp, skip)
                        pump(inp, out, s)
                    }
                }
                416 -> writeHead(out, 416, listOf("Content-Range" to (up.header("Content-Range") ?: "bytes */${s.fileLength}"), "Content-Length" to "0"))
                else -> {
                    Log.w(tag, "il sito ha risposto HTTP ${up.code} per il video")
                    simple(out, 502, "Origine HTTP ${up.code}")
                }
            }
        }
    }

    // ---------- HLS riscritto (la TV usa il suo lettore HLS) ----------

    private fun serveHls(s: ServeSession, req: Request, out: OutputStream, seg: List<String>) {
        val name = seg.getOrNull(2) ?: ""
        val host = req.header("host") ?: "127.0.0.1:$port"
        val base = "http://$host/h/${s.id}"
        when {
            name == "master.m3u8" -> servePlaylist(s, req, out, s.source.url, base, s.variantIndex)
            name.startsWith("p") && name.endsWith(".m3u8") -> {
                val ref = name.substring(1, name.length - 5).toIntOrNull()?.let { s.refAt(it) }
                if (ref == null) simple(out, 404, "Non trovato") else servePlaylist(s, req, out, ref.first, base, null)
            }
            name.startsWith("r") -> {
                val ref = name.substring(1).substringBefore('.').toIntOrNull()?.let { s.refAt(it) }
                if (ref == null) simple(out, 404, "Non trovato") else serveResource(s, req, out, ref.first, ref.second)
            }
            else -> simple(out, 404, "Non trovato")
        }
    }

    private fun extFor(url: String, type: Hls.RefType, fmp4: Boolean): String {
        val e = MediaDetector.extension(url)
        if (e.length in 1..5 && e.all { it.isLetterOrDigit() } && e != "html" && e != "php" && e != "png" && e != "jpg") return e
        return when (type) {
            Hls.RefType.SEGMENT -> if (fmp4) "m4s" else "ts"
            Hls.RefType.KEY -> "key"
            Hls.RefType.MAP -> "mp4"
            Hls.RefType.PLAYLIST -> "m3u8"
            Hls.RefType.OTHER -> "bin"
        }
    }

    private fun servePlaylist(s: ServeSession, req: Request, out: OutputStream, url: String, base: String, keepVariant: Int?) {
        val (text, finalUrl) = try {
            Http.getText(url, s.source)
        } catch (e: HttpStatusException) {
            Log.w(tag, "playlist: il sito ha risposto HTTP ${e.code}")
            simple(out, 502, "Origine HTTP ${e.code}")
            return
        }
        val rewritten = Hls.rewrite(text, finalUrl, keepVariant) { abs, type ->
            val n = s.ref(abs, type)
            if (type == Hls.RefType.PLAYLIST) "$base/p$n.m3u8" else "$base/r$n.${extFor(abs, type, s.fmp4)}"
        }
        val body = rewritten.toByteArray(Charsets.UTF_8)
        writeHead(out, 200, listOf("Content-Type" to s.playlistMime, "Content-Length" to "${body.size}", "Cache-Control" to "no-cache") + dlna(true))
        if (req.method != "HEAD") {
            out.write(body)
            s.bytesServed.addAndGet(body.size.toLong())
        }
    }

    private fun serveResource(s: ServeSession, req: Request, out: OutputStream, url: String, type: Hls.RefType) {
        val range = req.header("range")
        if (type == Hls.RefType.SEGMENT && range == null && !s.fmp4) {
            // segmento TS: lo scarico intero per poterlo "ripulire" se è travestito
            val data = try {
                fetchSegment(s, url)
            } catch (e: HttpStatusException) {
                simple(out, 502, "Origine HTTP ${e.code}")
                return
            }
            writeHead(out, 200, listOf("Content-Type" to "video/mp2t", "Content-Length" to "${data.size}") + dlna(true))
            if (req.method != "HEAD") {
                out.write(data)
                s.bytesServed.addAndGet(data.size.toLong())
            }
            return
        }
        val extra = if (range != null) mapOf("Range" to range) else emptyMap()
        Http.open(url, s.source, method = if (req.method == "HEAD") "HEAD" else "GET", extra = extra).use { up ->
            if (up.code !in 200..299) {
                simple(out, 502, "Origine HTTP ${up.code}")
                return
            }
            val h = ArrayList<Pair<String, String>>()
            h += "Content-Type" to (baseMime(up.contentType) ?: "application/octet-stream")
            up.header("Content-Range")?.let { h += "Content-Range" to it }
            if (up.contentLength >= 0) h += "Content-Length" to "${up.contentLength}"
            writeHead(out, up.code, h)
            if (req.method != "HEAD") up.body().use { pump(it, out, s) }
        }
    }

    internal fun fetchSegment(s: ServeSession, url: String): ByteArray {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val b = Http.getBytes(url, s.source)
                return if (s.fmp4) b else TsUtil.align(b)
            } catch (e: HttpStatusException) {
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
            Thread.sleep(500L + attempt * 1000L)
        }
        throw lastError ?: IOException("segmento non scaricabile")
    }

    // ---------- HLS "unito" in un unico flusso (modalità compatibilità) ----------

    private fun serveRemux(s: ServeSession, req: Request, out: OutputStream, seg: List<String>) {
        val from = seg.getOrNull(2)?.toIntOrNull() ?: 0
        val chunked = req.version != "HTTP/1.0"
        val h = ArrayList<Pair<String, String>>()
        h += "Content-Type" to s.remuxMime
        if (chunked) h += "Transfer-Encoding" to "chunked"
        h += dlna(false)
        writeHead(out, 200, h)
        if (req.method == "HEAD") return
        Log.i(tag, "flusso unico dal segmento $from (${s.remuxMime})")
        if (chunked) {
            val co = ChunkedOutputStream(out)
            Remuxer(this, s, from).run(co)
            co.finish()
        } else {
            Remuxer(this, s, from).run(out)
        }
    }
}

/** Scarica i segmenti in ordine (con un piccolo anticipo) e li scrive uno dopo l'altro. */
class Remuxer(private val server: StreamServer, private val s: ServeSession, private val from: Int) {
    private val tag = "Remux"

    private fun loadPlaylist(): HlsPlaylist {
        val url = s.mediaPlaylistUrl ?: s.source.url
        val (t, f) = Http.getText(url, s.source)
        return Hls.parsePlaylist(t, f).also { s.playlist = it }
    }

    fun run(out: OutputStream) {
        val queue = ArrayBlockingQueue<ByteArray>(3)
        val end = ByteArray(0)
        val stop = AtomicBoolean(false)
        val producer = Thread({
            var failures = 0
            try {
                var pl = s.playlist ?: loadPlaylist()
                if (s.fmp4) pl.mapUri?.let { queue.put(server.fetchSegment(s, it)) }
                var idx = from.coerceIn(0, maxOf(0, pl.segments.size - 1))
                var lastSeq = -1L
                while (!stop.get()) {
                    if (idx < pl.segments.size) {
                        val sg = pl.segments[idx]
                        var data: ByteArray? = null
                        try {
                            data = server.fetchSegment(s, sg.uri)
                        } catch (e: HttpStatusException) {
                            if (e.code == 401 || e.code == 403 || e.code == 410) {
                                // link scaduto: ricarico la playlist e riprovo lo stesso segmento
                                Log.w(tag, "segmento $idx: HTTP ${e.code}, ricarico la playlist")
                                try {
                                    pl = loadPlaylist()
                                    val again = pl.segments.firstOrNull { it.seq == sg.seq } ?: pl.segments.getOrNull(idx)
                                    if (again != null) data = server.fetchSegment(s, again.uri)
                                } catch (e2: Exception) {
                                    Log.w(tag, "nuovo tentativo fallito: ${e2.message}")
                                }
                            } else {
                                Log.w(tag, "segmento $idx: HTTP ${e.code}")
                            }
                        } catch (e: IOException) {
                            Log.w(tag, "segmento $idx: ${e.message}")
                        }
                        idx++
                        lastSeq = sg.seq
                        if (data == null) {
                            if (++failures >= 5) throw IOException("troppi segmenti non scaricabili")
                            continue
                        }
                        failures = 0
                        queue.put(data)
                    } else if (pl.endList) {
                        break
                    } else {
                        // diretta: aspetto nuovi segmenti
                        Thread.sleep((pl.targetDuration * 500).toLong().coerceIn(1000, 5000))
                        pl = loadPlaylist()
                        idx = pl.segments.indexOfFirst { it.seq > lastSeq }.let { if (it < 0) pl.segments.size else it }
                    }
                }
            } catch (_: InterruptedException) {
            } catch (e: Exception) {
                Log.w(tag, "flusso interrotto: ${e.message}")
            } finally {
                try { if (!stop.get()) queue.put(end) } catch (_: InterruptedException) { }
            }
        }, "tc-remux").apply {
            isDaemon = true
            start()
        }
        try {
            while (true) {
                val b = queue.poll(90, TimeUnit.SECONDS) ?: break
                if (b === end) break
                out.write(b)
                out.flush()
                s.bytesServed.addAndGet(b.size.toLong())
            }
        } finally {
            stop.set(true)
            producer.interrupt()
        }
    }
}

class ChunkedOutputStream(private val out: OutputStream) : OutputStream() {
    private val crlf = "\r\n".toByteArray(Charsets.ISO_8859_1)

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        out.write(Integer.toHexString(len).toByteArray(Charsets.ISO_8859_1))
        out.write(crlf)
        out.write(b, off, len)
        out.write(crlf)
    }

    override fun flush() = out.flush()

    fun finish() {
        out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }
}
