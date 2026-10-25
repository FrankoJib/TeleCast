package it.telecast.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.random.Random

class CastException(code: Int, message: String) : TvException(code, message)

/** Messaggio Cast v2 (protobuf "CastMessage" con payload JSON), codificato a mano: niente librerie Google. */
object CastProto {
    class Msg(val sourceId: String, val destinationId: String, val namespace: String, val payload: String?)

    fun encode(source: String, dest: String, ns: String, payload: String): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(0x08)
        varint(o, 0) // protocol_version = CASTV2_1_0
        str(o, 2, source)
        str(o, 3, dest)
        str(o, 4, ns)
        o.write(0x28)
        varint(o, 0) // payload_type = STRING
        str(o, 6, payload)
        return o.toByteArray()
    }

    private fun str(o: ByteArrayOutputStream, field: Int, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.write((field shl 3) or 2)
        varint(o, b.size.toLong())
        o.write(b)
    }

    private fun varint(o: ByteArrayOutputStream, value: Long) {
        var x = value
        while (true) {
            if ((x and 0x7FL.inv()) == 0L) {
                o.write(x.toInt())
                return
            }
            o.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < b.size) {
            val v = b[i++].toInt() and 0xFF
            result = result or ((v and 0x7F).toLong() shl shift)
            if ((v and 0x80) == 0) return result to i
            shift += 7
            if (shift > 63) break
        }
        throw IOException("varint non valido")
    }

    fun decode(b: ByteArray): Msg {
        var i = 0
        var src = ""
        var dst = ""
        var ns = ""
        var payload: String? = null
        while (i < b.size) {
            val (key, n1) = readVarint(b, i)
            i = n1
            val field = (key shr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> i = readVarint(b, i).second
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val (len, n2) = readVarint(b, i)
                    i = n2
                    val n = len.toInt()
                    if (n < 0 || i + n > b.size) throw IOException("campo troncato")
                    if (field in 2..4 || field == 6) {
                        val s = String(b, i, n, Charsets.UTF_8)
                        when (field) {
                            2 -> src = s
                            3 -> dst = s
                            4 -> ns = s
                            6 -> payload = s
                        }
                    }
                    i += n
                }
                else -> throw IOException("tipo protobuf non gestito")
            }
        }
        return Msg(src, dst, ns, payload)
    }
}

/** I Chromecast usano un certificato autofirmato: si accetta solo per questo canale verso la TV di casa. */
private object TrustCastDevice : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

/** Canale TLS sulla porta 8009 della TV, con heartbeat e richieste/risposte per requestId. */
class CastChannel(private val host: String, private val port: Int) : Closeable {
    companion object {
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
        const val RECEIVER = "receiver-0"
        const val CLOSED = "__CLOSED__"

        val CONNECT: Map<String, Any?> = mapOf(
            "type" to "CONNECT",
            "origin" to emptyMap<String, Any?>(),
            "userAgent" to "TeleCast",
            "senderInfo" to mapOf(
                "sdkType" to 2, "version" to "15.605.1.3", "browserVersion" to "44.0.2403.30",
                "platform" to 4, "systemVersion" to "Android", "connectionType" to 1,
            ),
        )
    }

    private val tag = "Cast"
    private val senderId = "sender-" + Random.nextInt(100000, 999999)
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private val pending = ConcurrentHashMap<Int, ArrayBlockingQueue<Map<String, Any?>>>()
    private val nextId = AtomicInteger(1)
    @Volatile private var lastActivity = 0L

    @Volatile var closed = false
        private set

    @Volatile var listener: ((namespace: String, msg: Map<String, Any?>) -> Unit)? = null

    @Volatile var onTransportClosed: ((sourceId: String) -> Unit)? = null

    fun connect(timeoutMs: Int = 6000) {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.tcpNoDelay = true
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(TrustCastDevice), SecureRandom())
        val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.soTimeout = timeoutMs
        s.startHandshake()
        s.soTimeout = 0
        socket = s
        output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        lastActivity = System.currentTimeMillis()
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        Thread({ readLoop(input) }, "tc-cast-read").apply { isDaemon = true }.start()
        send(NS_CONNECTION, RECEIVER, CONNECT)
        Thread({ heartbeatLoop() }, "tc-cast-ping").apply { isDaemon = true }.start()
        Log.d(tag, "canale aperto con $host:$port")
    }

    fun send(ns: String, dest: String, payload: Map<String, Any?>) {
        val o = output ?: throw IOException("canale Cast non aperto")
        if (closed) throw IOException("canale Cast chiuso")
        val body = CastProto.encode(senderId, dest, ns, Json.stringify(payload))
        synchronized(this) {
            o.writeInt(body.size)
            o.write(body)
            o.flush()
        }
    }

    /** Invia e aspetta la risposta con lo stesso requestId (filtrata da [accept]). */
    fun request(
        ns: String,
        dest: String,
        payload: Map<String, Any?>,
        timeoutMs: Long = 8000,
        accept: (Map<String, Any?>) -> Boolean = { true },
    ): Map<String, Any?> {
        val id = nextId.getAndIncrement()
        val q = ArrayBlockingQueue<Map<String, Any?>>(8)
        pending[id] = q
        try {
            send(ns, dest, payload + ("requestId" to id))
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw SocketTimeoutException("la TV non ha risposto a ${payload["type"]}")
                val r = q.poll(minOf(left, 500L), TimeUnit.MILLISECONDS) ?: continue
                if (r.str("type") == CLOSED) throw IOException("collegamento con la TV interrotto")
                if (accept(r)) return r
            }
        } finally {
            pending.remove(id)
        }
    }

    private fun readLoop(input: DataInputStream) {
        try {
            while (!closed) {
                val len = input.readInt()
                if (len <= 0 || len > (4 shl 20)) throw IOException("messaggio Cast non valido ($len byte)")
                val buf = ByteArray(len)
                input.readFully(buf)
                lastActivity = System.currentTimeMillis()
                val m = CastProto.decode(buf)
                val json = m.payload?.let { Json.parseObject(it) } ?: continue
                val type = json.str("type")
                if (m.namespace == NS_HEARTBEAT && type == "PING") {
                    try { send(NS_HEARTBEAT, m.sourceId, mapOf("type" to "PONG")) } catch (_: Exception) { }
                }
                if (m.namespace == NS_CONNECTION && type == "CLOSE") {
                    Log.i(tag, "la TV ha chiuso il collegamento (${m.sourceId})")
                    onTransportClosed?.invoke(m.sourceId)
                    if (m.sourceId == RECEIVER) close()
                }
                val rid = (json["requestId"] as? Number)?.toInt() ?: 0
                if (rid != 0) pending[rid]?.offer(json)
                try { listener?.invoke(m.namespace, json) } catch (e: Exception) { Log.w(tag, "listener: ${e.message}") }
            }
        } catch (e: Exception) {
            if (!closed) Log.w(tag, "canale interrotto: ${e.message}")
        } finally {
            close()
        }
    }

    private fun heartbeatLoop() {
        while (!closed) {
            try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            if (closed) break
            if (System.currentTimeMillis() - lastActivity > 20_000) {
                Log.w(tag, "la TV non risponde da 20 s: chiudo il canale")
                close()
                break
            }
            try { send(NS_HEARTBEAT, RECEIVER, mapOf("type" to "PING")) } catch (_: Exception) {
                close()
                break
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { socket?.close() } catch (_: Exception) { }
        for (q in pending.values) q.offer(mapOf("type" to CLOSED))
    }
}

/**
 * TV Chromecast / Google TV: usa il "ricevitore multimediale predefinito" di Google (app CC1AD845),
 * che legge da solo MP4 e HLS dall'URL del proxy del telefono.
 */
class CastPlayer(override val renderer: Renderer) : TvPlayer {
    companion object {
        const val APP_ID = "CC1AD845"
    }

    private val tag = "Cast"
    private var ch: CastChannel? = null
    @Volatile private var transportId: String? = null
    @Volatile private var mediaSessionId: Int? = null
    private var appSessionId: String? = null
    private var lastDuration = -1

    override val hasVolume = true
    override val startsAtOffset = true

    private fun channel(): CastChannel {
        ch?.let { if (!it.closed) return it }
        val c = CastChannel(renderer.host, if (renderer.port > 0) renderer.port else 8009)
        c.onTransportClosed = { src ->
            if (src == transportId) {
                transportId = null
                mediaSessionId = null
            }
        }
        c.connect()
        ch = c
        transportId = null
        return c
    }

    @Suppress("UNCHECKED_CAST")
    private fun findApp(status: Map<String, Any?>): Map<String, Any?>? =
        status.obj("status")?.list("applications")?.mapNotNull { it as? Map<String, Any?> }?.firstOrNull { it.str("appId") == APP_ID }

    @Suppress("UNCHECKED_CAST")
    private fun firstStatus(msg: Map<String, Any?>): Map<String, Any?>? = msg.list("status")?.firstOrNull() as? Map<String, Any?>

    /** Collega (e se serve avvia) il ricevitore; null se non è in esecuzione e [launch] è falso. */
    private fun ensureApp(launch: Boolean): Pair<CastChannel, String>? {
        val c = channel()
        transportId?.let { return c to it }
        var app = findApp(c.request(CastChannel.NS_RECEIVER, CastChannel.RECEIVER, mapOf("type" to "GET_STATUS")))
        if (app == null) {
            if (!launch) return null
            Log.i(tag, "avvio il ricevitore multimediale sulla TV")
            val r = c.request(CastChannel.NS_RECEIVER, CastChannel.RECEIVER, mapOf("type" to "LAUNCH", "appId" to APP_ID), 20_000)
            if (r.str("type") == "LAUNCH_ERROR") throw CastException(-1, "La TV non ha avviato il ricevitore (${r.str("reason") ?: "errore"})")
            app = findApp(r)
            var tries = 0
            while (app == null && tries++ < 15) {
                Thread.sleep(1000)
                app = findApp(c.request(CastChannel.NS_RECEIVER, CastChannel.RECEIVER, mapOf("type" to "GET_STATUS")))
            }
            if (app == null) throw CastException(-1, "Il ricevitore della TV non è partito")
        }
        val tid = app.str("transportId") ?: throw CastException(-1, "Risposta della TV incompleta")
        appSessionId = app.str("sessionId")
        c.send(CastChannel.NS_CONNECTION, tid, CastChannel.CONNECT)
        transportId = tid
        return c to tid
    }

    override fun beforeLoad() = Unit

    override fun load(url: String, mime: String, title: String, meta: LoadMeta) {
        val media = LinkedHashMap<String, Any?>()
        media["contentId"] = url
        media["contentUrl"] = url
        media["streamType"] = if (meta.live) "LIVE" else "BUFFERED"
        media["contentType"] = mime
        media["metadata"] = mapOf("type" to 0, "metadataType" to 0, "title" to title)
        meta.hlsSegmentFormat?.let { media["hlsSegmentFormat"] = it }
        meta.hlsVideoSegmentFormat?.let { media["hlsVideoSegmentFormat"] = it }
        if (meta.durationSec > 0) media["duration"] = meta.durationSec.toDouble()
        lastDuration = if (meta.durationSec > 0) meta.durationSec else -1
        mediaSessionId = null
        for (round in 0..1) {
            // ricontrollo sempre il ricevitore: sulla TV potrebbe essere stata aperta un'altra app nel frattempo
            transportId = null
            val (c, tid) = ensureApp(launch = true) ?: throw CastException(-1, "Ricevitore non disponibile")
            val payload = mapOf(
                "type" to "LOAD",
                "sessionId" to appSessionId,
                "media" to media,
                "autoplay" to true,
                "currentTime" to meta.startSec.toDouble(),
            )
            val r = try {
                c.request(CastChannel.NS_MEDIA, tid, payload, 30_000)
            } catch (e: IOException) {
                if (round == 0) {
                    Log.w(tag, "LOAD senza risposta (${e.message}): riprovo")
                    continue
                }
                throw e
            }
            if (r.str("type") == "MEDIA_STATUS") {
                mediaSessionId = firstStatus(r)?.num("mediaSessionId")?.toInt()
                return
            }
            if (r.str("type") == "INVALID_REQUEST" && round == 0) {
                Log.w(tag, "ricevitore non più valido (${r.str("reason")}): lo riavvio")
                continue
            }
            val why = listOfNotNull(r.str("type"), r.str("reason"), r.str("detailedErrorCode")?.let { "codice $it" }).joinToString(", ")
            throw CastException(-2, "Il Chromecast ha rifiutato il video ($why)")
        }
    }

    override fun state(): TvState {
        val (c, tid) = ensureApp(launch = false) ?: return TvState("STOPPED", 0, lastDuration)
        val r = c.request(CastChannel.NS_MEDIA, tid, mapOf("type" to "GET_STATUS"), 6000)
        if (r.str("type") == "INVALID_REQUEST") {
            // il ricevitore non c'è più (es. sulla TV è stata aperta un'altra app)
            transportId = null
            mediaSessionId = null
            return TvState("STOPPED", 0, lastDuration)
        }
        val st = firstStatus(r) ?: return TvState("NO_MEDIA_PRESENT", 0, lastDuration)
        st.num("mediaSessionId")?.toInt()?.let { mediaSessionId = it }
        val dur = st.obj("media")?.num("duration")?.toInt()
        if (dur != null && dur > 0) lastDuration = dur
        val pos = st.num("currentTime")?.toInt() ?: 0
        val state = when (st.str("playerState")) {
            "PLAYING" -> "PLAYING"
            "PAUSED" -> "PAUSED_PLAYBACK"
            "BUFFERING", "LOADING" -> "TRANSITIONING"
            else -> if (st.str("idleReason") == "ERROR") "ERROR" else "STOPPED"
        }
        return TvState(state, pos, lastDuration)
    }

    private fun mediaCmd(type: String, extra: Map<String, Any?> = emptyMap()) {
        for (round in 0..1) {
            val (c, tid) = ensureApp(launch = false) ?: throw CastException(-1, "Nessun video in onda sulla TV")
            val msid = mediaSessionId
                ?: firstStatus(c.request(CastChannel.NS_MEDIA, tid, mapOf("type" to "GET_STATUS")))?.num("mediaSessionId")?.toInt()
                ?: throw CastException(-1, "Nessun video in onda sulla TV")
            mediaSessionId = msid
            val r = c.request(CastChannel.NS_MEDIA, tid, mapOf("type" to type, "mediaSessionId" to msid) + extra)
            if (r.str("type") == "MEDIA_STATUS") return
            if (r.str("type") == "INVALID_REQUEST" && round == 0) {
                // sessione cambiata o ricevitore riavviato: rileggo lo stato e riprovo una volta
                mediaSessionId = null
                transportId = null
                continue
            }
            throw CastException(-1, "La TV ha rifiutato il comando (${r.str("reason") ?: r.str("type")})")
        }
    }

    override fun play() = mediaCmd("PLAY")
    override fun pause() = mediaCmd("PAUSE")
    override fun seek(sec: Int) = mediaCmd("SEEK", mapOf("currentTime" to sec.toDouble()))

    override fun stop() {
        try { mediaCmd("STOP") } catch (_: Exception) { }
        val sid = appSessionId
        val c = ch
        if (sid != null && c != null && !c.closed) {
            try {
                c.request(CastChannel.NS_RECEIVER, CastChannel.RECEIVER, mapOf("type" to "STOP", "sessionId" to sid), 5000)
            } catch (_: Exception) { }
        }
        transportId = null
        mediaSessionId = null
        appSessionId = null
    }

    private fun receiverVolume(): Map<String, Any?>? =
        channel().request(CastChannel.NS_RECEIVER, CastChannel.RECEIVER, mapOf("type" to "GET_STATUS")).obj("status")?.obj("volume")

    override fun volume(): Int? = receiverVolume()?.num("level")?.let { Math.round(it * 100).toInt() }
    override fun mute(): Boolean? = receiverVolume()?.get("muted") as? Boolean

    override fun setVolume(v: Int) {
        channel().request(CastChannel.NS_RECEIVER, CastChannel.RECEIVER, mapOf("type" to "SET_VOLUME", "volume" to mapOf("level" to v.coerceIn(0, 100) / 100.0)))
    }

    override fun setMute(m: Boolean) {
        channel().request(CastChannel.NS_RECEIVER, CastChannel.RECEIVER, mapOf("type" to "SET_VOLUME", "volume" to mapOf("muted" to m)))
    }

    override fun close() {
        ch?.close()
        ch = null
        transportId = null
    }
}

/** Scoperta dei dispositivi Chromecast con mDNS (_googlecast._tcp), senza librerie esterne. */
object Mdns {
    private const val TAG = "mDNS"
    const val GROUP = "224.0.0.251"
    const val PORT = 5353
    const val CAST_SERVICE = "_googlecast._tcp.local"

    /** Domanda PTR con il bit "risposta unicast" (QU): la risposta arriva direttamente a noi. */
    fun query(service: String = CAST_SERVICE): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in service.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            o.write(b.size)
            o.write(b)
        }
        o.write(0)
        o.write(0)
        o.write(12) // PTR
        o.write(0x80) // QU
        o.write(1) // IN
        return o.toByteArray()
    }

    private fun u16(p: ByteArray, i: Int) = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)

    private fun readName(p: ByteArray, start: Int, len: Int): Pair<String, Int> {
        val labels = ArrayList<String>()
        var off = start
        var end = -1
        var jumps = 0
        while (off < len) {
            val l = p[off].toInt() and 0xFF
            if (l == 0) {
                off++
                break
            }
            if ((l and 0xC0) == 0xC0) {
                if (off + 1 >= len) break
                val ptr = ((l and 0x3F) shl 8) or (p[off + 1].toInt() and 0xFF)
                if (end < 0) end = off + 2
                off = ptr
                if (++jumps > 30) break
                continue
            }
            if (off + 1 + l > len) break
            labels += String(p, off + 1, l, Charsets.UTF_8)
            off += 1 + l
        }
        return labels.joinToString(".") to (if (end >= 0) end else off)
    }

    private class Rr(val name: String, val type: Int, val data: Int, val dataLen: Int)

    /** Estrae i dispositivi Cast da un pacchetto di risposta mDNS. */
    fun parseCast(p: ByteArray, len: Int, from: InetAddress?): List<Renderer> {
        if (len < 12) return emptyList()
        val qd = u16(p, 4)
        val total = u16(p, 6) + u16(p, 8) + u16(p, 10)
        var off = 12
        try {
            repeat(qd) { off = readName(p, off, len).second + 4 }
            val rrs = ArrayList<Rr>()
            for (k in 0 until total) {
                if (off >= len) break
                val (name, o2) = readName(p, off, len)
                if (o2 + 10 > len) break
                val type = u16(p, o2)
                val dl = u16(p, o2 + 8)
                rrs += Rr(name.lowercase(), type, o2 + 10, dl)
                off = o2 + 10 + dl
            }
            val instances = LinkedHashSet<String>()
            for (r in rrs) {
                if (r.type == 12 && r.name == CAST_SERVICE) instances += readName(p, r.data, len).first
                if (r.type == 33 && r.name.endsWith("." + CAST_SERVICE)) instances += r.name
            }
            val out = ArrayList<Renderer>()
            for (inst in instances) {
                val key = inst.lowercase()
                val srv = rrs.firstOrNull { it.type == 33 && it.name == key }
                val port = srv?.let { u16(p, it.data + 4) } ?: 8009
                val target = srv?.let { readName(p, it.data + 6, len).first.lowercase() }
                val txt = HashMap<String, String>()
                rrs.firstOrNull { it.type == 16 && it.name == key }?.let { t ->
                    var i = t.data
                    while (i < t.data + t.dataLen && i < len) {
                        val l = p[i].toInt() and 0xFF
                        val s = String(p, i + 1, minOf(l, len - i - 1), Charsets.UTF_8)
                        val eq = s.indexOf('=')
                        if (eq > 0) txt[s.substring(0, eq).lowercase()] = s.substring(eq + 1)
                        i += 1 + l
                    }
                }
                val ip = rrs.firstOrNull { it.type == 1 && it.name == target && it.dataLen == 4 }?.let {
                    InetAddress.getByAddress(p.copyOfRange(it.data, it.data + 4)).hostAddress
                } ?: (from as? Inet4Address)?.hostAddress ?: continue
                val id = txt["id"] ?: inst.substringBefore("._googlecast")
                val name = txt["fn"]?.takeIf { it.isNotBlank() } ?: inst.substringBefore("._googlecast").substringBeforeLast('-')
                out += Renderer.cast(id, name, txt["md"] ?: "", ip, port)
            }
            return out
        } catch (e: Exception) {
            Log.d(TAG, "pacchetto non leggibile: ${e.message}")
            return emptyList()
        }
    }

    /** Cerca dispositivi Cast in multicast e/o interrogando direttamente gli host indicati. */
    fun searchCast(
        local: Inet4Address?,
        timeoutMs: Int,
        unicastHosts: Collection<String> = emptyList(),
        multicast: Boolean = true,
        port: Int = PORT,
        cancelled: () -> Boolean = { false },
        onFound: (Renderer) -> Unit,
    ) {
        val seen = HashSet<String>()
        val socket = MulticastSocket(null as SocketAddress?)
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(local ?: InetAddress.getByName("0.0.0.0"), 0))
            socket.timeToLive = 255
            if (local != null && multicast) {
                try {
                    NetworkInterface.getByInetAddress(local)?.let { socket.networkInterface = it }
                } catch (e: Exception) {
                    Log.w(TAG, "interfaccia multicast non impostata: ${e.message}")
                }
            }
            socket.soTimeout = 250
            val q = query()
            val group = InetAddress.getByName(GROUP)
            fun sendAll() {
                if (multicast) send(socket, q, group, PORT)
                for (h in unicastHosts) {
                    try { send(socket, q, InetAddress.getByName(h), port) } catch (_: Exception) { }
                }
            }
            sendAll()
            val start = System.currentTimeMillis()
            var resends = 0
            val buf = ByteArray(9000)
            while (System.currentTimeMillis() - start < timeoutMs && !cancelled()) {
                val elapsed = System.currentTimeMillis() - start
                if ((resends == 0 && elapsed > 800) || (resends == 1 && elapsed > 2000)) {
                    resends++
                    sendAll()
                }
                val pk = DatagramPacket(buf, buf.size)
                try { socket.receive(pk) } catch (_: SocketTimeoutException) { continue }
                for (r in parseCast(pk.data, pk.length, pk.address)) {
                    if (seen.add(r.udn)) {
                        Log.d(TAG, "Chromecast: ${r.name} (${r.model}) su ${r.host}:${r.port}")
                        onFound(r)
                    }
                }
            }
        } finally {
            socket.close()
        }
    }

    private fun send(s: MulticastSocket, data: ByteArray, addr: InetAddress, port: Int) {
        try {
            s.send(DatagramPacket(data, data.size, addr, port))
        } catch (e: Exception) {
            Log.d(TAG, "invio a ${addr.hostAddress} fallito: ${e.message}")
        }
    }
}

/** Verifica diretta di un IP inserito a mano: risponde come un Chromecast sulla porta 8009? */
object CastProbe {
    fun probe(host: String, port: Int = 8009, timeoutMs: Int = 2500): Renderer? = try {
        CastChannel(host, port).use { ch ->
            ch.connect(timeoutMs)
            val st = ch.request(CastChannel.NS_RECEIVER, CastChannel.RECEIVER, mapOf("type" to "GET_STATUS"), 4000)
            if (st.str("type") != "RECEIVER_STATUS") null else Renderer.cast("ip-$host", name(host) ?: "TV Chromecast ($host)", "", host, port)
        }
    } catch (e: Exception) {
        Log.d("Cast", "nessun Chromecast su $host: ${e.message}")
        null
    }

    private fun name(host: String): String? = try {
        val (t, _) = Http.getText("http://$host:8008/setup/eureka_info?params=name", null, timeoutMs = 2500)
        Json.parseObject(t)?.str("name")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }
}
