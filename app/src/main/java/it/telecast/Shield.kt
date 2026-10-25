package it.telecast

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import it.telecast.core.AdBlocker
import it.telecast.core.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Il blocco pubblicità dell'app: elenco in memoria, aggiornato in background ogni pochi giorni. */
object AdBlock {
    private const val TAG = "AdBlock"

    @Volatile private var blocker: AdBlocker? = null

    fun init(app: App) {
        val b = AdBlocker(File(app.filesDir, "adblock/elenco.bin"))
        blocker = b
        app.bg.execute {
            b.load()
            // sui dati mobili aggiorna solo se manca del tutto l'elenco completo
            if (b.needsUpdate() && (!b.hasFullList || !metered(app))) {
                try {
                    b.update()
                } catch (e: Exception) {
                    Log.w(TAG, "aggiornamento non riuscito", e)
                }
            }
        }
    }

    fun isBlocked(host: String): Boolean = blocker?.isBlocked(host) ?: false

    val size: Int get() = blocker?.size ?: 0
    val hasFullList: Boolean get() = blocker?.hasFullList == true
    val updating: Boolean get() = blocker?.updating == true
    val updatedAt: Long get() = blocker?.updatedAt ?: 0L

    fun updateNow(app: App, done: (Boolean) -> Unit) {
        val b = blocker ?: return done(false)
        app.bg.execute {
            val ok = try {
                b.update()
            } catch (e: Exception) {
                Log.w(TAG, "aggiornamento non riuscito", e)
                false
            }
            app.main.post { done(ok) }
        }
    }

    /** "Elenco: 208.671 siti pubblicitari, aggiornato il 25/10/2026." */
    fun describe(): String = when {
        updating && !hasFullList -> "Sto scaricando l'elenco completo dei siti pubblicitari…"
        hasFullList && updatedAt > 0 ->
            "Elenco: ${number(size)} siti pubblicitari, aggiornato il ${SimpleDateFormat("d/M/yyyy", Locale.ITALY).format(Date(updatedAt))}."
        hasFullList -> "Elenco: ${number(size)} siti pubblicitari."
        else -> "Elenco di base (${number(size)} siti): quello completo si scarica appena c'è internet."
    }

    fun number(n: Int): String = String.format(Locale.ITALY, "%,d", n)

    private fun metered(c: Context): Boolean = try {
        (c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    } catch (_: Exception) {
        false
    }
}

/** Quanto ha bloccato lo scudo sulla pagina aperta. */
class ShieldStats {
    val ads = AtomicInteger()
    val popups = AtomicInteger()
    val redirects = AtomicInteger()
    val total: Int get() = ads.get() + popups.get() + redirects.get()

    fun reset() {
        ads.set(0)
        popups.set(0)
        redirects.set(0)
    }

    fun describe(): String {
        fun n(v: Int, one: String, many: String) = if (v == 1) "1 $one" else "$v $many"
        return "Su questa pagina ho bloccato " + listOf(
            n(ads.get(), "pubblicità", "pubblicità"),
            n(popups.get(), "pop-up", "pop-up"),
            n(redirects.get(), "reindirizzamento", "reindirizzamenti"),
        ).joinToString(", ") + "."
    }
}

/** Un pop-up o un reindirizzamento bloccato, riapribile se serviva davvero. */
class BlockedItem(val url: String, val what: String)

/** Avviso in alto: "Pop-up bloccato · sito.xyz   Apri". Sparisce da solo dopo qualche secondo. */
class BlockedBar(ctx: Context) : LinearLayout(ctx) {
    private val text: TextView
    private val open: TextView
    private var action: (() -> Unit)? = null
    private val hideTask = Runnable { dismiss() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(14), ctx.dp(4), ctx.dp(4), ctx.dp(4))
        background = roundRect(C.SURFACE2, ctx.dpf(16f), ctx.dp(1), C.LINE)
        elevation = ctx.dpf(6f)
        visibility = GONE
        isClickable = true
        addView(
            ImageView(ctx).apply {
                setImageResource(R.drawable.ic_shield)
                imageTintList = ColorStateList.valueOf(C.ACCENT)
            },
            LayoutParams(ctx.dp(18), ctx.dp(18)),
        )
        text = ctx.label("", 13f, C.TEXT).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ctx.dp(10), 0, ctx.dp(6), 0)
        }
        addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        open = ctx.label("Apri", 14f, C.ACCENT, bold = true).apply {
            setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            background = rippleRect(Color.TRANSPARENT, ctx.dpf(14f))
            isClickable = true
            contentDescription = "Apri comunque"
            setOnClickListener {
                val a = action
                dismiss()
                a?.invoke()
            }
        }
        addView(open)
    }

    fun show(message: String, onOpen: (() -> Unit)?) {
        text.text = message
        action = onOpen
        open.visibility = if (onOpen != null) VISIBLE else GONE
        removeCallbacks(hideTask)
        if (visibility != VISIBLE) {
            visibility = VISIBLE
            alpha = 0f
            translationY = -context.dpf(8f)
            animate().alpha(1f).translationY(0f).setDuration(160).start()
        }
        postDelayed(hideTask, 6000)
    }

    fun dismiss() {
        removeCallbacks(hideTask)
        if (visibility != VISIBLE) return
        animate().alpha(0f).setDuration(160).withEndAction {
            visibility = GONE
            action = null
        }.start()
    }
}
