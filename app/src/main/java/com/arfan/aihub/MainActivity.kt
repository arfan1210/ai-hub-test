package com.arfan.aihub

import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.tabs.TabLayout

/**
 * AI Hub — hosts seven AI chat services, one WebView per tab.
 *
 * Only the ACTIVE tab has a live WebView: switching tabs destroys the previous
 * WebView and creates + loads a new one. This keeps memory usage low on
 * low-RAM (3 GB) devices. Login state survives because cookies are persisted
 * by the shared CookieManager and flushed in onPause().
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var container: FrameLayout

    /** The single live WebView of the currently selected tab, or null. */
    private var webView: WebView? = null

    /** Index of the currently selected tab. */
    private var currentIndex: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        configureCookies()

        tabLayout = findViewById(R.id.tab_layout)
        container = findViewById(R.id.web_container)

        for (tab in AiServices.TABS) {
            tabLayout.addTab(tabLayout.newTab().setText(tab.label))
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                // Reload when the user taps the already-active tab.
                webView?.reload()
            }
        })

        currentIndex = savedInstanceState?.getInt(STATE_INDEX) ?: 0
        showTab(currentIndex)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_INDEX, currentIndex)
    }

    override fun onPause() {
        super.onPause()
        // Persist cookies so logins survive app restarts.
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        destroyWebView()
        super.onDestroy()
    }

    /**
     * Back button: navigate the WebView back if it can; otherwise jump to the
     * previous tab. On the first tab with no history, let the system handle it
     * (i.e. exit the app).
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
            return
        }
        if (currentIndex > 0) {
            tabLayout.getTabAt(currentIndex - 1)?.select()
            return
        }
        super.onBackPressed()
    }

    // ------------------------------------------------------------------
    // Tab / WebView management
    // ------------------------------------------------------------------

    /** Destroy the old WebView and create a fresh one for [index]. */
    private fun showTab(index: Int) {
        destroyWebView()
        currentIndex = index
        val service = AiServices.TABS[index]
        val wv = createWebView(service.url)
        container.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        webView = wv
    }

    private fun destroyWebView() {
        webView?.let { wv ->
            container.removeView(wv)
            wv.stopLoading()
            wv.webChromeClient = null
            wv.destroy()
        }
        webView = null
    }

    private fun createWebView(url: String): WebView {
        val wv = object : WebView(this) {
            // Keep hardware keys working inside the WebView.
            override fun onCheckIsTextEditor(): Boolean = true
        }

        val defaultUa = WebSettings.getDefaultUserAgent(this)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Strip "; wv" from the default UA so sites (especially Google
            // sign-in) treat us like a regular Chrome browser.
            userAgentString = desktopUserAgent(defaultUa)
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
        }

        wv.webChromeClient = WebChromeClient()

        wv.webViewClient = object : WebViewClient() {
            /** All links stay inside this WebView. */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Allow app-schema / mailto etc. to fall through to the system,
                // but keep every http(s) navigation in-app.
                val scheme = request.url.scheme ?: return false
                return when (scheme) {
                    "http", "https" -> {
                        view.loadUrl(request.url.toString())
                        true
                    }
                    else -> false
                }
            }
        }

        // Third-party cookies must be enabled on the WebView itself as well.
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.loadUrl(url)
        return wv
    }

    /** Accept first- and third-party cookies globally (persisted logins). */
    private fun configureCookies() {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.flush()
    }

    companion object {
        private const val STATE_INDEX = "current_tab_index"

        /** Remove the WebView marker ("; wv") from a user-agent string. */
        fun desktopUserAgent(defaultUa: String): String =
            defaultUa.replace("; wv", "")
    }
}
