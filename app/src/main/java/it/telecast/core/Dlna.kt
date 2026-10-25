package it.telecast.core

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class DlnaException(code: Int, message: String) : TvException(code, message)

object DlnaTime {
    fun format(sec: Int): String {
        val s = maxOf(0, sec)
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    /** "1:02:03", "01:02:03.000" -> secondi; -1 se assente o NOT_IMPLEMENTED. */
    fun parse(v: String?): Int {
        if (v.isNullOrBlank()) return -1
        val t = v.trim()
        if (!t[0].isDigit()) return -1
        var acc = 0
        for (p in t.substringBefore('.').split(':')) {
            val n = p.trim().toIntOrNull() ?: return -1
            acc = acc * 60 + n
        }
        return acc
    }
}

/** Metadati DIDL-Lite: la "scheda" del video che si passa alla TV insieme all'URL. */
object Didl {
    const val FLAGS = "01700000000000000000000000000000"

    fun features(seekable: Boolean): String =
        "DLNA.ORG_OP=${if (seekable) "01" else "00"};DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$FLAGS"

    fun build(url: String, title: String, mime: String, seekable: Boolean, size: Long = -1, durationSec: Int = -1): String {
        val cls = if (mime.startsWith("audio")) "object.item.audioItem.musicTrack" else "object.item.videoItem.movie"
        val attrs = StringBuilder()
        if (size > 0) attrs.append(" size=\"").append(size).append('"')
        if (durationSec > 0) attrs.append(" duration=\"").append(DlnaTime.format(durationSec)).append(".000\"")
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" " +
            "xmlns:sec=\"http://www.sec.co.kr/\" xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">" +
            "<item id=\"1000\" parentID=\"0\" restricted=\"1\">" +
            "<dc:title>${Xml.esc(title)}</dc:title>" +
            "<upnp:class>$cls</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:${features(seekable)}\"$attrs>${Xml.esc(url)}</res>" +
            "</item></DIDL-Lite>"
    }
}

/** Comandi SOAP verso la TV (AVTransport per la riproduzione, RenderingControl per il volume). */
class DlnaClient(val renderer: Renderer) {
    private val tag = "DLNA"

    fun call(controlUrl: String, serviceType: String, action: String, args: List<Pair<String, String>>, timeoutMs: Int = 7000): Map<String, String> {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(serviceType).append("\">")
            for ((k, v) in args) append('<').append(k).append('>').append(Xml.esc(v)).append("</").append(k).append('>')
            append("</u:").append(action).append("></s:Body></s:Envelope>")
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val c = URL(controlUrl).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            c.setRequestProperty("SOAPAction", "\"$serviceType#$action\"")
            c.setRequestProperty("User-Agent", "Android/1 UPnP/1.0 DLNADOC/1.50 TeleCast/1.0")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val resp = stream?.use { Http.readLimited(it, 1 shl 20) } ?: ByteArray(0)
            if (code !in 200..299) {
                val (ec, ed) = parseFault(resp)
                val msg = "$action: ${ed ?: "HTTP $code"}" + (ec?.let { " (UPnP $it)" } ?: "")
                throw DlnaException(ec ?: code, msg)
            }
            return parseResponse(resp)
        } finally {
            c.disconnect()
        }
    }

    private fun parseResponse(bytes: ByteArray): Map<String, String> {
        if (bytes.isEmpty()) return emptyMap()
        return try {
            val root = Xml.parse(bytes).documentElement
            val body = Xml.find(root, "Body") ?: root
            val resp = Xml.childElements(body).firstOrNull() ?: return emptyMap()
            Xml.childElements(resp).associate { Xml.local(it) to (it.textContent ?: "").trim() }
        } catch (e: Exception) {
            Log.w(tag, "risposta SOAP illeggibile: ${e.message}")
            emptyMap()
        }
    }

    private fun parseFault(bytes: ByteArray): Pair<Int?, String?> = try {
        val root = Xml.parse(bytes).documentElement
        val code = Xml.find(root, "errorCode")?.textContent?.trim()?.toIntOrNull()
        val desc = Xml.find(root, "errorDescription")?.textContent?.trim()
            ?: Xml.find(root, "faultstring")?.textContent?.trim()
        code to desc
    } catch (_: Exception) {
        null to null
    }

    private fun avt(action: String, vararg args: Pair<String, String>): Map<String, String> =
        call(renderer.avTransportUrl, renderer.avTransportType, action, listOf("InstanceID" to "0") + args)

    private fun rcs(action: String, vararg args: Pair<String, String>): Map<String, String> {
        val url = renderer.renderingUrl ?: throw DlnaException(-1, "La TV non espone il controllo del volume")
        return call(url, renderer.renderingType ?: Upnp.RCS, action, listOf("InstanceID" to "0") + args)
    }

    fun setUri(url: String, metadata: String) {
        avt("SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to metadata)
    }

    fun play() { avt("Play", "Speed" to "1") }
    fun pause() { avt("Pause") }
    fun stop() { avt("Stop") }
    fun seek(sec: Int) { avt("Seek", "Unit" to "REL_TIME", "Target" to DlnaTime.format(sec)) }

    fun transportState(): String = avt("GetTransportInfo")["CurrentTransportState"]?.uppercase(Locale.ROOT) ?: "UNKNOWN"

    /** (posizione, durata) in secondi; -1 se la TV non li conosce. */
    fun position(): Pair<Int, Int> {
        val m = avt("GetPositionInfo")
        return DlnaTime.parse(m["RelTime"]) to DlnaTime.parse(m["TrackDuration"])
    }

    fun volume(): Int? = rcs("GetVolume", "Channel" to "Master")["CurrentVolume"]?.trim()?.toIntOrNull()
    fun setVolume(v: Int) { rcs("SetVolume", "Channel" to "Master", "DesiredVolume" to v.coerceIn(0, 100).toString()) }
    fun mute(): Boolean? = rcs("GetMute", "Channel" to "Master")["CurrentMute"]?.let { it == "1" || it.equals("true", true) }
    fun setMute(m: Boolean) { rcs("SetMute", "Channel" to "Master", "DesiredMute" to if (m) "1" else "0") }
}
