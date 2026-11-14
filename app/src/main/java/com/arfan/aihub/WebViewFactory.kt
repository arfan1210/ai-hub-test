package com.arfan.aihub

import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Factory for the app's single live WebView.
 *
 * Keeping construction here means the Activity only manages *lifecycle* and the
 * WebView is always configured identically, whichever service created it.
 */
object WebViewFactory {

    /**
     * Chrome on Windows desktop. Sent only for services rendered in desktop mode;
     * mobile mode keeps the WebView's own (already `; wv`-stripped) UA.
     */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Viewport width forced in desktop mode so sites lay out as on a laptop. */
    private const val DESKTOP_VIEWPORT_WIDTH = 1280

    /**
     * Creates and configures a WebView for [service], applying [desktop] mode,
     * then starts loading the service's URL.
     */
    fun create(
        activity: MainActivity,
        service: AiService,
        desktop: Boolean,
        startUrl: String? = null
    ): WebView {
        val wv = object : WebView(activity) {
            // Keep hardware keyboard/IME behaviour working inside the page.
            override fun onCheckIsTextEditor(): Boolean = true
        }

        configure(wv, activity, desktop)
        attachClients(wv, desktop)
        // Passing an explicit URL keeps the user on the page they were viewing
        // when they flip between desktop and mobile mode.
        wv.loadUrl(startUrl ?: service.url)
        return wv
    }

    /** Applies WebSettings, user agent and desktop-mode viewport behaviour. */
    private fun configure(wv: WebView, activity: MainActivity, desktop: Boolean) {
        val mobileUa = WebSettings.getDefaultUserAgent(activity).replace("; wv", "")

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
            builtInZoomControls = false
            displayZoomControls = false

            if (desktop) {
                userAgentString = DESKTOP_USER_AGENT
                useWideViewPort = true
                loadWithOverviewMode = true
            } else {
                // Strip the "; wv" marker so sites (notably Google sign-in) treat
                // the WebView as a regular mobile Chrome browser.
                userAgentString = mobileUa
                useWideViewPort = false
                loadWithOverviewMode = false
            }
        }

        // Third-party cookies are required for the login flows on these sites.
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
    }

    private fun attachClients(wv: WebView, desktop: Boolean) {
        wv.webChromeClient = WebChromeClient()

        wv.webViewClient = object : WebViewClient() {
            /** Every http(s) navigation stays in this WebView. */
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme ?: return false
                return when (scheme) {
                    "http", "https" -> {
                        view.loadUrl(request.url.toString())
                        true
                    }
                    // Let tel:, mailto:, intent: etc. reach the system.
                    else -> false
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                if (desktop) forceDesktopViewport(view)
            }
        }
    }

    /**
     * Replaces (or inserts) the page's viewport meta tag with `width=1280` so
     * responsive sites pick their desktop breakpoints inside a phone-width
     * window. Runs after every page finish because SPAs rewrite the head.
     */
    private fun forceDesktopViewport(view: WebView) {
        val js = """
            (function() {
              try {
                var head = document.head || document.getElementsByTagName('head')[0];
                if (!head) return;
                var metas = head.getElementsByTagName('meta');
                for (var i = metas.length - 1; i >= 0; i--) {
                  var m = metas[i];
                  if (m && (m.getAttribute('name') || '').toLowerCase() === 'viewport') {
                    head.removeChild(m);
                  }
                }
                var meta = document.createElement('meta');
                meta.setAttribute('name', 'viewport');
                meta.setAttribute('content', 'width=$DESKTOP_VIEWPORT_WIDTH');
                head.appendChild(meta);
              } catch (e) { /* page may be cross-origin or not ready */ }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }
}
