package it.telecast.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

enum class PlayState { IDLE, CONNECTING, BUFFERING, PLAYING, PAUSED, STOPPED, ERROR }

enum class Mode(val label: String) {
    DIRECT("Diretta"),
    TS("Compatibilità"),
    HLS("HLS nativo"),
}

data class CastStatus(
    val state: PlayState = PlayState.IDLE,
    val title: String = "",
    val tvName: String = "",
    val positionSec: Int = 0,
    val durationSec: Int = 0,
    val live: Boolean = false,
    val mode: Mode? = null,
    val attempt: String? = null,
    val message: String? = null,
    val volume: Int? = null,
    val muted: Boolean = false,
    val canSeek: Boolean = false,
    val quality: String? = null,
    val canSwitchMode: Boolean = false,
    val tvKind: String = "",
) {
    val active: Boolean
        get() = state == PlayState.CONNECTING || state == PlayState.BUFFERING ||
            state == PlayState.PLAYING || state == PlayState.PAUSED
}

private class Attempt(val mode: Mode, val mime: String, val hints: Boolean = false)

/**
 * Regia della trasmissione: prepara il video, prova le modalità una dopo l'altra finché
 * la TV non parte, poi interroga la TV ogni secondo per posizione e stato.
 * Funziona con TV DLNA (LG) e Chromecast/Google TV (ok.).
 * Tutto lo stato interno è usato solo dal thread "tc-cast": niente lock.
 */
class CastController(private val server: StreamServer) {
    private val tag = "Cast"
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "tc-cast").apply { isDaemon = true } }
    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tc-poll").apply { isDaemon = true } }
    private val generation = AtomicInteger()

    @Volatile var listener: ((CastStatus) -> Unit)? = null

    @Volatile var status = CastStatus()
        private set

    @Volatile var currentRenderer: Renderer? = null
        private set

    @Volatile private var lastSource: MediaSource? = null
    @Volatile private var lastVariant: Int? = null

    private var player: TvPlayer? = null
    private var session: ServeSession? = null
    private var plan: List<Attempt> = emptyList()
    private var attemptIdx = 0
    private var offsetSec = 0
    private var knownDuration = 0
    private var pollTask: ScheduledFuture<*>? = null
    private var pollFailures = 0
    private var stoppedPolls = 0
    private var pollCount = 0
    private var pausedAt = 0L
    private var seekHoldUntil = 0L
    private var lastError: Exception? = null

    // ---------------- comandi (chiamabili da qualsiasi thread) ----------------

    fun cast(r: Renderer, src: MediaSource, variantIndex: Int?, startSec: Int = 0) {
        val gen = generation.incrementAndGet()
        val wasActive = status.active
        lastSource = src
        lastVariant = variantIndex
        // stato aggiornato subito: l'interfaccia non deve mostrare per un attimo il video precedente
        val st = CastStatus(
            state = PlayState.CONNECTING, title = src.title, tvName = r.name, tvKind = r.kindLabel,
            positionSec = startSec, message = "Preparo il video…",
        )
        status = st
        try { listener?.invoke(st) } catch (_: Exception) { }
        exec.execute { doCast(gen, r, src, variantIndex, startSec, wasActive) }
    }

    /** Sposta il film su un'altra TV, dal punto in cui si è arrivati. */
    fun moveTo(r: Renderer) {
        val src = lastSource ?: return
        Log.i(tag, "sposto il film su ${r.name} da ${Fmt.clock(status.positionSec)}")
        cast(r, src, lastVariant, status.positionSec)
    }

    val canMove: Boolean get() = lastSource != null

    fun togglePause() = exec.execute { doTogglePause() }

    fun stop() {
        generation.incrementAndGet()
        exec.execute { doStop() }
    }

    fun seekTo(sec: Int) {
        if (status.mode == Mode.TS) generation.incrementAndGet()
        exec.execute { doSeek(sec) }
    }

    fun seekBy(delta: Int) = seekTo(status.positionSec + delta)

    fun switchMode() {
        generation.incrementAndGet()
        exec.execute { doSwitchMode() }
    }

    fun volumeBy(delta: Int) = exec.execute { doVolume(delta) }

    fun toggleMute() = exec.execute { doToggleMute() }

    // ---------------- implementazione ----------------

    private fun publish(gen: Int, st: CastStatus) {
        if (gen != generation.get()) return
        status = st
        try { listener?.invoke(st) } catch (e: Exception) { Log.e(tag, "listener", e) }
    }

    private fun newId(): String {
        val chars = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..6).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun doCast(gen: Int, r: Renderer, src: MediaSource, variantIndex: Int?, startSec: Int, wasActive: Boolean) {
        if (gen != generation.get()) return
        stopPolling()
        val previous = player
        val p: TvPlayer
        if (previous != null && previous.renderer == r) {
            p = previous
        } else {
            if (previous != null) {
                if (wasActive && previous.renderer.udn != r.udn) {
                    Log.i(tag, "fermo la trasmissione su ${previous.renderer.name}")
                    try { previous.stop() } catch (_: Exception) { }
                }
                previous.close()
            }
            p = playerFor(r)
        }
        player = p
        currentRenderer = r
        lastError = null
        knownDuration = 0
        offsetSec = 0
        Log.i(tag, "trasmetto \"${src.title}\" su ${r.name} (${r.kindLabel}, ${r.manufacturer} ${r.model}, ${r.host})")
        val s = ServeSession(newId(), src)
        try {
            server.start()
            prepare(s, variantIndex)
        } catch (e: Exception) {
            Log.e(tag, "preparazione non riuscita", e)
            publish(gen, status.copy(state = PlayState.ERROR, message = friendly(e)))
            return
        }
        if (gen != generation.get()) return
        lastVariant = s.variantIndex
        server.register(s)
        session = s
        plan = buildPlan(s, r)
        attemptIdx = 0
        val pl = s.playlist
        knownDuration = if (pl != null && pl.endList) pl.totalDuration.toInt() else 0
        Log.i(tag, "modalità da provare: " + plan.joinToString { "${it.mode.label} (${it.mime})" })
        runAttempts(gen, startSec)
    }

    private fun guessMime(contentType: String, url: String): String {
        val t = contentType.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (t.startsWith("video/")) return t
        return when (MediaDetector.extension(url)) {
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
    }

    private fun extForMime(m: String): String = when (m) {
        "video/webm" -> "webm"
        "video/x-matroska", "video/x-mkv" -> "mkv"
        "video/quicktime" -> "mov"
        else -> "mp4"
    }

    private fun prepare(s: ServeSession, variantIndex: Int?) {
        val src = s.source
        if (src.kind == MediaKind.FILE) {
            Http.open(src.url, src, extra = mapOf("Range" to "bytes=0-1"), connectTimeoutMs = 10_000, readTimeoutMs = 15_000).use { up ->
                if (up.code >= 400) throw HttpStatusException(up.code, src.url)
                val type = up.contentType?.lowercase(Locale.ROOT) ?: ""
                if (type.contains("mpegurl")) {
                    src.kind = MediaKind.HLS
                } else {
                    s.fileLength = up.totalLength
                    s.fileMime = guessMime(type, src.url)
                    Log.i(tag, "file video: ${s.fileMime}, ${Fmt.size(s.fileLength)} (HTTP ${up.code})")
                }
            }
            if (src.kind == MediaKind.FILE) return
        }
        val (text, finalUrl) = Http.getText(src.url, src)
        if (!Hls.isPlaylist(text)) throw IOException("Il link non è una playlist video valida")
        if (Hls.isMaster(text)) {
            val m = Hls.parseMaster(text, finalUrl)
            val v = variantIndex?.let { m.variants.getOrNull(it) } ?: Hls.pickVariant(m)
                ?: throw IOException("La playlist non contiene video")
            s.variantIndex = m.variants.indexOf(v)
            s.separateAudio = m.hasSeparateAudio(v)
            s.quality = v.label
            val (t2, f2) = Http.getText(v.uri, src)
            s.mediaPlaylistUrl = f2
            s.playlist = Hls.parsePlaylist(t2, f2)
        } else {
            s.mediaPlaylistUrl = finalUrl
            s.playlist = Hls.parsePlaylist(text, finalUrl)
        }
        val pl = s.playlist ?: throw IOException("Playlist vuota")
        s.fmp4 = pl.mapUri != null
        Log.i(
            tag,
            "HLS${s.quality?.let { " $it" } ?: ""}: ${pl.segments.size} segmenti, ${Fmt.duration(pl.totalDuration)}, " +
                "fine=${pl.endList}, cifrato=${pl.encrypted}, audio separato=${s.separateAudio}, fmp4=${s.fmp4}",
        )
    }

    private fun buildPlan(s: ServeSession, r: Renderer): List<Attempt> {
        val list = ArrayList<Attempt>()
        if (s.source.kind == MediaKind.FILE) {
            list += Attempt(Mode.DIRECT, s.fileMime)
            when {
                s.fileMime == "video/mp4" -> Unit
                s.fileMime == "video/x-matroska" && !r.isCast -> list += Attempt(Mode.DIRECT, "video/x-mkv")
                else -> list += Attempt(Mode.DIRECT, "video/mp4")
            }
            return list
        }
        if (r.isCast) {
            // il Chromecast legge da solo l'HLS (anche cifrato) ma non un flusso MPEG-TS unico
            list += Attempt(Mode.HLS, "application/x-mpegurl", hints = true)
            list += Attempt(Mode.HLS, "application/vnd.apple.mpegurl")
            return list
        }
        if (s.remuxable) {
            if (s.fmp4) {
                list += Attempt(Mode.TS, "video/mp4")
            } else {
                list += Attempt(Mode.TS, "video/mpeg")
                list += Attempt(Mode.TS, "video/mp2t")
            }
        }
        list += Attempt(Mode.HLS, "application/vnd.apple.mpegurl")
        list += Attempt(Mode.HLS, "application/x-mpegURL")
        return list
    }

    private fun runAttempts(gen: Int, startSec: Int) {
        val s = session ?: return
        val p = player ?: return
        while (attemptIdx < plan.size) {
            if (gen != generation.get()) return
            val a = plan[attemptIdx]
            val label = if (plan.size > 1) "Tentativo ${attemptIdx + 1} di ${plan.size} · ${a.mode.label}" else null
            publish(
                gen,
                status.copy(
                    state = PlayState.CONNECTING, mode = a.mode, attempt = label, message = "Invio alla TV…",
                    canSeek = false, quality = s.quality, tvName = p.renderer.name, tvKind = p.renderer.kindLabel,
                ),
            )
            val ok = try {
                startAttempt(gen, s, p, a, startSec)
            } catch (e: Exception) {
                Log.w(tag, "${a.mode.label} (${a.mime}) non riuscita: ${e.message}")
                lastError = e
                false
            }
            if (gen != generation.get()) return
            if (ok) {
                onStarted(gen, p, a, startSec)
                return
            }
            attemptIdx++
        }
        attemptIdx = 0
        val msg = when {
            s.requests.get() == 0 -> "La TV non ha mai contattato il telefono. Controlla che siano sulla stessa rete Wi-Fi " +
                "(non quella \"ospiti\") e che sul telefono non ci sia una VPN attiva."
            lastError != null -> "La TV non riesce a riprodurre questo video. ${friendly(lastError!!)}"
            else -> "La TV non riesce a riprodurre questo video (formato non supportato?)."
        }
        publish(gen, status.copy(state = PlayState.ERROR, attempt = null, message = msg))
    }

    private fun startAttempt(gen: Int, s: ServeSession, p: TvPlayer, a: Attempt, startSec: Int): Boolean {
        val r = p.renderer
        val ip = Net.localAddressFor(r.host) ?: Net.lanIPv4()?.hostAddress
            ?: throw IOException("Il telefono non è collegato alla rete locale")
        val base = "http://$ip:${server.port}"
        val url: String
        val seekable: Boolean
        when (a.mode) {
            Mode.DIRECT -> {
                s.fileMime = a.mime
                url = "$base/v/${s.id}/video.${extForMime(a.mime)}"
                seekable = true
                offsetSec = 0
            }
            Mode.TS -> {
                val pl = s.playlist ?: throw IOException("Playlist mancante")
                s.remuxMime = a.mime
                val from = if (startSec > 0) pl.indexAt(startSec.toDouble()) else 0
                offsetSec = pl.segments.getOrNull(from)?.start?.toInt() ?: 0
                url = "$base/t/${s.id}/$from/stream.${if (s.fmp4) "mp4" else "ts"}"
                seekable = false
            }
            Mode.HLS -> {
                s.playlistMime = a.mime
                url = "$base/h/${s.id}/master.m3u8"
                seekable = true
                offsetSec = 0
            }
        }
        val title = (s.source.title).trim().take(80).ifBlank { "Video" }
        val live = s.source.kind == MediaKind.HLS && s.playlist?.endList == false
        val hls = a.hints && a.mode == Mode.HLS
        val meta = LoadMeta(
            seekable = seekable,
            size = if (a.mode == Mode.DIRECT) s.fileLength else -1,
            durationSec = knownDuration,
            startSec = if (p.startsAtOffset && a.mode != Mode.TS) startSec else 0,
            live = live,
            hlsSegmentFormat = if (hls) (if (s.fmp4) "fmp4" else "ts") else null,
            hlsVideoSegmentFormat = if (hls) (if (s.fmp4) "fmp4" else "mpeg2_ts") else null,
        )
        Log.i(tag, "→ ${a.mode.label} (${a.mime}): $url")
        p.beforeLoad()
        val reqBefore = s.requests.get()
        p.load(url, a.mime, title, meta)
        return verify(gen, s, p, reqBefore)
    }

    /** Aspetta che la TV parta davvero; false se rinuncia (così si passa alla modalità successiva). */
    private fun verify(gen: Int, s: ServeSession, p: TvPlayer, reqBefore: Int): Boolean {
        val start = System.currentTimeMillis()
        var deadline = start + 25_000
        var playingPolls = 0
        var stopped = 0
        var lastBytes = s.bytesServed.get()
        var everActive = false
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            if (gen != generation.get()) return false
            val st = try { p.state().state } catch (e: Exception) {
                Log.d(tag, "stato non letto: ${e.message}")
                "?"
            }
            val bytes = s.bytesServed.get()
            val tvRequests = s.requests.get() - reqBefore
            val elapsed = System.currentTimeMillis() - start
            Log.d(tag, "verifica ${elapsed / 1000}s: stato=$st, richieste=$tvRequests, dati=${Fmt.size(bytes)}")
            when (st) {
                "PLAYING" -> {
                    everActive = true
                    if (++playingPolls >= 2 || bytes - lastBytes > 256 * 1024) return true
                }
                "PAUSED_PLAYBACK" -> return true
                "ERROR" -> return false
                "TRANSITIONING" -> {
                    everActive = true
                    stopped = 0
                }
                "STOPPED", "NO_MEDIA_PRESENT" -> {
                    stopped++
                    if (tvRequests > 0 && stopped >= 3 && elapsed > 4000) return false
                    if (tvRequests == 0 && !everActive && elapsed > 10_000) return false
                }
            }
            if (bytes > lastBytes) {
                lastBytes = bytes
                deadline = minOf(maxOf(deadline, System.currentTimeMillis() + 8000), start + 45_000)
            }
        }
        return false
    }

    private fun onStarted(gen: Int, p: TvPlayer, a: Attempt, startSec: Int) {
        val s = session
        val live = s?.source?.kind == MediaKind.HLS && s.playlist?.endList == false
        Log.i(tag, "la TV è partita in modalità ${a.mode.label} (${a.mime})")
        publish(
            gen,
            status.copy(
                state = PlayState.PLAYING,
                mode = a.mode,
                attempt = null,
                message = null,
                live = live,
                positionSec = if (a.mode == Mode.TS) offsetSec else startSec,
                durationSec = knownDuration,
                canSeek = !live,
                quality = s?.quality,
                canSwitchMode = plan.map { it.mode }.distinct().size > 1,
            ),
        )
        if (startSec > 0 && a.mode != Mode.TS && !p.startsAtOffset) {
            try {
                p.seek(startSec)
                seekHoldUntil = System.currentTimeMillis() + 3000
            } catch (e: Exception) {
                Log.w(tag, "seek iniziale non riuscito: ${e.message}")
            }
        }
        refreshVolume(gen)
        startPolling(gen)
    }

    private fun startPolling(gen: Int) {
        stopPolling()
        pollFailures = 0
        stoppedPolls = 0
        pollCount = 0
        pollTask = ticker.scheduleWithFixedDelay({ exec.execute { pollOnce(gen) } }, 1000, 1000, TimeUnit.MILLISECONDS)
    }

    private fun stopPolling() {
        pollTask?.cancel(false)
        pollTask = null
    }

    private fun pollOnce(gen: Int) {
        if (gen != generation.get()) return
        val p = player ?: return
        try {
            val ts = p.state()
            pollFailures = 0
            pollCount++
            val state = when (ts.state) {
                "PLAYING" -> PlayState.PLAYING
                "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PlayState.PAUSED
                "TRANSITIONING" -> PlayState.BUFFERING
                "STOPPED", "NO_MEDIA_PRESENT" -> PlayState.STOPPED
                "ERROR" -> PlayState.ERROR
                else -> status.state
            }
            val rel = ts.position
            var pos = maxOf(0, rel)
            if (status.mode == Mode.TS) {
                // alcune TV contano dall'inizio del flusso, altre usano l'orario del video: gestiamo entrambi
                val absolute = offsetSec > 20 && rel >= offsetSec - 15
                if (!absolute) pos += offsetSec
            }
            val duration = if (status.mode != Mode.TS && ts.duration > 0) ts.duration else knownDuration
            if (System.currentTimeMillis() < seekHoldUntil) pos = status.positionSec
            if (state == PlayState.STOPPED || state == PlayState.ERROR) {
                if (++stoppedPolls >= 4) {
                    Log.i(tag, "la TV ha fermato la riproduzione")
                    stopPolling()
                }
            } else {
                stoppedPolls = 0
            }
            publish(
                gen,
                status.copy(
                    state = state,
                    positionSec = if (state == PlayState.STOPPED || state == PlayState.ERROR) status.positionSec else pos,
                    durationSec = duration,
                    canSeek = !status.live && duration > 0,
                    message = when (state) {
                        PlayState.PLAYING -> null
                        PlayState.ERROR -> "La TV ha interrotto il video (errore di riproduzione)"
                        else -> status.message
                    },
                ),
            )
            if (pollCount % 6 == 0) refreshVolume(gen)
        } catch (e: Exception) {
            if (++pollFailures >= 6) {
                Log.w(tag, "la TV non risponde: ${e.message}")
                stopPolling()
                publish(gen, status.copy(state = PlayState.ERROR, message = "La TV non risponde più (spenta o fuori rete?)"))
            }
        }
    }

    private fun refreshVolume(gen: Int) {
        val p = player ?: return
        if (!p.hasVolume) return
        try {
            val v = p.volume()
            val m = try { p.mute() } catch (_: Exception) { null }
            publish(gen, status.copy(volume = v ?: status.volume, muted = m ?: status.muted))
        } catch (_: Exception) {
        }
    }

    private fun restartAt(sec: Int) {
        if (session == null || player == null || plan.isEmpty()) return
        val gen = generation.incrementAndGet()
        stopPolling()
        attemptIdx = attemptIdx.coerceIn(0, plan.size - 1)
        publish(gen, status.copy(state = PlayState.BUFFERING, positionSec = sec, message = null))
        runAttempts(gen, sec)
    }

    private fun doTogglePause() {
        val p = player ?: return
        val gen = generation.get()
        try {
            when (status.state) {
                PlayState.PLAYING, PlayState.BUFFERING -> {
                    p.pause()
                    pausedAt = System.currentTimeMillis()
                    publish(gen, status.copy(state = PlayState.PAUSED))
                }
                PlayState.PAUSED -> {
                    if (status.mode == Mode.TS && System.currentTimeMillis() - pausedAt > 45_000) {
                        // dopo una pausa lunga la TV può aver chiuso il flusso: lo riapro dal punto giusto
                        restartAt(status.positionSec)
                    } else {
                        p.play()
                        publish(gen, status.copy(state = PlayState.PLAYING))
                    }
                }
                PlayState.STOPPED, PlayState.ERROR -> restartAt(status.positionSec)
                else -> Unit
            }
        } catch (e: Exception) {
            publish(gen, status.copy(message = friendly(e)))
        }
    }

    private fun doSeek(sec: Int) {
        val p = player ?: return
        val dur = status.durationSec
        val t = if (dur > 0) sec.coerceIn(0, maxOf(0, dur - 5)) else maxOf(0, sec)
        if (status.mode == Mode.TS) {
            restartAt(t)
            return
        }
        val gen = generation.get()
        try {
            p.seek(t)
            seekHoldUntil = System.currentTimeMillis() + 2500
            publish(gen, status.copy(positionSec = t))
        } catch (e: Exception) {
            Log.w(tag, "spostamento rifiutato: ${e.message}")
            publish(gen, status.copy(message = "La TV non ha accettato lo spostamento"))
        }
    }

    private fun doSwitchMode() {
        if (plan.isEmpty()) return
        val cur = plan.getOrNull(attemptIdx)?.mode
        val next = plan.indexOfFirst { it.mode != cur }
        if (next < 0) return
        attemptIdx = next
        Log.i(tag, "cambio modalità: ${cur?.label} → ${plan[next].mode.label}")
        restartAt(status.positionSec)
    }

    private fun doVolume(delta: Int) {
        val p = player ?: return
        val gen = generation.get()
        try {
            val cur = status.volume ?: p.volume() ?: return
            val v = (cur + delta).coerceIn(0, 100)
            p.setVolume(v)
            publish(gen, status.copy(volume = v, muted = false))
        } catch (e: Exception) {
            publish(gen, status.copy(message = "Volume: ${friendly(e)}"))
        }
    }

    private fun doToggleMute() {
        val p = player ?: return
        val gen = generation.get()
        try {
            val m = !status.muted
            p.setMute(m)
            publish(gen, status.copy(muted = m))
        } catch (e: Exception) {
            publish(gen, status.copy(message = "Muto: ${friendly(e)}"))
        }
    }

    private fun doStop() {
        stopPolling()
        try { player?.stop() } catch (e: Exception) { Log.w(tag, "stop: ${e.message}") }
        val st = status.copy(state = PlayState.STOPPED, message = null, attempt = null)
        status = st
        try { listener?.invoke(st) } catch (_: Exception) { }
    }

    companion object {
        fun friendly(e: Throwable): String = when (e) {
            is HttpStatusException -> when (e.code) {
                401, 403 -> "Il sito ha rifiutato l'accesso al video (HTTP ${e.code}): ricarica la pagina, avvia il video e riprova."
                404, 410 -> "Il video non è più disponibile (HTTP ${e.code})."
                else -> "Il sito ha risposto con un errore (HTTP ${e.code})."
            }
            is TvException -> "Risposta della TV: ${e.message}"
            is SocketTimeoutException -> "Tempo scaduto: rete lenta o TV non raggiungibile."
            is UnknownHostException -> "Nessuna connessione a Internet."
            is ConnectException -> "Connessione rifiutata dalla TV o dal sito."
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}
