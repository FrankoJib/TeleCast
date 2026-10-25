package it.telecast.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

enum class Protocol { DLNA, CAST }

internal fun hostOf(location: String): String = try { URL(location).host } catch (_: Exception) { "" }

/** Una TV trovata sulla rete: renderer DLNA (es. LG) oppure dispositivo Chromecast (es. Google TV). */
data class Renderer(
    val udn: String,
    val name: String,
    val manufacturer: String,
    val model: String,
    val location: String,
    val avTransportUrl: String,
    val avTransportType: String,
    val renderingUrl: String?,
    val renderingType: String?,
    val protocol: Protocol = Protocol.DLNA,
    val host: String = hostOf(location),
    val port: Int = 0,
) {
    val isCast: Boolean get() = protocol == Protocol.CAST
    val isLg: Boolean
        get() = manufacturer.contains("LG", true) || name.contains("LG", true) || name.contains("webos", true)
    val kindLabel: String get() = if (isCast) "Chromecast" else "DLNA"

    companion object {
        fun cast(id: String, name: String, model: String, host: String, port: Int = 8009) = Renderer(
            udn = "cast:$id",
            name = name,
            manufacturer = "Google Cast",
            model = model,
            location = "cast://$host:$port",
            avTransportUrl = "",
            avTransportType = "",
            renderingUrl = null,
            renderingType = null,
            protocol = Protocol.CAST,
            host = host,
            port = port,
        )
    }
}

object Xml {
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> if (c.code >= 0x20 || c == '\t' || c == '\n' || c == '\r') sb.append(c)
            }
        }
        return sb.toString()
    }

    fun parse(bytes: ByteArray): Document {
        var start = 0
        // alcune TV mettono spazi o righe vuote prima di <?xml ...?>: il parser li rifiuterebbe
        while (start < bytes.size && (bytes[start] == ' '.code.toByte() || bytes[start] == '\n'.code.toByte() ||
                bytes[start] == '\r'.code.toByte() || bytes[start] == '\t'.code.toByte())
        ) start++
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
        try { f.isExpandEntityReferences = false } catch (_: Exception) { }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes, start, bytes.size - start))
    }

    fun local(n: Node): String = n.nodeName.substringAfter(':')

    fun childElements(e: Element): List<Element> {
        val out = ArrayList<Element>()
        var c = e.firstChild
        while (c != null) {
            if (c is Element) out += c
            c = c.nextSibling
        }
        return out
    }

    fun children(e: Element, name: String): List<Element> = childElements(e).filter { local(it).equals(name, true) }
    fun child(e: Element, name: String): Element? = children(e, name).firstOrNull()
    fun text(e: Element?, name: String): String = e?.let { child(it, name)?.textContent?.trim() } ?: ""

    /** Primo elemento discendente con quel nome locale (ignora i prefissi s:, u: ...). */
    fun find(e: Element, name: String): Element? {
        for (c in childElements(e)) {
            if (local(c).equals(name, true)) return c
            find(c, name)?.let { return it }
        }
        return null
    }
}

object Upnp {
    const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"

    private fun devices(root: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) {
            for (c in Xml.childElements(e)) {
                if (Xml.local(c).equals("device", true)) out += c
                walk(c)
            }
        }
        walk(root)
        return out
    }

    /** Legge la descrizione UPnP e restituisce il renderer solo se espone AVTransport. */
    fun parseDescription(xml: ByteArray, location: String): Renderer? {
        val root = Xml.parse(xml).documentElement
        val base = Xml.text(root, "URLBase").ifEmpty { location }
        val all = devices(root)
        val rootDevice = all.firstOrNull() ?: return null
        for (d in all) {
            val services = Xml.child(d, "serviceList")?.let { Xml.children(it, "service") } ?: continue
            var avt: Pair<String, String>? = null
            var rc: Pair<String, String>? = null
            for (s in services) {
                val type = Xml.text(s, "serviceType")
                val ctrl = Xml.text(s, "controlURL")
                if (ctrl.isEmpty()) continue
                val abs = try { URL(URL(base), ctrl).toString() } catch (_: Exception) { continue }
                if (type.contains(":service:AVTransport:", true)) avt = abs to type
                else if (type.contains(":service:RenderingControl:", true)) rc = abs to type
            }
            if (avt != null) {
                val host = try { URL(location).host } catch (_: Exception) { "" }
                return Renderer(
                    udn = Xml.text(d, "UDN").ifEmpty { location },
                    name = Xml.text(d, "friendlyName").ifEmpty { Xml.text(rootDevice, "friendlyName") }.ifEmpty { "TV $host" },
                    manufacturer = Xml.text(d, "manufacturer").ifEmpty { Xml.text(rootDevice, "manufacturer") },
                    model = Xml.text(d, "modelName").ifEmpty { Xml.text(rootDevice, "modelName") },
                    location = location,
                    avTransportUrl = avt.first,
                    avTransportType = avt.second,
                    renderingUrl = rc?.first,
                    renderingType = rc?.second,
                )
            }
        }
        return null
    }

    fun fetchRenderer(location: String): Renderer? {
        val bytes = Http.open(location, null, connectTimeoutMs = 3000, readTimeoutMs = 4000).use { r ->
            if (r.code !in 200..299) return null
            r.body().use { Http.readLimited(it, 1 shl 20) }
        }
        return parseDescription(bytes, location)
    }
}

/** Scoperta dei dispositivi con SSDP (il "chi c'è?" di UPnP/DLNA). */
object Ssdp {
    private const val TAG = "SSDP"
    const val GROUP = "239.255.255.250"
    const val PORT = 1900
    val TARGETS = listOf("urn:schemas-upnp-org:device:MediaRenderer:1", "urn:schemas-upnp-org:service:AVTransport:1")

    fun searchMessage(st: String, host: String): String =
        "M-SEARCH * HTTP/1.1\r\nHOST: $host\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n" +
            "USER-AGENT: Android/1 UPnP/1.1 TeleCast/1.0\r\n\r\n"

    fun parseHeaders(msg: String): Map<String, String>? {
        val lines = msg.split("\r\n", "\n")
        val first = lines.firstOrNull()?.uppercase(Locale.ROOT) ?: return null
        if (!first.startsWith("HTTP/1.1 200") && !first.startsWith("HTTP/1.0 200") && !first.startsWith("NOTIFY")) return null
        val h = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) h[l.substring(0, i).trim().uppercase(Locale.ROOT)] = l.substring(i + 1).trim()
        }
        return h
    }

    /**
     * Invia M-SEARCH in multicast e/o in unicast verso [unicastHosts] (utile quando il router
     * blocca il multicast) e chiama [onLocation] per ogni nuovo LOCATION. Blocca per [timeoutMs].
     */
    fun search(
        local: Inet4Address?,
        timeoutMs: Int,
        unicastHosts: Collection<String> = emptyList(),
        multicast: Boolean = true,
        cancelled: () -> Boolean = { false },
        onLocation: (String) -> Unit,
    ) {
        val seen = HashSet<String>()
        val socket = MulticastSocket(null as SocketAddress?)
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(local ?: InetAddress.getByName("0.0.0.0"), 0))
            socket.timeToLive = 4
            if (local != null && multicast) {
                try {
                    NetworkInterface.getByInetAddress(local)?.let { socket.networkInterface = it }
                } catch (e: Exception) {
                    Log.w(TAG, "interfaccia multicast non impostata: ${e.message}")
                }
            }
            socket.soTimeout = 250
            val group = InetAddress.getByName(GROUP)
            fun sendAll() {
                if (multicast) for (st in TARGETS) send(socket, searchMessage(st, "$GROUP:$PORT"), group)
                for (h in unicastHosts) {
                    try { send(socket, searchMessage(TARGETS[0], "$h:$PORT"), InetAddress.getByName(h)) } catch (_: Exception) { }
                }
            }
            Log.d(TAG, "ricerca (multicast=$multicast, unicast=${unicastHosts.size} host, ${timeoutMs}ms)")
            sendAll()
            val start = System.currentTimeMillis()
            var resent = false
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() - start < timeoutMs && !cancelled()) {
                if (!resent && System.currentTimeMillis() - start > 800) {
                    resent = true
                    sendAll()
                }
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (_: SocketTimeoutException) { continue }
                val h = parseHeaders(String(p.data, 0, p.length, Charsets.ISO_8859_1)) ?: continue
                val loc = h["LOCATION"] ?: continue
                if (seen.add(loc)) {
                    Log.d(TAG, "risposta da ${p.address.hostAddress}: $loc")
                    onLocation(loc)
                }
            }
        } finally {
            socket.close()
        }
    }

    private fun send(s: DatagramSocket, msg: String, addr: InetAddress) {
        val b = msg.toByteArray(Charsets.ISO_8859_1)
        try {
            s.send(DatagramPacket(b, b.size, addr, PORT))
        } catch (e: Exception) {
            Log.d(TAG, "invio a ${addr.hostAddress} fallito: ${e.message}")
        }
    }
}

object Net {
    /** IPv4 della rete locale (Wi-Fi o cavo), escluse rete mobile e VPN. */
    fun lanIPv4(): Inet4Address? {
        val candidates = ArrayList<Pair<Int, Inet4Address>>()
        val ifaces = try { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() } catch (_: Exception) { emptyList() }
        for (nif in ifaces) {
            try {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual || nif.isPointToPoint) continue
            } catch (_: Exception) {
                continue
            }
            val name = nif.name.lowercase(Locale.ROOT)
            for (a in nif.inetAddresses.toList()) {
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                    val score = when {
                        name.startsWith("wlan") -> 0
                        name.startsWith("eth") -> 1
                        name.startsWith("ap") || name.startsWith("swlan") -> 3
                        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") -> 9
                        else -> 5
                    }
                    candidates += score to a
                }
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }

    /** Indirizzo del telefono visto dalla TV: è quello da mettere negli URL del proxy. */
    fun localAddressFor(host: String): String? = try {
        DatagramSocket().use { s ->
            s.connect(InetAddress.getByName(host), 9)
            s.localAddress?.hostAddress?.takeIf { it != "0.0.0.0" && it != "::" }
        }
    } catch (_: Exception) {
        null
    }

    fun subnetHosts(local: Inet4Address): List<String> {
        val b = local.address
        val prefix = "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}."
        val self = b[3].toInt() and 255
        return (1..254).filter { it != self }.map { prefix + it }
    }
}
