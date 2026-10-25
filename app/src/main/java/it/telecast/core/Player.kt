package it.telecast.core

import java.io.IOException

/** Errore comunicato dalla TV (DLNA o Chromecast). */
open class TvException(val code: Int, message: String) : IOException(message)

/**
 * Stato letto dalla TV, normalizzato sui nomi DLNA:
 * PLAYING, PAUSED_PLAYBACK, TRANSITIONING, STOPPED, NO_MEDIA_PRESENT, ERROR.
 */
data class TvState(val state: String, val position: Int, val duration: Int)

data class LoadMeta(
    val seekable: Boolean,
    val size: Long = -1,
    val durationSec: Int = -1,
    val startSec: Int = 0,
    val live: Boolean = false,
    val hlsSegmentFormat: String? = null,
    val hlsVideoSegmentFormat: String? = null,
)

/** Una TV comandabile, qualunque sia il protocollo. */
interface TvPlayer {
    val renderer: Renderer
    val hasVolume: Boolean

    /** La TV parte da sola dal secondo indicato in [LoadMeta.startSec]? (Chromecast sì, DLNA no) */
    val startsAtOffset: Boolean

    fun beforeLoad()
    fun load(url: String, mime: String, title: String, meta: LoadMeta)
    fun play()
    fun pause()
    fun seek(sec: Int)
    fun state(): TvState

    /** Ferma tutto (su Chromecast chiude anche il ricevitore e la TV torna a dov'era). */
    fun stop()
    fun volume(): Int?
    fun setVolume(v: Int)
    fun mute(): Boolean?
    fun setMute(m: Boolean)
    fun close()
}

fun playerFor(r: Renderer): TvPlayer = if (r.isCast) CastPlayer(r) else DlnaPlayer(r)

class DlnaPlayer(override val renderer: Renderer) : TvPlayer {
    private val d = DlnaClient(renderer)
    override val hasVolume: Boolean get() = renderer.renderingUrl != null
    override val startsAtOffset = false

    override fun beforeLoad() {
        try { d.stop() } catch (_: Exception) { }
    }

    override fun load(url: String, mime: String, title: String, meta: LoadMeta) {
        d.setUri(url, Didl.build(url, title, mime, meta.seekable, meta.size, meta.durationSec))
        try {
            d.play()
        } catch (e: DlnaException) {
            Log.w("DLNA", "Play: ${e.message}, riprovo")
            Thread.sleep(1200)
            try { d.play() } catch (e2: DlnaException) { Log.w("DLNA", "Play: ${e2.message}") }
        }
    }

    override fun play() = d.play()
    override fun pause() = d.pause()
    override fun seek(sec: Int) = d.seek(sec)
    override fun stop() = d.stop()

    override fun state(): TvState {
        val st = d.transportState()
        val (rel, dur) = d.position()
        return TvState(st, rel, dur)
    }

    override fun volume(): Int? = d.volume()
    override fun setVolume(v: Int) = d.setVolume(v)
    override fun mute(): Boolean? = d.mute()
    override fun setMute(m: Boolean) = d.setMute(m)
    override fun close() = Unit
}
