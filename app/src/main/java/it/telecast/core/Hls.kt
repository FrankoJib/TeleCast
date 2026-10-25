package it.telecast.core

import java.net.URI
import java.net.URL

data class HlsVariant(
    val uri: String,
    val bandwidth: Long,
    val width: Int,
    val height: Int,
    val codecs: String?,
    val audioGroup: String?,
) {
    val label: String
        get() = when {
            height > 0 -> "${height}p"
            bandwidth > 0 -> "${bandwidth / 1000} kbps"
            else -> "Auto"
        }
}

data class HlsRendition(
    val type: String,
    val groupId: String,
    val name: String?,
    val language: String?,
    val uri: String?,
    val isDefault: Boolean,
)

data class HlsSegment(val uri: String, val duration: Double, val start: Double, val seq: Long, val encrypted: Boolean)

class HlsMaster(val variants: List<HlsVariant>, val renditions: List<HlsRendition>) {
    /** Vero se l'audio della variante è in una playlist separata (non concatenabile in un unico flusso). */
    fun hasSeparateAudio(v: HlsVariant): Boolean =
        v.audioGroup != null && renditions.any { it.type == "AUDIO" && it.groupId == v.audioGroup && it.uri != null }
}

class HlsPlaylist(
    val segments: List<HlsSegment>,
    val targetDuration: Double,
    val mediaSequence: Long,
    val endList: Boolean,
    val mapUri: String?,
    val encrypted: Boolean,
    val byteRange: Boolean,
) {
    val totalDuration: Double
        get() = segments.lastOrNull()?.let { it.start + it.duration } ?: 0.0

    /** Indice del segmento che contiene il secondo [sec]. */
    fun indexAt(sec: Double): Int {
        if (segments.isEmpty()) return 0
        var lo = 0
        var hi = segments.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (segments[mid].start <= sec) lo = mid else hi = mid - 1
        }
        return lo
    }
}

object Hls {
    enum class RefType { PLAYLIST, SEGMENT, KEY, MAP, OTHER }

    fun isPlaylist(text: String): Boolean = text.trimStart('﻿', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")

    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    fun resolve(base: String, ref: String): String {
        val r = ref.trim()
        return try {
            URL(URL(base), r).toString()
        } catch (_: Exception) {
            try { URI(base).resolve(r.replace(" ", "%20")).toString() } catch (_: Exception) { r }
        }
    }

    /** Attributi tipo KEY=VALUE,KEY2="valore, con virgole". */
    fun attributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && (s[i] == ',' || s[i] == ' ')) i++
            val eq = s.indexOf('=', i)
            if (eq < 0) break
            val key = s.substring(i, eq).trim().uppercase()
            var j = eq + 1
            val value: String
            if (j < n && s[j] == '"') {
                val end = s.indexOf('"', j + 1).let { if (it < 0) n else it }
                value = s.substring(j + 1, end)
                j = end + 1
            } else {
                val end = s.indexOf(',', j).let { if (it < 0) n else it }
                value = s.substring(j, end).trim()
                j = end
            }
            if (key.isNotEmpty()) out[key] = value
            i = j
        }
        return out
    }

    private fun tagValue(line: String): String = line.substringAfter(':', "")

    fun parseMaster(text: String, baseUrl: String): HlsMaster {
        val variants = ArrayList<HlsVariant>()
        val renditions = ArrayList<HlsRendition>()
        var pending: Map<String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> pending = attributes(tagValue(line))
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val a = attributes(tagValue(line))
                    renditions += HlsRendition(
                        type = a["TYPE"] ?: "",
                        groupId = a["GROUP-ID"] ?: "",
                        name = a["NAME"],
                        language = a["LANGUAGE"],
                        uri = a["URI"]?.let { resolve(baseUrl, it) },
                        isDefault = a["DEFAULT"] == "YES",
                    )
                }
                line.startsWith("#") -> Unit
                else -> {
                    val a = pending ?: continue
                    val res = a["RESOLUTION"]?.split('x', 'X')
                    variants += HlsVariant(
                        uri = resolve(baseUrl, line),
                        bandwidth = a["BANDWIDTH"]?.toLongOrNull() ?: a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L,
                        width = res?.getOrNull(0)?.trim()?.toIntOrNull() ?: 0,
                        height = res?.getOrNull(1)?.trim()?.toIntOrNull() ?: 0,
                        codecs = a["CODECS"],
                        audioGroup = a["AUDIO"],
                    )
                    pending = null
                }
            }
        }
        return HlsMaster(variants, renditions)
    }

    fun parsePlaylist(text: String, baseUrl: String): HlsPlaylist {
        val segs = ArrayList<HlsSegment>()
        var target = 0.0
        var mediaSeq = 0L
        var endList = false
        var mapUri: String? = null
        var encrypted = false
        var byteRange = false
        var keyActive = false
        var dur = -1.0
        var t = 0.0
        var nextSeq: Long? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF:") -> dur = tagValue(line).substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = tagValue(line).trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> mediaSeq = tagValue(line).trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-ENDLIST") -> endList = true
                line.startsWith("#EXT-X-BYTERANGE") -> byteRange = true
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(tagValue(line))
                    if (a.containsKey("BYTERANGE")) byteRange = true
                    if (mapUri == null) a["URI"]?.let { mapUri = resolve(baseUrl, it) }
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val m = attributes(tagValue(line))["METHOD"]?.uppercase()
                    keyActive = m != null && m != "NONE"
                    if (keyActive) encrypted = true
                }
                line.startsWith("#") -> Unit
                else -> {
                    val seq = nextSeq ?: mediaSeq
                    val d = if (dur >= 0) dur else target
                    segs += HlsSegment(resolve(baseUrl, line), d, t, seq, keyActive)
                    t += d
                    nextSeq = seq + 1
                    dur = -1.0
                }
            }
        }
        return HlsPlaylist(segs, target, mediaSeq, endList, mapUri, encrypted, byteRange)
    }

    /** Variante consigliata: la migliore fino a [maxHeight] (le TV reggono bene il 1080p). */
    fun pickVariant(m: HlsMaster, maxHeight: Int = 1080): HlsVariant? {
        if (m.variants.isEmpty()) return null
        val ok = m.variants.filter { it.height in 1..maxHeight }
        if (ok.isNotEmpty()) return ok.maxByOrNull { it.height * 1_000_000_000L + it.bandwidth }
        val unknown = m.variants.filter { it.height == 0 }
        if (unknown.isNotEmpty()) return unknown.maxByOrNull { it.bandwidth }
        return m.variants.minByOrNull { it.height }
    }

    private val URI_ATTR = Regex("URI=\"([^\"]*)\"")

    private fun rewriteUriAttr(line: String, base: String, type: RefType, map: (String, RefType) -> String): String =
        URI_ATTR.replace(line) { m -> "URI=\"" + map(resolve(base, m.groupValues[1]), type) + "\"" }

    /**
     * Riscrive tutti gli URI della playlist con [map] (il proxy locale li sostituisce con
     * indirizzi propri). Se [keepVariantIndex] non è null e la playlist è una master, tiene
     * solo quella variante: la TV riceve un'unica qualità, più semplice da gestire.
     */
    fun rewrite(text: String, baseUrl: String, keepVariantIndex: Int?, map: (String, RefType) -> String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val master = isMaster(text)
        val out = ArrayList<String>(lines.size)
        var variantCounter = -1
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#")) {
                when (line.substringBefore(':')) {
                    "#EXT-X-STREAM-INF" -> {
                        variantCounter++
                        var j = i + 1
                        while (j < lines.size && lines[j].startsWith("#")) j++
                        val keep = keepVariantIndex == null || keepVariantIndex == variantCounter
                        if (keep && j < lines.size) {
                            out += line
                            for (k in i + 1 until j) out += lines[k]
                            out += map(resolve(baseUrl, lines[j]), RefType.PLAYLIST)
                        }
                        i = j + 1
                        continue
                    }
                    "#EXT-X-I-FRAME-STREAM-INF" -> {
                        if (keepVariantIndex == null) out += rewriteUriAttr(line, baseUrl, RefType.PLAYLIST, map)
                    }
                    "#EXT-X-MEDIA", "#EXT-X-RENDITION-REPORT" -> out += rewriteUriAttr(line, baseUrl, RefType.PLAYLIST, map)
                    "#EXT-X-KEY", "#EXT-X-SESSION-KEY" -> out += rewriteUriAttr(line, baseUrl, RefType.KEY, map)
                    "#EXT-X-MAP" -> out += rewriteUriAttr(line, baseUrl, RefType.MAP, map)
                    "#EXT-X-PART", "#EXT-X-PRELOAD-HINT" -> out += rewriteUriAttr(line, baseUrl, RefType.SEGMENT, map)
                    else -> out += line
                }
            } else {
                out += map(resolve(baseUrl, line), if (master) RefType.PLAYLIST else RefType.SEGMENT)
            }
            i++
        }
        return out.joinToString("\n", postfix = "\n")
    }
}

object TsUtil {
    private const val SYNC: Byte = 0x47

    /**
     * Alcuni siti "travestono" i segmenti TS (es. con un'intestazione PNG davanti).
     * Se il segmento non inizia con il byte di sync MPEG-TS, cerca il primo punto
     * in cui la sequenza 0x47 ogni 188 byte è valida e taglia lì.
     */
    fun align(b: ByteArray): ByteArray {
        if (b.size < 188 * 3) return b
        if (b[0] == SYNC && b[188] == SYNC) return b
        val limit = minOf(b.size - 188 * 2, 256 * 1024)
        for (i in 0 until limit) {
            if (b[i] == SYNC && b[i + 188] == SYNC && b[i + 376] == SYNC) return b.copyOfRange(i, b.size)
        }
        return b
    }
}
