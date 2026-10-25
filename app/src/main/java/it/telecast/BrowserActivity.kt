package it.telecast

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import it.telecast.core.CastStatus
import it.telecast.core.Hls
import it.telecast.core.HlsMaster
import it.telecast.core.Http
import it.telecast.core.Log
import it.telecast.core.MediaDetector
import it.telecast.core.MediaKind
import it.telecast.core.MediaSource
import it.telecast.core.NavShield
import it.telecast.core.PlayState
import it.telecast.core.Renderer
import it.telecast.core.Sites
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Schermata principale: un browser che intercetta i video delle pagine.
 * Si apre da sola quando da Chrome si sceglie Condividi → TeleCast.
 */
class BrowserActivity : Activity() {
    private val app get() = App.instance
    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var fab: LinearLayout
    private lateinit var fabIcon: ImageView
    private lateinit var fabText: TextView
    private lateinit var fabBadge: TextView
    private lateinit var nowBar: LinearLayout
    private lateinit var nowTitle: TextView
    private lateinit var nowSub: TextView
    private lateinit var nowBtn: ImageButton
    private lateinit var shieldIcon: ImageView
    private lateinit var shieldBadge: TextView
    private lateinit var blockedBar: BlockedBar

    @Volatile private var pageUrl: String? = null
    @Volatile private var pageTitle: String = ""
    @Volatile private var userAgent: String = Http.DEFAULT_UA
    private var lastCount = 0
    private val checkedCandidates = ConcurrentHashMap.newKeySet<String>()

    // blocco pubblicità
    @Volatile private var pageSite: String = ""
    @Volatile private var lastTap: NavShield.Tap? = null
    @Volatile private var lastAllowedAt = 0L
    @Volatile private var shieldOffSites: Set<String> = emptySet()
    private val stats = ShieldStats()
    private val recentBlocked = ArrayList<BlockedItem>()
    private val shieldUiPending = AtomicBoolean(false)

    /** Nome del ponte JavaScript diverso a ogni avvio: le pubblicità non possono indovinarlo. */
    private val bridgeName = "tc" + UUID.randomUUID().toString().replace("-", "").take(12)

    private val scanTask = object : Runnable {
        override fun run() {
            scanVideos()
            app.main.postDelayed(this, 4000)
        }
    }
    private val storeListener: () -> Unit = { updateFab() }
    private val statusListener: (CastStatus) -> Unit = { updateNowBar(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shieldOffSites = Prefs.shieldOffSites(this)
        val root = buildLayout()
        setContentView(root)
        Edge.apply(this, root)
        setupWebView()
        val restored = savedInstanceState?.let { web.restoreState(it) } != null
        if (!handleIntent(intent) && !restored) showStartPage()
        // la notifica con Pausa/Stop serve durante la trasmissione: chiedo il permesso una volta sola
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications(this) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Prefs.setAskedNotifications(this)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        app.detected.addListener(storeListener)
        app.addStatusListener(statusListener)
        updateFab()
        app.main.removeCallbacks(scanTask)
        app.main.postDelayed(scanTask, 2000)
    }

    override fun onPause() {
        app.main.removeCallbacks(scanTask)
        app.detected.removeListener(storeListener)
        app.removeStatusListener(statusListener)
        CookieManager.getInstance().flush()
        web.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    // ------------------------------------------------------------------ interfaccia

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        top.addView(iconButton(R.drawable.ic_arrow_back, C.TEXT, "Indietro").apply { setOnClickListener { goBack() } })
        urlBar = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(C.TEXT)
            setHintTextColor(C.DIM)
            hint = "Cerca o scrivi un indirizzo"
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
            background = roundRect(C.SURFACE2, dpf(22f))
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    go(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        top.addView(urlBar, lp(0, WRAP, 1f, l = dp(4), r = dp(4)))
        top.addView(buildShieldButton(), LinearLayout.LayoutParams(dp(44), dp(44)))
        top.addView(iconButton(R.drawable.ic_refresh, C.TEXT, "Ricarica").apply { setOnClickListener { web.reload() } })
        val more = iconButton(R.drawable.ic_more, C.TEXT, "Menu")
        more.setOnClickListener { showMenu(more) }
        top.addView(more)
        root.addView(top, lp(MATCH, WRAP))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(C.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            visibility = View.INVISIBLE
        }
        root.addView(progress, lp(MATCH, dp(3)))

        val content = FrameLayout(this)
        web = WebView(this).apply { setBackgroundColor(C.BG) }
        content.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))
        fab = buildFab()
        content.addView(
            fab,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(20)) },
        )
        blockedBar = BlockedBar(this)
        content.addView(
            blockedBar,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { setMargins(dp(10), dp(8), dp(10), 0) },
        )
        root.addView(content, lp(MATCH, 0, 1f))

        nowBar = buildNowBar()
        root.addView(nowBar, lp(MATCH, WRAP))
        return root
    }

    private fun buildFab(): LinearLayout {
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(14), dp(12))
            elevation = dpf(8f)
            isClickable = true
            isFocusable = true
            contentDescription = "Trasmetti alla TV"
            setOnClickListener { showMediaSheet() }
        }
        fabIcon = ImageView(this).apply { setImageResource(R.drawable.ic_cast) }
        f.addView(fabIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
        fabText = label("Trasmetti", 15f, C.TEXT, bold = true).apply { setPadding(dp(10), 0, dp(6), 0) }
        f.addView(fabText)
        fabBadge = label("0", 12f, C.ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(22)
            setPadding(dp(6), 0, dp(6), 0)
            background = roundRect(C.ON_ACCENT, dpf(11f))
        }
        f.addView(fabBadge, LinearLayout.LayoutParams(WRAP, dp(22)))
        return f
    }

    /** Scudo nella barra in alto: il numero è quanto ha bloccato sulla pagina. */
    private fun buildShieldButton(): View {
        val f = FrameLayout(this).apply {
            background = rippleOval()
            isClickable = true
            isFocusable = true
            contentDescription = "Blocco pubblicità"
            setOnClickListener { showShieldDialog() }
        }
        shieldIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_shield)
            imageTintList = ColorStateList.valueOf(C.ACCENT)
        }
        f.addView(shieldIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        shieldBadge = label("", 9f, C.ON_ACCENT, bold = true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(16)
            setPadding(dp(3), 0, dp(3), 0)
            background = roundRect(C.ACCENT, dpf(8f))
            visibility = View.GONE
        }
        f.addView(shieldBadge, FrameLayout.LayoutParams(WRAP, dp(16), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(3), dp(1), 0) })
        return f
    }

    private fun shieldOn(): Boolean = pageSite.isEmpty() || pageSite !in shieldOffSites

    private fun updateShieldUi() {
        val on = shieldOn()
        shieldIcon.imageTintList = ColorStateList.valueOf(if (on) C.ACCENT else C.DIM)
        shieldIcon.alpha = if (on) 1f else 0.6f
        val n = stats.total
        shieldBadge.visibility = if (on && n > 0) View.VISIBLE else View.GONE
        shieldBadge.text = if (n > 99) "99+" else n.toString()
    }

    /** Dai thread della WebView: aggiorna il contatore al massimo ogni 300 ms. */
    private fun scheduleShieldUi() {
        if (shieldUiPending.compareAndSet(false, true)) {
            app.main.postDelayed({
                shieldUiPending.set(false)
                updateShieldUi()
            }, 300)
        }
    }

    private fun updateFab() {
        val n = app.detected.size
        val active = n > 0
        fab.background = rippleRect(if (active) C.ACCENT else C.SURFACE2, dpf(28f))
        val fg = if (active) C.ON_ACCENT else C.DIM
        fabText.setTextColor(fg)
        fabIcon.imageTintList = ColorStateList.valueOf(fg)
        fabText.text = if (active) "Trasmetti" else "Nessun video"
        fabBadge.visibility = if (active) View.VISIBLE else View.GONE
        fabBadge.text = n.toString()
        if (n > lastCount) {
            fab.animate().scaleX(1.12f).scaleY(1.12f).setDuration(140).withEndAction {
                fab.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }.start()
        }
        lastCount = n
    }

    private fun buildNowBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            background = rippleRect(C.SURFACE, 0f)
            visibility = View.GONE
            isClickable = true
            setOnClickListener { openRemote() }
        }
        bar.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_tv)
                imageTintList = ColorStateList.valueOf(C.ACCENT)
            },
            LinearLayout.LayoutParams(dp(24), dp(24)),
        )
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        nowTitle = label("", 14f, C.TEXT, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        nowSub = label("", 12f, C.DIM).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        texts.addView(nowTitle)
        texts.addView(nowSub)
        bar.addView(texts, lp(0, WRAP, 1f))
        nowBtn = iconButton(R.drawable.ic_pause, C.TEXT, "Pausa o riprendi").apply { setOnClickListener { app.cast.togglePause() } }
        bar.addView(nowBtn)
        return bar
    }

    private fun updateNowBar(st: CastStatus) {
        nowBar.visibility = if (st.state != PlayState.IDLE) View.VISIBLE else View.GONE
        nowTitle.text = st.title
        nowSub.text = listOf(shortState(st), st.tvName).filter { it.isNotBlank() }.joinToString(" · ")
        val playing = st.state == PlayState.PLAYING || st.state == PlayState.BUFFERING || st.state == PlayState.CONNECTING
        nowBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    // ------------------------------------------------------------------ browser

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.safeBrowsingEnabled = true
        // ci presentiamo come Chrome: alcuni siti trattano male le WebView
        s.userAgentString = s.userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        userAgent = s.userAgentString
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), bridgeName)
        web.webViewClient = Client()
        web.webChromeClient = Chrome()
        web.setDownloadListener { url, _, _, mimetype, _ -> onDownload(url, mimetype) }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame) return null
            val host = request.url.host?.lowercase(Locale.ROOT) ?: ""
            // le risorse del sito stesso passano sempre; quelle dei circuiti pubblicitari no
            if (host.isNotEmpty() && shieldOn() && Sites.of(host) != pageSite && AdBlock.isBlocked(host)) {
                stats.ads.incrementAndGet()
                scheduleShieldUi()
                return emptyResponse()
            }
            onResource(request.url.toString(), request.requestHeaders ?: emptyMap())
            return null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            if (scheme != "http" && scheme != "https") {
                if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
                // intent://, market://, app esterne: sui siti di streaming sono quasi sempre pubblicità
                Log.d(TAG, "link $scheme: ignorato")
                return true
            }
            if (!request.isForMainFrame) return false
            val url = uri.toString()
            val now = SystemClock.elapsedRealtime()
            if (shieldOn()) {
                when (NavShield.decide(url, pageUrl, request.isRedirect, lastTap, lastAllowedAt, now, AdBlock::isBlocked)) {
                    NavShield.Verdict.AD -> {
                        noteBlocked(url, "Pagina pubblicitaria bloccata", stats.redirects)
                        return true
                    }
                    NavShield.Verdict.UNWANTED -> {
                        noteBlocked(url, "Reindirizzamento bloccato", stats.redirects)
                        return true
                    }
                    NavShield.Verdict.ALLOW -> Unit
                }
            }
            lastAllowedAt = now
            val kind = MediaDetector.classify(url)
            if (kind != null) {
                // link diretto a un video: invece di aprirlo lo metto nell'elenco
                addSource(url, kind, emptyMap())
                toast("Video trovato: tocca Trasmetti")
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            setPage(url)
            stats.reset()
            updateShieldUi()
            injectShield()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            injectShield()
        }

        override fun onPageFinished(view: WebView, url: String) {
            view.title?.takeIf { it.isNotBlank() && !it.startsWith("http") }?.let { pageTitle = it }
            setUrlBar(url)
            scanVideos()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            setPage(url)
        }
    }

    private fun setPage(url: String) {
        pageUrl = url.takeIf { it.startsWith("http") }
        pageSite = Sites.ofUrl(pageUrl)
        setUrlBar(url)
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Registra un blocco e mostra l'avviso con "Apri", nel caso servisse davvero. */
    private fun noteBlocked(url: String, what: String, counter: AtomicInteger) {
        counter.incrementAndGet()
        val host = Sites.hostOf(url).removePrefix("www.")
        Log.i(TAG, "$what: ${url.take(120)}")
        recentBlocked.removeAll { it.url == url }
        recentBlocked.add(BlockedItem(url, what))
        while (recentBlocked.size > 6) recentBlocked.removeAt(0)
        blockedBar.show("$what · $host") { openBlocked(url) }
        updateShieldUi()
    }

    private fun openBlocked(url: String) {
        val kind = MediaDetector.classify(url)
        if (kind != null) {
            addSource(url, kind, emptyMap())
            toast("Video trovato: tocca Trasmetti")
            return
        }
        Log.i(TAG, "aperto su richiesta: ${url.take(120)}")
        lastAllowedAt = SystemClock.elapsedRealtime()
        web.loadUrl(url)
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress.progress = newProgress
            progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank() && !title.startsWith("http")) pageTitle = title
        }

        /**
         * Nuove finestre: si aprono qui solo il link toccato davvero o una pagina dello stesso sito
         * (spesso il player); le altre sono quasi sempre pubblicità e vengono bloccate.
         * Alla pagina si dà comunque una finestra finta: così lo script pubblicitario si crede
         * soddisfatto e al tocco successivo parte il video.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val on = shieldOn()
            if (!isUserGesture) {
                // come Chrome: le finestre aperte senza un tocco non si aprono mai
                if (on) {
                    stats.popups.incrementAndGet()
                    scheduleShieldUi()
                }
                Log.d(TAG, "pop-up automatico bloccato")
                return false
            }
            val temp = WebView(this@BrowserActivity)
            var handled = false
            fun take(url: String) {
                if (handled || url.isBlank() || url.startsWith("about:")) return
                handled = true
                app.main.post { temp.destroy() }
                val now = SystemClock.elapsedRealtime()
                val verdict = if (on) NavShield.popup(url, pageUrl, AdBlock::isBlocked) else NavShield.Popup.OPEN_HERE
                val media = MediaDetector.classify(url)
                when {
                    verdict == NavShield.Popup.SAME_PAGE -> {
                        stats.popups.incrementAndGet()
                        updateShieldUi()
                        Log.d(TAG, "pop-under ignorato: ${url.take(120)}")
                    }
                    verdict == NavShield.Popup.AD -> noteBlocked(url, "Pop-up pubblicitario bloccato", stats.popups)
                    media != null -> addSource(url, media, emptyMap())
                    verdict == NavShield.Popup.OPEN_HERE -> {
                        lastAllowedAt = now
                        web.loadUrl(url)
                    }
                    else -> noteBlocked(url, "Pop-up bloccato", stats.popups)
                }
            }
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    take(r.url.toString())
                    return true
                }

                // la finestra finta non scarica nulla
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse = emptyResponse()

                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                    take(url)
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            app.main.postDelayed({
                if (!handled) {
                    handled = true
                    temp.destroy()
                }
            }, 5000)
            return true
        }

        // "Hai vinto un iPhone!" e simili: con il blocco attivo le finestre di avviso delle pagine non compaiono
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (!shieldOn()) return false
            Log.i(TAG, "avviso della pagina nascosto: ${message?.take(80)}")
            result.confirm()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (!shieldOn()) return false
            Log.i(TAG, "domanda della pagina rifiutata: ${message?.take(80)}")
            result.cancel()
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            if (!shieldOn()) return false
            result.cancel()
            return true
        }

        // niente "Vuoi davvero lasciare la pagina?": si esce sempre
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.confirm()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }
    }

    /** Ponte JavaScript: video trovati nella pagina, link toccati, controllo dei riquadri pubblicitari. */
    private inner class Bridge {
        @JavascriptInterface
        fun found(json: String, page: String, title: String) {
            val urls = try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.optString(it) }.filter { it.startsWith("http") }
            } catch (_: Exception) {
                return
            }
            app.main.post {
                if (pageTitle.isBlank() && title.isNotBlank()) pageTitle = title
                for (u in urls) {
                    if (MediaDetector.isAd(u)) continue
                    addSource(u, MediaDetector.classify(u) ?: MediaKind.FILE, mapOf("Referer" to page))
                }
            }
        }

        /** L'utente ha toccato un link vero (overlay = era uno strato trasparente sopra la pagina). */
        @JavascriptInterface
        fun tap(url: String, overlay: Boolean) {
            lastTap = NavShield.Tap(url, SystemClock.elapsedRealtime(), overlay)
        }

        @JavascriptInterface
        fun blocked(host: String): Boolean = shieldOn() && Sites.of(host) != pageSite && AdBlock.isBlocked(host)
    }

    /** Lo script dello scudo va rimesso a ogni pagina (non si duplica: controlla se c'è già). */
    private fun injectShield() {
        val u = web.url ?: return
        if (u.startsWith("http") && shieldOn()) web.evaluateJavascript(shieldJs(bridgeName), null)
    }

    /** Chiamato per ogni richiesta della pagina (su un thread della WebView). */
    private fun onResource(url: String, headers: Map<String, String>) {
        if (MediaDetector.isAd(url)) return
        val kind = MediaDetector.classify(url)
        if (kind != null) {
            app.main.post { addSource(url, kind, headers) }
            return
        }
        if (MediaDetector.isCandidate(url) && checkedCandidates.add(url)) {
            val probe = makeSource(url, MediaKind.HLS, headers)
            app.bg.execute {
                if (Enricher.isHlsPlaylist(url, probe)) app.main.post { addSource(url, MediaKind.HLS, headers) }
            }
        }
    }

    private fun makeSource(url: String, kind: MediaKind, headers: Map<String, String>): MediaSource {
        fun h(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val page = pageUrl
        return MediaSource(
            url = url,
            kind = kind,
            pageUrl = page,
            referer = h("Referer") ?: page,
            origin = h("Origin"),
            userAgent = userAgent,
            title = pageTitle.ifBlank { MediaDetector.fileName(url) },
            cookies = { u -> CookieManager.getInstance().getCookie(u) },
        )
    }

    private fun addSource(url: String, kind: MediaKind, headers: Map<String, String>) {
        val src = makeSource(url, kind, headers)
        if (app.detected.add(src)) {
            Log.i(TAG, "video trovato (${kind.name}): ${url.take(140)}")
            app.bg.execute { Enricher.probe(src, app.detected) }
        }
    }

    private fun scanVideos() {
        val u = web.url ?: return
        if (!u.startsWith("http")) return
        if (shieldOn()) web.evaluateJavascript(shieldJs(bridgeName) + sweepJs(bridgeName), null)
        web.evaluateJavascript(scanJs(bridgeName), null)
    }

    private fun onDownload(url: String, mime: String?) {
        val m = mime ?: ""
        val kind = MediaDetector.classify(url) ?: when {
            m.contains("mpegurl", ignoreCase = true) -> MediaKind.HLS
            m.startsWith("video/") -> MediaKind.FILE
            else -> null
        }
        if (kind != null) {
            addSource(url, kind, emptyMap())
            toast("Video trovato: tocca Trasmetti")
        } else {
            // file da scaricare che non sono video: sui siti di streaming sono quasi sempre app o "codec" truffa
            Log.i(TAG, "download ignorato ($m): ${url.take(120)}")
            blockedBar.show("Download bloccato · ${Sites.hostOf(url).removePrefix("www.")}", null)
        }
    }

    private fun setUrlBar(url: String?) {
        if (urlBar.hasFocus()) return
        urlBar.setText(if (url == null || !url.startsWith("http")) "" else url)
    }

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            !t.contains(' ') && t.contains('.') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        urlBar.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
        openUrl(url)
    }

    private fun openUrl(url: String) {
        app.detected.clear()
        checkedCandidates.clear()
        lastCount = 0
        pageTitle = ""
        lastAllowedAt = SystemClock.elapsedRealtime()
        web.loadUrl(url)
        setUrlBar(url)
    }

    private fun goBack() {
        if (web.canGoBack()) web.goBack() else showStartPage()
    }

    private fun showStartPage() {
        pageUrl = null
        pageTitle = ""
        web.loadDataWithBaseURL(null, START_HTML, "text/html", "utf-8", null)
        setUrlBar(null)
    }

    private fun handleIntent(i: Intent?): Boolean {
        if (i == null) return false
        when (i.action) {
            Intent.ACTION_SEND -> {
                val text = i.getStringExtra(Intent.EXTRA_TEXT) ?: return false
                val url = URL_RE.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'')
                if (url == null) {
                    toast("Nel testo condiviso non c'è un link")
                    return false
                }
                Log.i(TAG, "pagina ricevuta da un'altra app: $url")
                openUrl(url)
                i.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }?.let { pageTitle = it }
                return true
            }
            Intent.ACTION_VIEW -> {
                val data = i.dataString ?: return false
                val type = i.type ?: ""
                val kind = MediaDetector.classify(data) ?: when {
                    type.contains("mpegurl", ignoreCase = true) -> MediaKind.HLS
                    type.startsWith("video/") -> MediaKind.FILE
                    else -> null
                }
                if (kind == null) {
                    openUrl(data)
                    return true
                }
                if (web.url == null) showStartPage()
                addSource(data, kind, emptyMap())
                app.main.postDelayed({ showMediaSheet() }, 400)
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ menu e finestre

    private fun showMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Video trovati")
        pm.menu.add(0, 2, 1, "Le mie TV")
        pm.menu.add(0, 3, 2, "Telecomando")
        pm.menu.add(0, 4, 3, "Pagina iniziale")
        pm.menu.add(0, 5, 4, "Funziona a schermo spento")
        pm.menu.add(0, 7, 5, "Blocco pubblicità")
        pm.menu.add(0, 6, 6, "Log diagnostico")
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showMediaSheet()
                2 -> pickTv()
                3 -> openRemote()
                4 -> showStartPage()
                5 -> askBatteryExemption()
                6 -> LogDialog.show(this)
                7 -> showShieldDialog()
            }
            true
        }
        pm.show()
    }

    private fun showShieldDialog() {
        val site = pageSite
        val off = site.isNotEmpty() && !shieldOn()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(4))
        }
        val pageLine = when {
            site.isEmpty() -> "Blocca pubblicità, pop-up e pagine spam sui siti che apri qui."
            off -> "Disattivato su $site."
            else -> stats.describe()
        }
        box.addView(label(pageLine, 15f, C.TEXT))
        box.addView(label(AdBlock.describe(), 13f, C.DIM).apply { setPadding(0, dp(10), 0, 0) })
        var dlg: AlertDialog? = null
        if (recentBlocked.isNotEmpty()) {
            box.addView(label("Bloccati di recente: tocca per aprirli se servivano", 12f, C.DIM, bold = true).apply { setPadding(0, dp(18), 0, dp(6)) })
            for (item in recentBlocked.asReversed()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rippleRect(C.SURFACE2, dpf(12f))
                    isClickable = true
                    setOnClickListener {
                        dlg?.dismiss()
                        openBlocked(item.url)
                    }
                }
                row.addView(label(Sites.hostOf(item.url).removePrefix("www."), 14f, C.TEXT, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                row.addView(label(item.what, 12f, C.DIM))
                box.addView(row, lp(MATCH, WRAP, b = dp(6)))
            }
        }
        val b = dialog()
            .setTitle("Blocco pubblicità")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Chiudi", null)
            .setNegativeButton("Aggiorna elenco") { _, _ ->
                if (AdBlock.updating) {
                    toast("Aggiornamento già in corso")
                } else {
                    toast("Aggiorno l'elenco…")
                    AdBlock.updateNow(app) { ok ->
                        toast(if (ok) "Elenco aggiornato: ${AdBlock.number(AdBlock.size)} siti" else "Aggiornamento non riuscito: riprovo più tardi")
                    }
                }
            }
        if (site.isNotEmpty()) b.setNeutralButton(if (off) "Riattiva qui" else "Disattiva qui") { _, _ -> setShieldOff(site, !off) }
        dlg = b.show()
    }

    /** Se un sito non funziona col blocco attivo, lo si spegne solo lì. */
    private fun setShieldOff(site: String, off: Boolean) {
        Prefs.setShieldOff(this, site, off)
        shieldOffSites = Prefs.shieldOffSites(this)
        stats.reset()
        updateShieldUi()
        toast(if (off) "Blocco pubblicità disattivato su $site" else "Blocco pubblicità riattivato su $site")
        web.reload()
    }

    private fun pickTv() {
        DevicePicker(this, "Le mie TV") { r -> toast("${r.name}: la proporrò per prima") }.show()
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Già attivo: la trasmissione continua anche a schermo spento")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun showNoVideoHelp() {
        dialog()
            .setTitle("Nessun video trovato")
            .setMessage(
                "Fai partire il video nella pagina (basta un secondo): l'app lo intercetta e il pulsante diventa giallo.\n\n" +
                    "Se al primo tocco non parte, tocca di nuovo Play: spesso il primo tocco apre una pubblicità, che l'app blocca.\n\n" +
                    "I servizi protetti da DRM (Netflix, Prime Video, Disney+…) non si possono trasmettere così.",
            )
            .setPositiveButton("Ok", null)
            .show()
    }

    private fun showMediaSheet() {
        if (app.detected.list().isEmpty()) {
            showNoVideoHelp()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        var dlg: AlertDialog? = null
        val fill = {
            box.removeAllViews()
            for (src in app.detected.list()) {
                box.addView(
                    mediaRow(src) {
                        dlg?.dismiss()
                        chooseQualityThenCast(src)
                    },
                    lp(MATCH, WRAP, b = dp(8)),
                )
            }
        }
        fill()
        val listener: () -> Unit = { if (dlg?.isShowing == true) fill() }
        app.detected.addListener(listener)
        val d = dialog()
            .setTitle("Video trovati")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Chiudi", null)
            .create()
        d.setOnDismissListener { app.detected.removeListener(listener) }
        dlg = d
        d.show()
    }

    private fun mediaRow(src: MediaSource, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rippleRect(C.SURFACE2, dpf(14f))
            isClickable = true
            setOnClickListener { onClick() }
            if (src.error != null) alpha = 0.6f
        }
        val tag = when (src.kind) {
            MediaKind.HLS -> "HLS"
            MediaKind.FILE -> MediaDetector.extension(src.url).uppercase(Locale.ROOT).ifEmpty { "VIDEO" }.take(5)
        }
        row.addView(
            label(tag, 11f, C.ACCENT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(3), dp(4), dp(3))
                background = roundRect(Color.TRANSPARENT, dpf(6f), dp(1), C.ACCENT)
            },
            LinearLayout.LayoutParams(dp(48), WRAP),
        )
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(
            label(src.title, 15f, C.TEXT, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val err = src.error
        val sub = when {
            err != null -> "Non raggiungibile: $err"
            !src.probed -> listOf(src.host, "analisi…").filter { it.isNotBlank() }.joinToString(" · ")
            else -> listOfNotNull(src.host.ifBlank { null }, src.info).joinToString(" · ")
        }
        texts.addView(
            label(sub, 12f, if (err != null) C.DANGER else C.DIM).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        if (src.looksMain && err == null) {
            texts.addView(label("Probabilmente il film", 11f, C.OK, bold = true).apply { setPadding(0, dp(3), 0, 0) })
        }
        row.addView(texts, lp(0, WRAP, 1f))
        return row
    }

    private fun chooseQualityThenCast(src: MediaSource) {
        val vs = src.variants
        if (src.kind == MediaKind.HLS && vs.size > 1) {
            val best = Hls.pickVariant(HlsMaster(vs, emptyList()))
            val order = vs.indices.sortedByDescending { vs[it].height * 100_000L + vs[it].bandwidth / 1000 }
            val labels = order.map { i ->
                val v = vs[i]
                buildString {
                    append(v.label)
                    if (v.bandwidth > 0) append(String.format(Locale.ITALY, " · %.1f Mbps", v.bandwidth / 1_000_000.0))
                    if (v == best) append("  (consigliata)")
                }
            }.toTypedArray()
            dialog()
                .setTitle("Qualità")
                .setItems(labels) { _, which -> withTv { r -> startCast(r, src, order[which]) } }
                .setNegativeButton("Annulla", null)
                .show()
        } else {
            withTv { r -> startCast(r, src, null) }
        }
    }

    /** Ogni volta si sceglie dove guardare: in cima l'ultima TV usata, poi le altre trovate. */
    private fun withTv(cb: (Renderer) -> Unit) {
        DevicePicker(this, "Dove guardi il film?", onPick = cb).show()
    }

    private fun startCast(r: Renderer, src: MediaSource, variantIndex: Int?) {
        Prefs.saveTv(this, r)
        app.cast.cast(r, src, variantIndex)
        CastService.start(this)
        openRemote()
    }

    private fun openRemote() {
        startActivity(Intent(this, RemoteActivity::class.java))
    }

    companion object {
        private const val TAG = "Browser"
        private val URL_RE = Regex("""https?://[^\s"'<>]+""")

        /** Cerca i <video> della pagina e li segnala all'app. */
        fun scanJs(b: String) =
            "(function(B){try{var r=[];function a(s){if(s&&typeof s==='string'&&s.indexOf('http')===0&&r.indexOf(s)<0)r.push(s)}" +
                "document.querySelectorAll('video,audio').forEach(function(v){a(v.currentSrc);a(v.src);" +
                "v.querySelectorAll('source').forEach(function(x){a(x.src)})});" +
                "if(r.length&&B)B.found(JSON.stringify(r),location.href,document.title||'')}catch(e){}})(window['$b']);"

        /**
         * Registra i link toccati davvero dall'utente (non i clic simulati dagli script):
         * così l'app distingue un link vero da una pubblicità che porta via la pagina.
         * Un elemento grande, vuoto o trasparente sopra la pagina è uno strato pubblicitario.
         */
        fun shieldJs(b: String) =
            "(function(B){if(!B||window['_$b'])return;window['_$b']=1;" +
                "function ov(el){try{if(!el||el.nodeType!==1)return false;" +
                "if(/^(IMG|VIDEO|PICTURE|CANVAS|IFRAME|SVG)${'$'}/i.test(el.tagName))return false;" +
                "var r=el.getBoundingClientRect();if(r.width*r.height<0.35*Math.max(innerWidth,1)*Math.max(innerHeight,1))return false;" +
                "var cs=getComputedStyle(el);if(parseFloat(cs.opacity)<0.15)return true;" +
                "if((el.textContent||'').trim())return false;if(el.querySelector('img,video,picture,svg,canvas,iframe'))return false;" +
                "return !cs.backgroundImage||cs.backgroundImage==='none'}catch(e){return false}}" +
                "function link(e){var p=e.composedPath?e.composedPath():[];for(var i=0;i<p.length;i++){var n=p[i];" +
                "if(n&&(n.tagName==='A'||n.tagName==='AREA')&&n.href)return n}" +
                "var t=e.target;return t&&t.closest?t.closest('a[href],area[href]'):null}" +
                "window.addEventListener('click',function(e){if(!e.isTrusted)return;var a=link(e);if(!a)return;" +
                "var t=e.target;B.tap(String(a.href),ov(a)||(t!==a&&ov(t)))},true);" +
                "window.addEventListener('submit',function(e){var f=e.target;if(e.isTrusted&&f&&f.action)B.tap(String(f.action),false)},true);" +
                "})(window['$b']);"

        /** Nasconde i riquadri pubblicitari rimasti vuoti (iframe di siti in elenco). */
        fun sweepJs(b: String) =
            "(function(B){if(!B)return;try{var fs=document.getElementsByTagName('iframe');for(var i=0;i<fs.length;i++){" +
                "var f=fs[i],s=f.src||'';if(s.indexOf('http')!==0||f.__tcs===s)continue;f.__tcs=s;var h='';" +
                "try{h=new URL(s).hostname}catch(e){}if(h&&B.blocked(h))f.style.setProperty('display','none','important')}}catch(e){}" +
                "})(window['$b']);"

        private val START_HTML = """<!doctype html><html lang="it"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{margin:0;background:#0F1115;color:#F2F3F5;font-family:sans-serif;padding:28px 22px 40px}
h1{font-size:30px;margin:6px 0 4px;letter-spacing:-.5px}h1 span{color:#FFB21E}
p{color:#9AA0AB;line-height:1.5;margin:0 0 22px}
form{display:flex;gap:8px;margin:0 0 26px}
input{flex:1;min-width:0;font-size:16px;padding:13px 16px;border-radius:24px;border:1px solid #2E3442;background:#181B22;color:#F2F3F5;outline:none}
button{padding:13px 18px;border:0;border-radius:24px;background:#FFB21E;color:#1D1400;font-weight:700;font-size:16px}
.s{display:flex;gap:14px;align-items:flex-start;background:#181B22;border-radius:16px;padding:14px 16px;margin:10px 0;line-height:1.45}
.n{flex:none;width:26px;height:26px;border-radius:13px;background:#FFB21E;color:#1D1400;font-weight:700;text-align:center;line-height:26px}
small{display:block;color:#9AA0AB;margin-top:22px;line-height:1.5}
</style></head><body>
<h1>Tele<span>Cast</span></h1>
<p>I video dei siti sulle tue TV (LG, ok. Google TV e altre), senza mirroring.</p>
<form action="https://www.google.com/search"><input name="q" placeholder="Cerca un sito o un film" autocomplete="off"><button>Vai</button></form>
<div class="s"><div class="n">1</div><div>Apri il sito qui: <b>pubblicità, pop-up e pagine spam vengono bloccati</b>. Da Chrome puoi anche toccare <b>Condividi &rarr; TeleCast</b>.</div></div>
<div class="s"><div class="n">2</div><div>Fai partire il video nella pagina: l'app lo intercetta.</div></div>
<div class="s"><div class="n">3</div><div>Tocca il pulsante giallo <b>Trasmetti</b> e scegli la TV.</div></div>
<small>Il telefono fa da telecomando: puoi bloccarlo, la TV continua. Non funziona con i servizi protetti da DRM (Netflix, Prime Video, Disney+).</small>
</body></html>"""
    }
}
