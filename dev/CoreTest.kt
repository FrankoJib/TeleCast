package test

import it.telecast.core.CastController
import it.telecast.core.CastStatus
import it.telecast.core.Didl
import it.telecast.core.DlnaTime
import it.telecast.core.Hls
import it.telecast.core.Log
import it.telecast.core.MediaDetector
import it.telecast.core.MediaKind
import it.telecast.core.MediaSource
import it.telecast.core.Mode
import it.telecast.core.PlayState
import it.telecast.core.Ssdp
import it.telecast.core.StreamServer
import it.telecast.core.TsUtil
import it.telecast.core.Upnp
import it.telecast.core.Xml
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

var failures = 0
var passed = 0

fun check(name: String, cond: Boolean, detail: () -> String = { "" }) {
    if (cond) {
        passed++
        println("  OK   $name")
    } else {
        failures++
        println("  FAIL $name  ${detail()}")
    }
}

fun get(url: String): String = URL(url).readText()

fun post(url: String, body: String) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.doOutput = true
    c.outputStream.use { it.write(body.toByteArray()) }
    c.responseCode
    c.disconnect()
}

fun jstr(json: String, key: String): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

fun main() {
    System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
    Log.platform = { l, t, m, e -> if (l != 'D') println("       · $l/$t: $m" + (e?.let { " — $it" } ?: "")) }
    unit()
    integration()
    println()
    println(if (failures == 0) "TUTTI I TEST OK ($passed)" else "FALLITI: $failures su ${passed + failures}")
    exitProcess(if (failures == 0) 0 else 1)
}

fun unit() {
    println("== Unit")
    val a = Hls.attributes("BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS=\"avc1.64001f,mp4a.40.2\",AUDIO=\"aud\"")
    check("attributi HLS con virgole tra virgolette", a["BANDWIDTH"] == "2500000" && a["CODECS"] == "avc1.64001f,mp4a.40.2" && a["AUDIO"] == "aud" && a["RESOLUTION"] == "1280x720") { a.toString() }
    val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n360/index.m3u8\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080\nhttps://cdn.x/1080/index.m3u8?t=1\n" +
        "#EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160\n4k.m3u8\n"
    val m = Hls.parseMaster(master, "https://site.x/v/master.m3u8?tok=9")
    check("3 varianti", m.variants.size == 3)
    check("URL relativo risolto", m.variants[0].uri == "https://site.x/v/360/index.m3u8") { m.variants[0].uri }
    check("sceglie 1080p, non il 4K", Hls.pickVariant(m)?.height == 1080)
    val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:10\n#EXTINF:6.0,\na.ts\n#EXTINF:5.5,\nb.ts\n#EXTINF:6,\nc.ts\n#EXT-X-ENDLIST\n"
    val pl = Hls.parsePlaylist(media, "https://cdn.x/p/index.m3u8")
    check("segmenti e numerazione", pl.segments.size == 3 && pl.segments[1].seq == 11L && pl.endList)
    check("durata totale", Math.abs(pl.totalDuration - 17.5) < 0.001)
    check("indexAt", pl.indexAt(0.0) == 0 && pl.indexAt(6.0) == 1 && pl.indexAt(11.4) == 1 && pl.indexAt(11.6) == 2 && pl.indexAt(999.0) == 2)
    val rw = Hls.rewrite(master, "https://site.x/v/master.m3u8", 1) { u, t -> "P[$t:$u]" }
    check("riscrittura: resta solo la variante scelta", rw.contains("P[PLAYLIST:https://cdn.x/1080/index.m3u8?t=1]") && !rw.contains("360") && !rw.contains("4k")) { rw }
    val aes = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k.bin\",IV=0x1\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\ns1.m4s\n"
    val rw2 = Hls.rewrite(aes, "https://c.x/a/", null) { u, t -> "${t.name}|$u" }
    check("riscrittura chiave, MAP e segmenti", rw2.contains("URI=\"KEY|https://c.x/a/k.bin\"") && rw2.contains("URI=\"MAP|https://c.x/a/init.mp4\"") && rw2.contains("SEGMENT|https://c.x/a/s1.m4s")) { rw2 }
    check("cifratura rilevata", Hls.parsePlaylist(aes, "https://c.x/a/").encrypted)
    check("tempi DLNA", DlnaTime.format(3725) == "01:02:05" && DlnaTime.parse("1:02:05") == 3725 && DlnaTime.parse("00:00:07.500") == 7 && DlnaTime.parse("NOT_IMPLEMENTED") == -1)
    val didl = Didl.build("http://1.2.3.4:8765/v/abc/video.mp4", "Film & \"prova\" <1>", "video/mp4", true, 1234, 60)
    check("DIDL-Lite è XML valido", try { Xml.parse(didl.toByteArray()); true } catch (e: Exception) { false }) { didl }
    check("rilevatore: m3u8", MediaDetector.classify("https://x.y/hls/master.m3u8?token=1") == MediaKind.HLS)
    check("rilevatore: mp4", MediaDetector.classify("https://x.y/film.mp4") == MediaKind.FILE)
    check("rilevatore: segmento mp4 ignorato", MediaDetector.classify("https://x.y/v/seg-12.mp4") == null)
    check("rilevatore: .ts ignorato", MediaDetector.classify("https://x.y/v/seg12.ts") == null)
    check("rilevatore: playlist senza estensione è candidata", MediaDetector.isCandidate("https://vix.x/playlist/12345?b=1&token=z"))
    check("rilevatore: immagine non candidata", !MediaDetector.isCandidate("https://x.y/playlist/cover.jpg"))
    check("rilevatore: pubblicità", MediaDetector.isAd("https://imasdk.googleapis.com/x.mp4"))
    val ts = ByteArray(188 * 4) { if (it % 188 == 0) 0x47 else 1 }
    val disguised = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3) + ts
    check("segmento TS travestito riallineato", TsUtil.align(disguised).contentEquals(ts))
    check("SSDP: parsing risposta", Ssdp.parseHeaders("HTTP/1.1 200 OK\r\nLocation: http://a/b.xml\r\nST: x\r\n\r\n")?.get("LOCATION") == "http://a/b.xml")
    val js = it.telecast.core.Json.stringify(mapOf("type" to "LOAD", "n" to 3, "x" to 1.5, "t" to "a\"b\\c\nè", "l" to listOf(true, null)))
    val back = it.telecast.core.Json.parseObject(js)
    check("JSON andata e ritorno", back?.get("t") == "a\"b\\c\nè" && (back["n"] as Number).toInt() == 3 && back["x"] == 1.5 && back["l"] == listOf(true, null)) { js }
    val pm = it.telecast.core.CastProto.decode(it.telecast.core.CastProto.encode("sender-1", "receiver-0", "urn:x-cast:com.google.cast.receiver", "{\"type\":\"PING\"}"))
    check("messaggio Cast (protobuf) andata e ritorno", pm.sourceId == "sender-1" && pm.destinationId == "receiver-0" && pm.payload == "{\"type\":\"PING\"}")
}

fun integration() {
    println("== Integrazione (sito finto + TV LG finta)")
    val origin = "http://127.0.0.1:18080"
    val tv = "http://127.0.0.1:18090"
    val exp = get("$origin/_expected")

    var loc: String? = null
    Ssdp.search(null, 1500, listOf("127.0.0.1"), multicast = false) { loc = it }
    check("SSDP unicast: la TV risponde", loc != null) { "$loc" }
    val r = Upnp.fetchRenderer(loc!!)
    check("descrizione UPnP (con spazi prima di <?xml)", r != null && r.isLg && r.avTransportUrl.endsWith("/AVTransport/control") && r.renderingUrl != null) { "$r" }
    r!!

    val server = StreamServer(18765)
    server.start()
    val cast = CastController(server)
    val history = java.util.concurrent.CopyOnWriteArrayList<CastStatus>()
    cast.listener = { history += it }

    fun waitFor(ms: Long, cond: (CastStatus) -> Boolean): CastStatus? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            val s = cast.status
            if (cond(s)) return s
            Thread.sleep(200)
        }
        return null
    }

    fun src(url: String, kind: MediaKind, referer: String? = "https://site.example/watch/1") =
        MediaSource(url, kind, "https://site.example/watch/1", referer, null, "TestUA/1.0", "Film di prova")

    // --- file MP4
    post("$tv/_reset", "{}")
    cast.cast(r, src("$origin/movie.mp4", MediaKind.FILE), null)
    val s1 = waitFor(30_000) { it.state == PlayState.PLAYING }
    check("MP4: la TV parte in modalità diretta", s1?.mode == Mode.DIRECT) { "${cast.status}" }
    val l1 = get("$tv/_last")
    check("MP4: la TV riceve gli stessi byte del sito", jstr(l1, "sha") == jstr(exp, "movie")) { l1 }
    check("MP4: tipo corretto e seek DLNA abilitato", l1.contains("\"ctype\": \"video/mp4\"") && l1.contains("DLNA.ORG_OP=01")) { l1 }
    check("MP4: HEAD e Range rispettati", l1.contains("\"head\": 200") && l1.contains("\"status\": 206")) { l1 }

    cast.togglePause()
    check("pausa", waitFor(5000) { it.state == PlayState.PAUSED } != null)
    Thread.sleep(1500)
    check("la TV è in pausa", get("$tv/_status").contains("PAUSED_PLAYBACK"))
    cast.togglePause()
    check("ripresa", waitFor(5000) { it.state == PlayState.PLAYING } != null)
    cast.seekTo(20)
    Thread.sleep(1500)
    check("seek inviato alla TV", get("$tv/_actions").contains("00:00:20")) { get("$tv/_actions").takeLast(400) }
    cast.volumeBy(5)
    Thread.sleep(1200)
    check("volume a 25", get("$tv/_status").contains("\"volume\": 25")) { get("$tv/_status") }

    // --- HLS -> flusso unico (compatibilità)
    post("$tv/_reset", "{}")
    cast.cast(r, src("$origin/hls/master.m3u8", MediaKind.HLS), null)
    val s2 = waitFor(30_000) { it.state == PlayState.PLAYING }
    check("HLS: parte in modalità compatibilità a 1080p", s2?.mode == Mode.TS && s2.quality == "1080p") { "${cast.status}" }
    val l2 = get("$tv/_last")
    check("HLS→TS: byte identici ai segmenti (anche quello travestito da PNG)", jstr(l2, "sha") == jstr(exp, "hls1080")) { l2 }
    check("HLS→TS: video/mpeg, chunked, senza seek DLNA", l2.contains("\"ctype\": \"video/mpeg\"") && l2.contains("chunked") && l2.contains("DLNA.ORG_OP=00")) { l2 }
    check("durata nota (30 s)", cast.status.durationSec == 30) { "${cast.status}" }

    cast.seekTo(13)
    val s3 = waitFor(30_000) { it.state == PlayState.PLAYING && get("$tv/_last").contains("/2/stream") }
    check("TS: lo spostamento riapre il flusso dal segmento 2", s3 != null) { get("$tv/_last") }
    check("TS: byte dal segmento 2 in poi", jstr(get("$tv/_last"), "sha") == jstr(exp, "hls1080from2"))
    Thread.sleep(1500)
    check("TS: posizione mostrata >= 12 s", cast.status.positionSec >= 12) { "${cast.status}" }

    // --- la TV rifiuta il flusso unico -> si passa da sola a HLS nativo
    post("$tv/_reset", "{\"reject_mimes\":[\"video/mpeg\"],\"fail_mimes\":[\"video/mp2t\"]}")
    cast.cast(r, src("$origin/hls/master.m3u8", MediaKind.HLS), null)
    val s4 = waitFor(60_000) { it.state == PlayState.PLAYING }
    check("ripiego automatico su HLS nativo", s4?.mode == Mode.HLS) { "${cast.status}" }
    val l4 = get("$tv/_last")
    check("HLS nativo: alla TV arriva una sola variante", l4.contains("\"variant_count\": 1")) { l4 }
    check("HLS nativo: segmenti identici", jstr(l4, "sha") == jstr(exp, "hls1080")) { l4 }

    // --- AES-128: niente flusso unico, la chiave passa dal proxy con gli header giusti
    post("$tv/_reset", "{}")
    cast.cast(r, src("$origin/aes/index.m3u8", MediaKind.HLS), null)
    val s5 = waitFor(30_000) { it.state == PlayState.PLAYING }
    check("AES: va in HLS nativo", s5?.mode == Mode.HLS) { "${cast.status}" }
    val l5 = get("$tv/_last")
    check("AES: la chiave arriva alla TV", jstr(l5, "key") == jstr(exp, "key")) { l5 }
    check("AES: segmenti identici", jstr(l5, "sha") == jstr(exp, "aes")) { l5 }

    // --- sito che rifiuta (manca il Referer) -> messaggio chiaro
    post("$tv/_reset", "{}")
    cast.cast(r, src("$origin/movie.mp4", MediaKind.FILE, referer = null), null)
    val s6 = waitFor(15_000) { it.state == PlayState.ERROR }
    check("403 dal sito → messaggio comprensibile", s6?.message?.contains("403") == true) { "${cast.status}" }

    val log = get("$origin/_log")
    check("User-Agent della pagina inoltrato al sito", log.contains("TestUA/1.0"))

    // --- stop
    post("$tv/_reset", "{}")
    cast.cast(r, src("$origin/movie.mp4", MediaKind.FILE), null)
    waitFor(30_000) { it.state == PlayState.PLAYING }
    cast.stop()
    check("stop", waitFor(5000) { it.state == PlayState.STOPPED } != null)
    check("la TV è ferma", get("$tv/_status").contains("\"state\": \"STOPPED\""))

    castTests(cast, r, origin, tv, exp, ::waitFor, ::src)
    server.stop()
}

fun castTests(
    cast: CastController,
    lg: it.telecast.core.Renderer,
    origin: String,
    tv: String,
    exp: String,
    waitFor: (Long, (CastStatus) -> Boolean) -> CastStatus?,
    src: (String, MediaKind, String?) -> MediaSource,
) {
    println("== Chromecast finto (ok. Google TV)")
    val cc = "http://127.0.0.1:18099"
    var found: it.telecast.core.Renderer? = null
    it.telecast.core.Mdns.searchCast(null, 1500, listOf("127.0.0.1"), multicast = false, port = 15353) { found = it }
    val c = found
    check("mDNS: trovata la ok. Google TV", c != null && c.isCast && c.port == 18009 && c.name == "ok. Google TV (finta)" && c.model == "ok. 43 Google TV") { "$c" }
    c!!

    // MP4
    post("$cc/", "{}")
    cast.cast(c, src("$origin/movie.mp4", MediaKind.FILE, "https://site.example/watch/1"), null)
    val s1 = waitFor(30_000) { it.state == PlayState.PLAYING }
    check("Cast MP4: parte (modalità diretta)", s1?.mode == Mode.DIRECT && s1.tvKind == "Chromecast") { "${cast.status}" }
    val l1 = get("$cc/_last")
    check("Cast MP4: ricevitore avviato, byte identici, tipo video/mp4", jstr(l1, "sha") == jstr(exp, "movie") && l1.contains("\"contentType\": \"video/mp4\"") && get("$cc/").contains("\"app\": true")) { l1 }
    check("Cast: collegato al ricevitore (transport web-7)", get("$cc/").contains("web-7"))

    cast.togglePause()
    Thread.sleep(1500)
    check("Cast: pausa", get("$cc/").contains("\"player\": \"PAUSED\"") && cast.status.state == PlayState.PAUSED) { get("$cc/") }
    cast.togglePause()
    Thread.sleep(1500)
    check("Cast: ripresa", get("$cc/").contains("\"player\": \"PLAYING\""))
    cast.seekTo(12)
    Thread.sleep(1500)
    check("Cast: spostamento a 12 s", get("$cc/").contains("\"type\": \"SEEK\", \"currentTime\": 12")) { get("$cc/") }
    check("Cast: durata letta dalla TV (30 s)", cast.status.durationSec == 30) { "${cast.status}" }
    cast.volumeBy(5)
    Thread.sleep(1500)
    check("Cast: volume 30 → 35", get("$cc/").contains("\"volume\": 0.35")) { get("$cc/") }

    // HLS (master con 3 qualità + segmento travestito)
    post("$cc/", "{}")
    cast.cast(c, src("$origin/hls/master.m3u8", MediaKind.HLS, "https://site.example/watch/1"), null)
    val s2 = waitFor(30_000) { it.state == PlayState.PLAYING }
    check("Cast HLS: parte in HLS nativo", s2?.mode == Mode.HLS) { "${cast.status}" }
    val l2 = get("$cc/_last")
    check("Cast HLS: preflight CORS e intestazioni CORS ok", l2.contains("\"preflight_ok\": true") && l2.contains("\"cors_ok\": true")) { l2 }
    check("Cast HLS: una sola variante, segmenti identici", l2.contains("\"variant_count\": 1") && jstr(l2, "sha") == jstr(exp, "hls1080")) { l2 }
    check("Cast HLS: tipo application/x-mpegurl e formato ts", l2.contains("application/x-mpegurl") && l2.contains("\"hlsSegmentFormat\": \"ts\"")) { l2 }

    // HLS cifrato
    post("$cc/", "{}")
    cast.cast(c, src("$origin/aes/index.m3u8", MediaKind.HLS, "https://site.example/watch/1"), null)
    waitFor(30_000) { it.state == PlayState.PLAYING }
    val l3 = get("$cc/_last")
    check("Cast AES: chiave e segmenti arrivano alla TV", jstr(l3, "key") == jstr(exp, "key") && jstr(l3, "sha") == jstr(exp, "aes")) { l3 }

    // Spostamento LG → ok. dal punto in cui si è
    post("$cc/", "{}")
    post("$tv/_reset", "{}")
    cast.cast(lg, src("$origin/hls/master.m3u8", MediaKind.HLS, "https://site.example/watch/1"), null)
    waitFor(30_000) { it.state == PlayState.PLAYING && it.tvKind == "DLNA" }
    cast.seekTo(13)
    waitFor(30_000) { it.state == PlayState.PLAYING && it.positionSec >= 12 }
    Thread.sleep(1200)
    val before = cast.status.positionSec
    cast.moveTo(c)
    val s4 = waitFor(30_000) { it.state == PlayState.PLAYING && it.tvKind == "Chromecast" }
    check("Cambia TV: il film passa dalla LG alla ok.", s4 != null && s4.tvName == "ok. Google TV (finta)") { "${cast.status}" }
    val l4 = get("$cc/_last")
    val startedAt = Regex("\"currentTime\": ([0-9.]+)").find(l4)?.groupValues?.get(1)?.toDouble() ?: -1.0
    check("Cambia TV: riparte dallo stesso punto ($before s)", startedAt >= 12.0 && startedAt <= before + 3) { l4 }
    check("Cambia TV: la LG viene fermata", get("$tv/_status").contains("\"state\": \"STOPPED\"")) { get("$tv/_status") }

    // Stop: chiude il ricevitore e la TV torna a dov'era
    cast.stop()
    Thread.sleep(1500)
    val fin = get("$cc/")
    val stops = Regex("\"stopped_app\": (\\d+)").find(fin)?.groupValues?.get(1)?.toInt() ?: 0
    check("Cast stop: ricevitore chiuso", stops >= 1 && fin.contains("\"app\": false") && fin.contains("\"type\": \"STOP\"")) { fin }
}
