import it.telecast.BrowserActivity
import java.io.File

/** Scrive in una cartella gli script che l'app inietta nelle pagine, per provarli con dev/shield_test.js. */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: ".")
    val b = "tcTEST"
    File(dir, "shield.js").writeText(BrowserActivity.shieldJs(b))
    File(dir, "sweep.js").writeText(BrowserActivity.sweepJs(b))
    File(dir, "scan.js").writeText(BrowserActivity.scanJs(b))
    println("script scritti in ${dir.path}")
}
