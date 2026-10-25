package testad

import it.telecast.core.AdBlocker
import it.telecast.core.DomainSet
import it.telecast.core.Log
import it.telecast.core.NavShield
import it.telecast.core.NavShield.Popup
import it.telecast.core.NavShield.Tap
import it.telecast.core.NavShield.Verdict
import it.telecast.core.Sites
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.system.exitProcess

// Test del blocco pubblicità. Uso: AdFilterTestKt [cartella con pro.txt e popup.txt veri]

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

fun main(args: Array<String>) {
    Log.platform = { l, t, m, _ -> if (l != 'D') println("       · $l/$t: $m") }
    parsing()
    domainSet()
    sites()
    blocker()
    shield()
    popups()
    if (args.isNotEmpty()) realLists(File(args[0]))
    println()
    println(if (failures == 0) "TUTTI I TEST OK ($passed)" else "FALLITI: $failures su ${passed + failures}")
    exitProcess(if (failures == 0) 0 else 1)
}

fun parsing() {
    println("== Righe degli elenchi")
    val p = DomainSet::parseLine
    check("dominio semplice", p("ads.example.com") == "ads.example.com")
    check("maiuscole e spazi", p("  Ads.Example.COM  ") == "ads.example.com")
    check("jolly *.", p("*.popads.net") == "popads.net")
    check("formato hosts 0.0.0.0", p("0.0.0.0 tracker.io") == "tracker.io")
    check("formato hosts 127.0.0.1 con tab e commento", p("127.0.0.1\tad.site.it # pubblicità") == "ad.site.it")
    check("formato AdBlock ||dominio^", p("||exoclick.com^") == "exoclick.com")
    check("AdBlock con opzioni ignorato", p("||exoclick.com^\$third-party") == null)
    check("commento #", p("# Title: HaGeZi") == null)
    check("commento !", p("! AdGuard") == null)
    check("sezione [Adblock]", p("[Adblock Plus 2.0]") == null)
    check("riga vuota", p("   ") == null)
    check("localhost escluso", p("127.0.0.1 localhost") == null)
    check("IP non di blocco escluso", p("10.0.0.1 router.lan") == null)
    check("TLD da solo escluso", p("com") == null)
    check("caratteri non validi", p("ads.ex<ample>.com") == null)
    check("punto finale tolto", p("ads.example.com.") == "ads.example.com")
    check("trattino iniziale escluso", p("-bad.com") == null)
}

fun domainSet() {
    println("== Insieme di domini")
    val s = DomainSet.of("popads.net", "ads.example.com", "*.exoclick.com", "doppio.com", "doppio.com")
    check("dimensione senza doppioni", s.size == 4) { "size=${s.size}" }
    check("dominio esatto", s.matches("popads.net"))
    check("sottodominio", s.matches("c1.popads.net"))
    check("sottodominio profondo", s.matches("a.b.c.popads.net"))
    check("sottodominio di una voce con sottodominio", s.matches("x.ads.example.com"))
    check("il dominio padre NON è bloccato", !s.matches("example.com"))
    check("un fratello NON è bloccato", !s.matches("www.example.com"))
    check("somiglianza nel nome NON basta", !s.matches("notpopads.net") && !s.matches("popads.net.evil.org"))
    check("suffisso diverso NON bloccato", !s.matches("popads.network"))
    check("TLD mai bloccato", !s.matches("net") && !s.matches("com"))
    check("host vuoto", !s.matches(""))
    check("insieme vuoto", !DomainSet.EMPTY.matches("popads.net"))

    val out = ByteArrayOutputStream()
    s.writeTo(out, 1234567L)
    val st = DomainSet.readFrom(out.toByteArray())
    check("salvataggio e rilettura", st != null && st.updatedAt == 1234567L && st.set.size == 4 && st.set.matches("x.popads.net"))
    val bytes = out.toByteArray()
    check("file troncato rifiutato", DomainSet.readFrom(bytes.copyOf(bytes.size - 3)) == null)
    val bad = bytes.copyOf()
    bad[0] = 0
    check("intestazione sbagliata rifiutata", DomainSet.readFrom(bad) == null)
    val unsorted = bytes.copyOf()
    for (i in 0 until 8) {
        val t = unsorted[20 + i]
        unsorted[20 + i] = unsorted[28 + i]
        unsorted[28 + i] = t
    }
    check("contenuto alterato (non in ordine) rifiutato", DomainSet.readFrom(unsorted) == null)
    check("file vuoto rifiutato", DomainSet.readFrom(ByteArray(0)) == null)

    val b = DomainSet.Builder()
    b.add("uno.com")
    val mark = b.count
    b.add("due.com")
    b.add("tre.com")
    b.truncate(mark)
    val t = b.build()
    check("annullamento di un download a metà", t.size == 1 && t.matches("uno.com") && !t.matches("due.com"))
}

fun sites() {
    println("== Siti")
    check("www tolto", Sites.of("www.rai.it") == "rai.it")
    check("sottodominio italiano", Sites.of("player.rai.it") == "rai.it")
    check("dominio a 3 lettere non è un suffisso", Sites.of("video.sky.it") == "sky.it")
    check("co.uk", Sites.of("news.bbc.co.uk") == "bbc.co.uk")
    check("com.br", Sites.of("www.globo.com.br") == "globo.com.br")
    check("github.io separa gli utenti", Sites.of("tizio.github.io") == "tizio.github.io" && Sites.of("a.tizio.github.io") == "tizio.github.io")
    check("altervista separa i siti", Sites.of("ciao.altervista.org") == "ciao.altervista.org")
    check("profondo", Sites.of("a.b.c.example.com") == "example.com")
    check("IP intero", Sites.of("192.168.1.5") == "192.168.1.5")
    check("localhost", Sites.of("localhost") == "localhost")
    check("host da URL con porta e utente", Sites.hostOf("https://user:pw@Sub.Example.com:8443/x?y#z") == "sub.example.com")
    check("host da URL senza percorso", Sites.hostOf("http://example.com") == "example.com")
    check("host da URL con query subito", Sites.hostOf("https://example.com?x=1") == "example.com")
    check("host IPv6", Sites.hostOf("http://[::1]:8080/") == "::1")
    check("non URL", Sites.hostOf("about:blank") == "" && Sites.hostOf(null) == "")
}

class FakeNet(val pages: Map<String, String>) {
    val calls = mutableListOf<String>()
    fun fetch(url: String): InputStream {
        calls += url
        val body = pages[url] ?: throw IOException("HTTP 404")
        if (body == "CRASH") {
            // si interrompe a metà: prima arrivano delle righe valide
            return object : InputStream() {
                val data = "mezzo1.com\nmezzo2.com\n".toByteArray()
                var i = 0
                override fun read(): Int = if (i < data.size) data[i++].toInt() else throw IOException("connessione interrotta")
            }
        }
        return ByteArrayInputStream(body.toByteArray())
    }
}

fun bigList(prefix: String, n: Int) = buildString {
    append("# Title: prova\n")
    for (i in 0 until n) append("$prefix$i.com\n")
}

fun blocker() {
    println("== Aggiornamento elenchi")
    val dir = File(System.getProperty("java.io.tmpdir"), "tc-adtest-${System.nanoTime()}").apply { mkdirs() }
    val file = File(dir, "adblock.bin")
    val sources = listOf(listOf("https://a/main", "https://b/main"), listOf("https://a/pop"))
    val day = 24 * 3600 * 1000L

    val net1 = FakeNet(mapOf("https://a/main" to bigList("ads", 1500), "https://a/pop" to bigList("pop", 1200)))
    val ab = AdBlocker(file, sources, net1::fetch)
    check("elenco interno attivo da subito", ab.isBlocked("c.popads.net") && ab.isBlocked("pagead2.googlesyndication.com"))
    check("all'inizio serve il download", ab.needsUpdate(1000L))
    check("download riuscito", ab.update(now = 10 * day))
    check("domini scaricati bloccati", ab.isBlocked("ads7.com") && ab.isBlocked("x.pop99.com") && ab.list.size == 2700) { "size=${ab.list.size}" }
    check("maiuscole nell'host", ab.isBlocked("WWW.ADS7.COM"))
    check("siti normali passano", !ab.isBlocked("google.com") && !ab.isBlocked("www.rai.it"))
    check("file salvato", file.exists() && file.length() == 20L + 2700 * 8) { "len=${file.length()}" }
    check("non serve aggiornare subito dopo", !ab.needsUpdate(10 * day + 1000))
    check("serve dopo 5 giorni", ab.needsUpdate(15 * day))
    check("serve se l'orologio va indietro", ab.needsUpdate(9 * day))

    val ab2 = AdBlocker(file, sources, { throw IOException("offline") })
    check("riavvio: elenco riletto dal file", ab2.load() && ab2.isBlocked("ads1499.com") && ab2.updatedAt == 10 * day)

    val net3 = FakeNet(mapOf("https://a/main" to "CRASH", "https://b/main" to bigList("alt", 1100), "https://a/pop" to bigList("pop", 1200)))
    val ab3 = AdBlocker(file, sources, net3::fetch)
    ab3.load()
    check("fonte di riserva usata se la prima si interrompe", ab3.update(now = 20 * day) && ab3.isBlocked("alt5.com"))
    check("righe del download interrotto scartate", !ab3.isBlocked("mezzo1.com"))
    check("vecchi domini non più in elenco", !ab3.isBlocked("ads7.com"))

    val net4 = FakeNet(mapOf("https://a/main" to bigList("nuovo", 1500)))
    val ab4 = AdBlocker(file, sources, net4::fetch)
    ab4.load()
    check("se una fonte manca tiene l'elenco vecchio", !ab4.update(now = 30 * day) && ab4.isBlocked("alt5.com") && !ab4.isBlocked("nuovo1.com"))

    val net5 = FakeNet(mapOf("https://a/main" to "<html><body>Errore</body></html>", "https://a/pop" to bigList("pop", 1200)))
    val ab5 = AdBlocker(file, sources, net5::fetch)
    ab5.load()
    check("pagina di errore al posto dell'elenco rifiutata", !ab5.update(now = 40 * day) && ab5.isBlocked("alt5.com"))

    val empty = File(dir, "vuoto.bin")
    val net6 = FakeNet(mapOf("https://a/pop" to bigList("solo", 1200)))
    val ab6 = AdBlocker(empty, sources, net6::fetch)
    check("prima volta senza una fonte: usa la parte scaricata ma non la salva", !ab6.update(now = 50 * day) && ab6.isBlocked("solo3.com") && !empty.exists() && ab6.needsUpdate(50 * day))
    dir.deleteRecursively()
}

fun shield() {
    println("== Reindirizzamenti")
    val blocked = DomainSet.of("popads.net", "adsite.xyz")
    val isBlocked: (String) -> Boolean = { blocked.matches(it) }
    val page = "https://www.filmsito.it/film/123"
    val now = 1_000_000L
    fun d(target: String, cur: String? = page, redirect: Boolean = false, tap: Tap? = null, lastAllowed: Long = 0L) =
        NavShield.decide(target, cur, redirect, tap, lastAllowed, now, isBlocked)

    check("stesso sito passa", d("https://filmsito.it/film/124") == Verdict.ALLOW)
    check("sottodominio dello stesso sito passa", d("https://player.filmsito.it/x") == Verdict.ALLOW)
    check("dalla pagina iniziale passa", d("https://www.google.com/search?q=x", cur = null) == Verdict.ALLOW)
    check("dominio pubblicitario bloccato", d("https://c2.popads.net/go") == Verdict.AD)
    check("dominio pubblicitario bloccato anche col tocco", d("https://c2.popads.net/go", tap = Tap("https://c2.popads.net/go", now - 100, false)) == Verdict.AD)
    check("altro sito senza tocco: bloccato", d("https://vinci-un-iphone.top/") == Verdict.UNWANTED)
    check("altro sito col link toccato: passa", d("https://www.wikipedia.org/wiki/Film", tap = Tap("https://it.wikipedia.org/wiki/Film", now - 300, false)) == Verdict.ALLOW)
    check("tocco su uno strato trasparente: bloccato", d("https://spam.top/", tap = Tap("https://spam.top/", now - 300, true)) == Verdict.UNWANTED)
    check("tocco vecchio (6 s): bloccato", d("https://wikipedia.org/", tap = Tap("https://wikipedia.org/", now - 6000, false)) == Verdict.UNWANTED)
    check("toccato un link ma va altrove: bloccato", d("https://spam.top/", tap = Tap("https://wikipedia.org/", now - 300, false)) == Verdict.UNWANTED)
    check("reindirizzamento del server appena dopo una navigazione voluta: passa", d("https://altro.com/", redirect = true, lastAllowed = now - 2000) == Verdict.ALLOW)
    check("reindirizzamento del server molto dopo: bloccato", d("https://altro.com/", redirect = true, lastAllowed = now - 60_000) == Verdict.UNWANTED)
    check("reindirizzamento verso pubblicità: bloccato", d("https://adsite.xyz/", redirect = true, lastAllowed = now - 1000) == Verdict.AD)
    check("dai risultati di Google passa", d("https://www.filmsito.it/", cur = "https://www.google.it/search?q=film") == Verdict.ALLOW)
    check("da DuckDuckGo passa", d("https://www.filmsito.it/", cur = "https://duckduckgo.com/?q=film") == Verdict.ALLOW)
    check("sito in elenco aperto apposta: le sue pagine passano", d("https://adsite.xyz/pagina2", cur = "https://adsite.xyz/") == Verdict.ALLOW)
    check("sito in elenco aperto apposta: altri siti spam no", d("https://popads.net/", cur = "https://adsite.xyz/") == Verdict.AD)
}

fun popups() {
    println("== Pop-up")
    val blocked = DomainSet.of("popads.net")
    val isBlocked: (String) -> Boolean = { blocked.matches(it) }
    val page = "https://www.filmsito.it/film/123"
    fun p(url: String, cur: String? = page) = NavShield.popup(url, cur, isBlocked)

    check("la stessa pagina riaperta (pop-under) ignorata", p("https://www.filmsito.it/film/123#play") == Popup.SAME_PAGE)
    check("la stessa pagina con / finale ignorata", p("https://www.filmsito.it/film/123/") == Popup.SAME_PAGE)
    check("pop-up pubblicitario", p("https://x.popads.net/") == Popup.AD)
    check("pop-up verso un altro sito: bloccato (riapribile)", p("https://vinci.top/") == Popup.UNWANTED)
    check("pagina dello stesso sito: si apre qui", p("https://www.filmsito.it/player/9") == Popup.OPEN_HERE)
    check("dalla pagina iniziale: bloccato", p("https://vinci.top/", cur = null) == Popup.UNWANTED)
}

fun realLists(dir: File) {
    println("== Elenchi veri (${dir.path})")
    val pro = File(dir, "pro.txt")
    val pop = File(dir, "popup.txt")
    if (!pro.exists() || !pop.exists()) {
        check("elenchi presenti", false) { "mancano pro.txt / popup.txt" }
        return
    }
    val pages = mapOf("https://gh/pro" to pro.readText(), "https://gh/pop" to pop.readText())
    val file = File(System.getProperty("java.io.tmpdir"), "tc-real-${System.nanoTime()}.bin")
    val t0 = System.nanoTime()
    val ab = AdBlocker(file, listOf(listOf("https://gh/pro"), listOf("https://gh/pop")), FakeNet(pages)::fetch)
    val ok = ab.update()
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("       · ${ab.list.size} domini in $ms ms, file ${file.length() / 1024} KB")
    check("elenco completo caricato", ok && ab.list.size > 150_000) { "size=${ab.list.size}" }
    val t1 = System.nanoTime()
    val again = AdBlocker(file, emptyList(), { throw IOException() })
    again.load()
    println("       · rilettura dal file in ${(System.nanoTime() - t1) / 1_000_000} ms")
    check("rilettura identica", again.list.size == ab.list.size)
    val ads = listOf("pagead2.googlesyndication.com", "c1.popads.net", "www.exoclick.com", "syndication.realsrv.com", "a.magsrv.com", "cdn.taboola.com")
    for (h in ads) check("bloccato $h", ab.isBlocked(h))
    val good = listOf(
        "www.google.com", "www.youtube.com", "rr3---sn-hp57kn6e.googlevideo.com", "fonts.gstatic.com", "www.rai.it",
        "www.raiplay.it", "mediasetinfinity.mediaset.it", "it.wikipedia.org", "cdn.jsdelivr.net", "cdnjs.cloudflare.com",
        "ssl.p.jwpcdn.com", "content.jwplatform.com", "player.vimeo.com", "www.dailymotion.com", "duckduckgo.com",
        "d1abc.cloudfront.net", "s3.amazonaws.com", "github.com", "www.imdb.com", "image.tmdb.org", "pluto.tv",
        "www.recaptcha.net", "challenges.cloudflare.com", "code.jquery.com", "unpkg.com",
    )
    val wrong = good.filter { ab.isBlocked(it) }
    check("nessun sito normale bloccato (${good.size} provati)", wrong.isEmpty()) { wrong.toString() }
    val t2 = System.nanoTime()
    var hits = 0
    repeat(200_000) { i -> if (ab.isBlocked("img$i.cdn.example-site.com")) hits++ }
    println("       · 200.000 controlli in ${(System.nanoTime() - t2) / 1_000_000} ms")
    check("nessun falso positivo su 200.000 host inventati", hits == 0) { "hits=$hits" }
    file.delete()
}
