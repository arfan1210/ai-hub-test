package com.arfan.aihub

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * AI Hub — a Home grid of eight AI services, each opening in one shared WebView.
 *
 * Only ONE WebView is ever alive: leaving the chat screen or switching service
 * destroys it and builds a fresh one. Login state still survives because the
 * shared CookieManager keeps first- and third-party cookies on disk (flushed in
 * [onPause]).
 */
class MainActivity : AppCompatActivity() {

    // ---- Views -------------------------------------------------------
    private lateinit var homeScreen: ScrollView
    private lateinit var chatScreen: View
    private lateinit var webContainer: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var desktopButton: ImageButton
    private lateinit var spinner: Spinner

    // ---- Review-loop views ------------------------------------------
    private lateinit var reviewBar: View
    private lateinit var btnReview: android.widget.Button
    private lateinit var btnStop: android.widget.Button
    private lateinit var btnCfg: android.widget.Button
    private lateinit var btnSenior: android.widget.Button
    private lateinit var btnAuto: android.widget.Button
    private lateinit var txtReviewStatus: TextView
    private var logOverlay: View? = null
    private var logText: TextView? = null
    private var settingsOverlay: ReviewSettingsView? = null

    // ---- State -------------------------------------------------------
    private var webView: WebView? = null
    private var currentService: AiService? = null

    /**
     * The review loop. It lives on the Activity — not inside the chat container
     * — so the Review/Stop/Cfg bar and the log survive every tab switch, and the
     * loop keeps its state while the single WebView is rebuilt underneath it.
     */
    private val review: ReviewController by lazy {
        ReviewController(this).also { controller ->
            controller.setListener(object : ReviewController.Listener {
                override fun onStatus(text: String) {
                    txtReviewStatus.text = text
                    reviewBar.visibility = View.VISIBLE
                }

                override fun onLog(line: String) {
                    ReviewLog.append(this@MainActivity, line)
                    refreshLog()
                }

                override fun onNeedsService(serviceId: String) {
                    val target = AiServices.findById(serviceId) ?: return
                    if (target.id == currentService?.id) return
                    // Reuses the normal tab switch: exactly one WebView stays
                    // alive and the controller is re-armed in installWebView().
                    openChat(target)
                }
            })
        }
    }

    /** True while the chat screen (not Home) is showing. */
    private var inChat = false

    /**
     * Index the spinner was last synchronised to. Spinner selection callbacks
     * are posted asynchronously, so a simple "set and clear" flag would already
     * be false by the time the callback runs. Comparing against this index is
     * reliable and also ignores the initial onItemSelected(0) fired at attach.
     */
    private var spinnerSelectedIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configureCookies()

        homeScreen = findViewById(R.id.home_screen)
        chatScreen = findViewById(R.id.chat_screen)
        webContainer = findViewById(R.id.web_container)
        titleView = findViewById(R.id.txt_title)
        desktopButton = findViewById(R.id.btn_desktop)
        spinner = findViewById(R.id.spinner)

        reviewBar = findViewById(R.id.review_bar)
        btnReview = findViewById(R.id.btn_review)
        btnStop = findViewById(R.id.btn_stop)
        btnCfg = findViewById(R.id.btn_cfg)
        btnSenior = findViewById(R.id.btn_senior)
        btnAuto = findViewById(R.id.btn_auto)
        txtReviewStatus = findViewById(R.id.txt_review_status)

        buildHomeGrid()
        wireToolbar()
        wireReviewControls()
        // Touch the lazy controller so the listener is attached before any
        // restored service installs a WebView.
        review

        val restored = AiServices.findById(savedInstanceState?.getString(STATE_SERVICE))
        if (restored != null) openChat(restored) else showHome()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentService?.let { outState.putString(STATE_SERVICE, it.id) }
    }

    override fun onPause() {
        super.onPause()
        // A run must not keep typing into a page the user cannot see. Its
        // progress is SAVED (never discarded) so it can be resumed, and the
        // wake flag is always cleared here.
        if (review.isRunning) review.pauseSession("app backgrounded")
        clearKeepScreenOn()
        // Persist cookies so logins survive process death.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        // pause/save (not stop/clear) so a resume survives process death; the
        // saved reviewer list is restored verbatim and nothing is re-sent.
        if (review.isRunning) review.pauseSession("activity destroyed")
        review.detach()
        clearKeepScreenOn()
        destroyWebView()
        super.onDestroy()
    }

    /**
     * Back: walk WebView history first, then return Home, then let the system
     * close the app.
     */
    @Deprecated("Overrides the framework method; kept for pre-33 back handling.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val wv = webView
        if (inChat && wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        if (inChat) {
            showHome()
            return
        }
        super.onBackPressed()
    }

    // ------------------------------------------------------------------
    // Home grid
    // ------------------------------------------------------------------

    /**
     * Lays the eight service cards out as 2-column rows inside a vertical
     * LinearLayout — simpler and more predictable than GridLayout on small
     * screens, and each row splits its width evenly.
     */
    private fun buildHomeGrid() {
        val grid = findViewById<LinearLayout>(R.id.grid_container)
        val gap = dp(10)

        AiServices.TABS.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            pair.forEach { service ->
                val card = ServiceCardView(this, service)
                card.setOnClickListener { openChat(service) }
                row.addView(card, LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    setMargins(gap, gap, gap, gap)
                })
            }

            // Keep the last card half-width when the count is odd.
            if (pair.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }

            grid.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun wireToolbar() {
        findViewById<ImageButton>(R.id.btn_home).setOnClickListener { showHome() }
        findViewById<ImageButton>(R.id.btn_reload).setOnClickListener { webView?.reload() }
        desktopButton.setOnClickListener { toggleDesktopMode() }

        spinner.adapter = buildSpinnerAdapter()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                // Ignore echoes of our own programmatic selection (and the
                // initial callback) — otherwise openChat() would re-run and
                // race with a desktop/mobile toggle the user just made.
                if (!inChat || position == spinnerSelectedIndex) return
                val target = AiServices.TABS.getOrNull(position) ?: return
                if (target.id == currentService?.id) {
                    spinnerSelectedIndex = position
                    return
                }
                openChat(target)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** Spinner entries: "Qwen · Planner" style, dark themed closed and expanded. */
    private fun buildSpinnerAdapter(): ArrayAdapter<String> {
        val labels = AiServices.TABS.map { "${it.label} · ${it.role}" }
        return object : ArrayAdapter<String>(this, R.layout.item_spinner, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                bindSpinnerRow(getItem(position), convertView, parent, closed = true)

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                bindSpinnerRow(getItem(position), convertView, parent, closed = false)
        }
    }

    private fun bindSpinnerRow(
        label: String?,
        convertView: View?,
        parent: ViewGroup,
        closed: Boolean
    ): View {
        val view = convertView ?: layoutInflater.inflate(R.layout.item_spinner, parent, false)
        (view as TextView).text = label
        // The closed spinner sits on the toolbar; the popup needs its own solid
        // dark background so rows stay readable.
        view.setBackgroundColor(if (closed) 0 else getColor(R.color.surface))
        return view
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    /** Show the Home grid and release the WebView. */
    private fun showHome() {
        // Leaving the chat tab stops the loop (nothing may run against a page
        // that is about to be destroyed) but SAVES the session, so it can be
        // resumed. Only the Stop button discards a session outright.
        if (review.isRunning) review.pauseSession("left chat")
        destroyWebView()
        currentService = null
        inChat = false
        chatScreen.visibility = View.GONE
        homeScreen.visibility = View.VISIBLE
    }

    /** Open [service] in the chat screen, replacing any existing WebView. */
    private fun openChat(service: AiService) {
        // installWebView() destroys any existing WebView before adding the new one.
        val previous = currentService?.id
        currentService = service
        // A *user* tab change abandons the running step. A tab change the loop
        // asked for (onNeedsService) must be allowed through, otherwise every
        // reviewer hop would cancel the run.
        if (previous != null && previous != service.id && !review.isAwaitingTab(service.id)) {
            // A user-driven switch stops the run; the session is kept for resume.
            if (review.isRunning) review.pauseSession("service changed")
        }
        inChat = true
        homeScreen.visibility = View.GONE
        chatScreen.visibility = View.VISIBLE
        syncSpinner(service)

        val desktop = ServicePrefs.isDesktop(this, service)
        installWebView(service, desktop)
    }

    /**
     * Destroys any current WebView and installs a fresh one for [service],
     * resuming at [resumeUrl] when the user is switching rendering mode.
     */
    private fun installWebView(
        service: AiService,
        desktop: Boolean,
        resumeUrl: String? = null
    ) {
        destroyWebView()
        val wv = WebViewFactory.create(this, service, desktop, resumeUrl)
        webContainer.addView(wv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        webView = wv
        updateToggleState(service, desktop)
        review.onWebViewReady(service.id, wv)
    }

    /**
     * Flip the current service between desktop and mobile, persist the choice,
     * and rebuild the WebView so the new UA and viewport take effect.
     */
    private fun toggleDesktopMode() {
        val service = currentService ?: return
        val next = !ServicePrefs.isDesktop(this, service)
        ServicePrefs.setDesktop(this, service, next)

        // Stay on the page the user is looking at, just re-rendered.
        val current = webView?.url
        val resume = current?.takeIf { it.startsWith("http") }
        installWebView(service, next, resume)
    }

    /** Reflects the active mode in the title and the toggle icon. */
    private fun updateToggleState(service: AiService, desktop: Boolean) {
        titleView.text = service.label
        // Desktop icon when currently in desktop mode, phone icon in mobile mode.
        desktopButton.setImageResource(
            if (desktop) R.drawable.ic_desktop else R.drawable.ic_mobile
        )
        desktopButton.contentDescription = getString(
            R.string.cd_desktop_toggle
        ) + if (desktop) " (currently desktop)" else " (currently mobile)"
    }

    private fun syncSpinner(service: AiService) {
        val position = AiServices.TABS.indexOfFirst { it.id == service.id }
        if (position < 0) return
        spinnerSelectedIndex = position
        if (spinner.selectedItemPosition != position) {
            spinner.setSelection(position, false)
        }
    }

    // ------------------------------------------------------------------
    // WebView lifecycle
    // ------------------------------------------------------------------

    private fun destroyWebView() {
        webView?.let { wv ->
            webContainer.removeView(wv)
            wv.stopLoading()
            wv.webChromeClient = null
            // webViewClient has no nullable setter; a no-op client releases the
            // Activity reference held by our real client before destroy().
            wv.webViewClient = WebViewClient()
            wv.destroy()
        }
        webView = null
    }

    // ------------------------------------------------------------------
    // Review loop UI
    // ------------------------------------------------------------------

    /**
     * The review bar is a child of the chat screen but is *sibling* to the
     * WebView container, and it is never removed on a tab switch — that is what
     * keeps Review/Stop/Cfg reachable after hopping services.
     */
    private fun wireReviewControls() {
        btnReview.setOnClickListener { startManualRound() }
        btnStop.setOnClickListener {
            review.cancel("stopped by user")
            showStatus("Stopped")
        }
        btnSenior.setOnClickListener { startSeniorRound() }
        btnAuto.setOnClickListener { toggleAutoMode() }
        btnCfg.setOnClickListener { openSettings() }
        txtReviewStatus.setOnClickListener { toggleLog() }
        txtReviewStatus.text = "Idle — tap to see the log"
    }

    /**
     * Manual review: needs the Qwen tab because the task and the answer to
     * review live there. Rather than silently switching (which would throw away
     * what the user is reading) we ask once, then run.
     */
    private fun startManualRound() {
        if (!ensureQwen("Review")) return
        review.startManualRound()
    }

    private fun startSeniorRound() {
        if (!ensureQwen("Senior")) return
        review.startSeniorEscalation(manual = true)
    }

    private fun toggleAutoMode() {
        if (review.isRunning) {
            review.cancel("auto stopped")
            showStatus("Auto stopped")
            return
        }
        ReviewSettings(this).resetDailyIfNeeded()
        if (ReviewSettings(this).activeReviewerIds().isEmpty()) {
            showStatus("No reviewers enabled — open Cfg")
            return
        }
        if (!ensureQwen("Auto")) return
        review.startAutoLoop()
    }

    /**
     * Guarantees the Qwen tab is open before a run starts. Returns false when it
     * had to switch tabs, telling the user to press the button again so nothing
     * starts behind a tab they did not choose.
     */
    private fun ensureQwen(action: String): Boolean {
        if (currentService?.id == ReviewController.QWEN) return true
        val qwen = AiServices.findById(ReviewController.QWEN)
        if (qwen == null) {
            showStatus("Qwen service missing")
            return false
        }
        openChat(qwen)
        showStatus("$action needs Qwen — tap it again")
        return false
    }

    /**
     * Opens the settings overlay. Rows are rebuilt from [ReviewSettings] on every
     * open (ReviewSettingsView.refresh() in init), so the state can never be
     * stale, and the overlay removes itself on Close.
     */
    private fun openSettings() {
        if (settingsOverlay != null) return
        val overlay = ReviewSettingsView(this, ReviewSettings(this))
        overlay.onResume = { review.resumeSaved() }
        overlay.hasSavedSession = { review.hasSavedSession() }
        overlay.onDismiss = { settingsOverlay = null }
        settingsOverlay = overlay
        addOverlay(overlay)
    }

    /** Adds a full-screen overlay above everything (Home and chat alike). */
    private fun addOverlay(view: View) {
        (findViewById<ViewGroup>(R.id.root)).addView(
            view,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** Expand/collapse the log panel. The text itself is always appended. */
    private fun toggleLog() {
        val existing = logOverlay
        if (existing != null) {
            (existing.parent as? android.view.ViewGroup)?.removeView(existing)
            logOverlay = null
            logText = null
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        panel.addView(TextView(this).apply {
            text = "Review log"
            setTextColor(getColor(R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val scroller = ScrollView(this)
        val body = TextView(this).apply {
            setTextColor(getColor(R.color.text_muted))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroller.addView(body, ScrollView.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        panel.addView(scroller, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(8) })
        panel.addView(android.widget.Button(this).apply {
            text = "Close log"
            setOnClickListener { toggleLog() }
        })
        logText = body
        logOverlay = panel
        refreshLog()
        addOverlay(panel)
    }

    private fun refreshLog() {
        val body = logText ?: return
        body.text = ReviewLog.text(this).ifBlank { "(no entries yet)" }
    }

    private fun showStatus(text: String) {
        txtReviewStatus.text = text
    }

    // ------------------------------------------------------------------
    // Screen wake lock flag (no permission needed)
    // ------------------------------------------------------------------

    fun setKeepScreenOn(keepOn: Boolean) {
        if (keepOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun clearKeepScreenOn() = setKeepScreenOn(false)

    /** Wired from the settings overlay: resume a saved session. */
    internal fun resumeSavedSession() = review.resumeSaved()

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Accept first- and third-party cookies globally (persisted logins). */
    private fun configureCookies() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.flush()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_SERVICE = "current_service"
    }
}
