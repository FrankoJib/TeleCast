package it.telecast

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import it.telecast.core.Log

/** Palette: sfondo scuro "da cinema" con accento ambra. */
object C {
    const val BG = 0xFF0F1115.toInt()
    const val SURFACE = 0xFF181B22.toInt()
    const val SURFACE2 = 0xFF222632.toInt()
    const val LINE = 0xFF2E3442.toInt()
    const val TEXT = 0xFFF2F3F5.toInt()
    const val DIM = 0xFF9AA0AB.toInt()
    const val ACCENT = 0xFFFFB21E.toInt()
    const val ON_ACCENT = 0xFF1D1400.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()
    const val OK = 0xFF3DDC97.toInt()
}

const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun roundRect(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/** Sfondo con effetto "tocco" (ripple) limitato alla forma. */
fun rippleRect(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0, rippleColor: Int = 0x33FFFFFF): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), roundRect(color, radius, stroke, strokeColor), roundRect(Color.WHITE, radius))

fun rippleOval(color: Int = Color.TRANSPARENT, rippleColor: Int = 0x33FFFFFF): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), if (color == Color.TRANSPARENT) null else oval(color), oval(Color.WHITE))

fun Context.label(text: CharSequence, sizeSp: Float, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = MEDIUM
    }

fun Context.iconButton(res: Int, tint: Int, desc: String, sizeDp: Int = 44, bg: Int = Color.TRANSPARENT): ImageButton =
    ImageButton(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(tint)
        background = rippleOval(bg)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = desc
        val pad = dp(if (sizeDp >= 56) 16 else 10)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

fun Context.pill(text: String, bg: Int, fg: Int, outline: Int = 0): TextView = TextView(this).apply {
    this.text = text
    setTextColor(fg)
    textSize = 15f
    typeface = MEDIUM
    gravity = Gravity.CENTER
    setPadding(dp(18), dp(11), dp(18), dp(11))
    background = rippleRect(bg, dpf(24f), if (outline != 0) dp(1) else 0, outline)
    isClickable = true
    isFocusable = true
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b) }

fun Context.dialog(): AlertDialog.Builder = AlertDialog.Builder(this, R.style.Theme_TeleCast_Dialog)

/** Disegno "da bordo a bordo": il contenuto evita da solo barre di sistema, notch e tastiera. */
object Edge {
    @Suppress("DEPRECATION")
    fun apply(act: Activity, root: View) {
        val w = act.window
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false)
        } else {
            w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.requestApplyInsets()
    }
}

/** Log diagnostico consultabile e copiabile (da incollare quando qualcosa non va). */
object LogDialog {
    fun show(act: Activity) {
        val text = Log.dump().ifBlank { "Ancora nessun evento." }
        val tv = TextView(act).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setTextColor(C.TEXT)
            setTextIsSelectable(true)
            setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(8))
        }
        val sv = ScrollView(act).apply { addView(tv) }
        act.dialog()
            .setTitle("Log diagnostico")
            .setView(sv)
            .setPositiveButton("Copia") { _, _ ->
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("TeleCast log", Log.dump()))
                act.toast("Log copiato")
            }
            .setNegativeButton("Chiudi", null)
            .show()
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }
}
