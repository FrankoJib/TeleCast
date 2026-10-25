package it.telecast

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import it.telecast.core.CastStatus
import it.telecast.core.Fmt
import it.telecast.core.PlayState

/** Stato in poche parole (barra "in onda" e notifica). */
fun shortState(st: CastStatus): String = when (st.state) {
    PlayState.IDLE -> ""
    PlayState.CONNECTING -> "Collegamento…"
    PlayState.BUFFERING -> "Caricamento…"
    PlayState.PLAYING -> "In onda"
    PlayState.PAUSED -> "In pausa"
    PlayState.STOPPED -> "Fermo"
    PlayState.ERROR -> "Errore"
}

/** Il telecomando: tutto ciò che serve mentre il film va sulla TV. */
class RemoteActivity : Activity() {
    private val app get() = App.instance
    private lateinit var tvText: TextView
    private lateinit var tvIcon: android.widget.ImageView
    private lateinit var changeTv: TextView
    private lateinit var titleText: TextView
    private lateinit var modeChip: TextView
    private lateinit var qualityChip: TextView
    private lateinit var stateText: TextView
    private lateinit var attemptText: TextView
    private lateinit var seek: SeekBar
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var playBtn: ImageButton
    private lateinit var muteBtn: ImageButton
    private lateinit var volText: TextView
    private lateinit var altBtn: TextView
    private lateinit var skipButtons: List<View>
    private var dragging = false
    private val listener: (CastStatus) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = build()
        setContentView(root)
        Edge.apply(this, root)
    }

    override fun onStart() {
        super.onStart()
        app.addStatusListener(listener)
    }

    override fun onStop() {
        app.removeStatusListener(listener)
        super.onStop()
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(16))
        }
        root.addView(inner, lp(MATCH, MATCH))

        // barra in alto
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(iconButton(R.drawable.ic_arrow_back, C.TEXT, "Indietro").apply { setOnClickListener { finish() } })
        top.addView(label("Telecomando", 18f, C.TEXT, bold = true).apply { setPadding(dp(6), 0, 0, 0) }, lp(0, WRAP, 1f))
        top.addView(iconButton(R.drawable.ic_log, C.DIM, "Log diagnostico").apply { setOnClickListener { LogDialog.show(this@RemoteActivity) } })
        inner.addView(top, lp(MATCH, WRAP, l = -dp(10)))

        // TV (con "Cambia TV") e titolo
        val tvRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(6), dp(8))
            background = roundRect(C.SURFACE, dpf(14f))
        }
        tvIcon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_tv)
            imageTintList = ColorStateList.valueOf(C.ACCENT)
        }
        tvRow.addView(tvIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
        tvText = label("", 14f, C.TEXT, bold = true).apply {
            setPadding(dp(10), 0, dp(8), 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        tvRow.addView(tvText, lp(0, WRAP, 1f))
        changeTv = label("Cambia TV", 13f, C.ON_ACCENT, bold = true).apply {
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = rippleRect(C.ACCENT, dpf(16f))
            isClickable = true
            setOnClickListener { chooseOtherTv() }
        }
        tvRow.addView(changeTv)
        inner.addView(tvRow, lp(MATCH, WRAP, t = dp(14)))

        titleText = label("", 24f, C.TEXT, bold = true).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        inner.addView(titleText, lp(MATCH, WRAP, t = dp(8)))

        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeChip = chip()
        qualityChip = chip()
        chips.addView(modeChip, lp(WRAP, WRAP, r = dp(8)))
        chips.addView(qualityChip, lp(WRAP, WRAP))
        inner.addView(chips, lp(MATCH, WRAP, t = dp(12)))

        stateText = label("", 15f, C.DIM, bold = true)
        inner.addView(stateText, lp(MATCH, WRAP, t = dp(18)))
        attemptText = label("", 13f, C.DIM)
        inner.addView(attemptText, lp(MATCH, WRAP, t = dp(2)))

        inner.addView(View(this), lp(MATCH, 0, 1f))

        // avanzamento
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(C.ACCENT)
            thumbTintList = ColorStateList.valueOf(C.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(C.LINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) posText.text = Fmt.clock(p)
                }

                override fun onStartTrackingTouch(sb: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    app.cast.seekTo(sb.progress)
                }
            })
        }
        inner.addView(seek, lp(MATCH, WRAP, l = -dp(8), r = -dp(8)))
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        posText = label("0:00", 13f, C.DIM)
        durText = label("--:--", 13f, C.DIM).apply { gravity = Gravity.END }
        times.addView(posText, lp(0, WRAP, 1f))
        times.addView(durText, lp(0, WRAP, 1f))
        inner.addView(times, lp(MATCH, WRAP, t = dp(2)))

        // comandi principali
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val b30 = skip("−30") { app.cast.seekBy(-30) }
        val b10 = skip("−10") { app.cast.seekBy(-10) }
        val f10 = skip("+10") { app.cast.seekBy(10) }
        val f30 = skip("+30") { app.cast.seekBy(30) }
        skipButtons = listOf(b30, b10, f10, f30)
        playBtn = iconButton(R.drawable.ic_play, C.ON_ACCENT, "Play o pausa", sizeDp = 76, bg = C.ACCENT).apply {
            elevation = dpf(6f)
            setOnClickListener { app.cast.togglePause() }
        }
        controls.addView(b30, lp(dp(54), dp(54)))
        controls.addView(b10, lp(dp(54), dp(54), l = dp(8)))
        controls.addView(playBtn, lp(dp(76), dp(76), l = dp(14), r = dp(14)))
        controls.addView(f10, lp(dp(54), dp(54), r = dp(8)))
        controls.addView(f30, lp(dp(54), dp(54)))
        inner.addView(controls, lp(MATCH, WRAP, t = dp(18)))

        // volume
        val vol = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        vol.addView(iconButton(R.drawable.ic_volume_down, C.TEXT, "Volume giù", 52, C.SURFACE2).apply { setOnClickListener { app.cast.volumeBy(-3) } })
        muteBtn = iconButton(R.drawable.ic_volume_up, C.TEXT, "Muto", 52, C.SURFACE2).apply { setOnClickListener { app.cast.toggleMute() } }
        vol.addView(muteBtn, lp(dp(52), dp(52), l = dp(16), r = dp(16)))
        vol.addView(iconButton(R.drawable.ic_volume_up, C.TEXT, "Volume su", 52, C.SURFACE2).apply { setOnClickListener { app.cast.volumeBy(3) } })
        inner.addView(vol, lp(MATCH, WRAP, t = dp(18)))
        volText = label("", 12f, C.DIM).apply { gravity = Gravity.CENTER }
        inner.addView(volText, lp(MATCH, WRAP, t = dp(4)))

        // azioni
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        altBtn = pill("Prova l'altra modalità", C.SURFACE, C.TEXT, C.LINE).apply { setOnClickListener { app.cast.switchMode() } }
        val stopBtn = pill("Stop", C.SURFACE, C.DANGER, C.LINE).apply {
            setOnClickListener {
                app.cast.stop()
                finish()
            }
        }
        actions.addView(altBtn, lp(0, WRAP, 1f, r = dp(10)))
        actions.addView(stopBtn, lp(WRAP, WRAP))
        inner.addView(actions, lp(MATCH, WRAP, t = dp(22)))

        inner.addView(
            label("Puoi bloccare il telefono: la TV continua da sola.", 12f, C.DIM).apply { gravity = Gravity.CENTER },
            lp(MATCH, WRAP, t = dp(14)),
        )
        return root
    }

    /** Sposta il film su un'altra TV, dal punto in cui si è arrivati. */
    private fun chooseOtherTv() {
        if (!app.cast.canMove) {
            toast("Scegli prima un video dal browser")
            return
        }
        DevicePicker(this, "Sposta il film su…", excludeUdn = app.cast.currentRenderer?.udn) { r ->
            app.cast.moveTo(r)
            CastService.start(this)
        }.show()
    }

    private fun chip(): TextView = label("", 12f, C.DIM, bold = true).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = roundRect(C.SURFACE2, dpf(12f))
    }

    private fun skip(text: String, action: () -> Unit): TextView = label(text, 15f, C.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        background = rippleOval(C.SURFACE2)
        isClickable = true
        contentDescription = "Sposta di $text secondi"
        setOnClickListener { action() }
    }

    private fun render(st: CastStatus) {
        tvText.text = if (st.tvName.isBlank()) "Nessuna TV" else listOf(st.tvName, st.tvKind).filter { it.isNotBlank() }.joinToString(" · ")
        tvIcon.setImageResource(if (st.tvKind == "Chromecast") R.drawable.ic_cast else R.drawable.ic_tv)
        changeTv.visibility = if (app.cast.canMove) View.VISIBLE else View.GONE
        titleText.text = st.title.ifBlank { "Niente in onda" }
        modeChip.text = st.mode?.let { "Modalità: ${it.label}" } ?: ""
        modeChip.visibility = if (st.mode != null) View.VISIBLE else View.GONE
        qualityChip.text = st.quality ?: ""
        qualityChip.visibility = if (st.quality != null) View.VISIBLE else View.GONE

        val (txt, color) = when (st.state) {
            PlayState.IDLE -> "Scegli un video dal browser" to C.DIM
            PlayState.CONNECTING -> (st.message ?: "Collegamento alla TV…") to C.ACCENT
            PlayState.BUFFERING -> "Caricamento…" to C.ACCENT
            PlayState.PLAYING -> (st.message ?: "In riproduzione sulla TV") to C.OK
            PlayState.PAUSED -> "In pausa" to C.TEXT
            PlayState.STOPPED -> "Fermo · tocca ▶ per riprendere" to C.DIM
            PlayState.ERROR -> (st.message ?: "Errore") to C.DANGER
        }
        stateText.text = txt
        stateText.setTextColor(color)
        attemptText.text = st.attempt ?: ""
        attemptText.visibility = if (st.attempt != null) View.VISIBLE else View.GONE

        val busy = st.state == PlayState.PLAYING || st.state == PlayState.BUFFERING || st.state == PlayState.CONNECTING
        playBtn.setImageResource(if (busy) R.drawable.ic_pause else R.drawable.ic_play)

        seek.isEnabled = st.canSeek
        if (!dragging) {
            seek.max = maxOf(1, st.durationSec)
            seek.progress = st.positionSec.coerceIn(0, seek.max)
            posText.text = Fmt.clock(st.positionSec)
        }
        durText.text = when {
            st.live -> "LIVE"
            st.durationSec > 0 -> Fmt.clock(st.durationSec)
            else -> "--:--"
        }
        for (b in skipButtons) {
            b.isEnabled = st.canSeek
            b.alpha = if (st.canSeek) 1f else 0.4f
        }
        muteBtn.setImageResource(if (st.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
        volText.text = st.volume?.let { "Volume TV: $it" + if (st.muted) " (muto)" else "" } ?: ""
        altBtn.visibility = if (st.canSwitchMode && st.state != PlayState.IDLE) View.VISIBLE else View.INVISIBLE
    }
}
