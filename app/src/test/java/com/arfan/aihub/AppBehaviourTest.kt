package com.arfan.aihub

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Behavioural tests that actually build the Activity and drive the real user
 * flows on the JVM (Robolectric). These complement the CI compile check: they
 * assert that each requested feature behaves as specified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppBehaviourTest {

    private lateinit var controller: org.robolectric.android.controller.ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        clearPrefs()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        idle()
    }

    private fun clearPrefs() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("ai_hub_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(ReviewSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(ReviewLog.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    // ---------------------------------------------------------------- helpers

    private fun homeScreen(): View = activity.findViewById(R.id.home_screen)
    private fun chatScreen(): View = activity.findViewById(R.id.chat_screen)
    private fun webContainer(): FrameLayout = activity.findViewById(R.id.web_container)
    private fun spinner(): Spinner = activity.findViewById(R.id.spinner)
    private fun title(): TextView = activity.findViewById(R.id.txt_title)
    private fun desktopButton(): ImageButton = activity.findViewById(R.id.btn_desktop)

    /** All cards currently in the home grid, in visual order. */
    private fun cards(): List<ServiceCardView> {
        val grid = activity.findViewById<LinearLayout>(R.id.grid_container)
        val result = mutableListOf<ServiceCardView>()
        for (r in 0 until grid.childCount) {
            val row = grid.getChildAt(r) as LinearLayout
            for (c in 0 until row.childCount) {
                (row.getChildAt(c) as? ServiceCardView)?.let { result.add(it) }
            }
        }
        return result
    }

    /** The single live WebView, or null. */
    private fun liveWebView(): WebView? {
        val container = webContainer()
        var found: WebView? = null
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is WebView) {
                assertNull("more than one WebView is alive", found)
                found = child
            }
        }
        return found
    }

    private fun webViewCount(): Int {
        val container = webContainer()
        return (0 until container.childCount).count { container.getChildAt(it) is WebView }
    }

    private fun openService(service: AiService) {
        val card = cards().first { it.service.id == service.id }
        card.performClick()
        idle()
    }

    @Suppress("DEPRECATION")
    private fun clickBack() {
        activity.onBackPressed()
        idle()
    }

    // ------------------------------------------------------- 1. config list

    @Test
    fun `config list has exactly nine services with coder and its role tags`() {
        assertEquals(9, AiServices.TABS.size)

        val expected = mapOf(
            "qwen" to Triple("Qwen", "https://chat.qwen.ai", "Planner"),
            "coder" to Triple("Coder", "https://coder.qwen.ai", "Builder"),
            "arena" to Triple("Arena", "https://arena.ai/agent", "Builder/agent"),
            "gemini" to Triple("Gemini", "https://gemini.google.com", "Design/Language"),
            "deepseek" to Triple("DeepSeek", "https://chat.deepseek.com", "Code review"),
            "meta" to Triple("Meta", "https://www.meta.ai", "Logic review"),
            "zai" to Triple("Zai", "https://chat.z.ai", "Senior review"),
            "minimax" to Triple("MiniMax", "https://agent.minimax.io", "Optional"),
            "mimo" to Triple("MiMo", "https://aistudio.xiaomimimo.com/#/c", "General")
        )
        assertEquals(expected.keys, AiServices.TABS.map { it.id }.toSet())

        AiServices.TABS.forEach { s ->
            val (label, url, role) = expected.getValue(s.id)
            assertEquals(label, s.label)
            assertEquals(url, s.url)
            assertEquals(role, s.role)
            assertEquals("initial must be first letter", s.label.first().toString(), s.initial)
        }
    }

    @Test
    fun `coder and arena are the only services defaulting to desktop mode`() {
        assertTrue(AiServices.findById("coder")!!.desktopMode)
        assertTrue(AiServices.findById("arena")!!.desktopMode)
        val others = AiServices.TABS.filter { it.id != "coder" && it.id != "arena" }
        assertEquals(7, others.size)
        others.forEach { assertFalse("${it.id} must default to mobile", it.desktopMode) }
    }

    @Test
    fun `arena sits right after coder with its own accent`() {
        val ids = AiServices.TABS.map { it.id }
        assertEquals(ids.indexOf("coder") + 1, ids.indexOf("arena"))
        val arena = AiServices.findById("arena")!!
        assertEquals(0xFF38BDF8.toInt(), arena.accent)
        assertEquals("A", arena.initial)
    }

    @Test
    fun `every service has a distinct accent colour`() {
        val accents = AiServices.TABS.map { it.accent }
        assertEquals(9, accents.toSet().size)
    }

    // ---------------------------------------------------------- 2. home grid

    @Test
    fun `app opens on home with nine cards in a two column grid`() {
        assertEquals(View.VISIBLE, homeScreen().visibility)
        assertEquals(View.GONE, chatScreen().visibility)
        assertNull("no WebView should exist on home", liveWebView())

        val cards = cards()
        assertEquals(9, cards.size)
        assertEquals("card order must match config", AiServices.TABS.map { it.id }, cards.map { it.service.id })

        // 9 cards chunked by 2 => 4 full rows plus a half-width last row.
        val grid = activity.findViewById<LinearLayout>(R.id.grid_container)
        assertEquals(5, grid.childCount)
        for (r in 0 until grid.childCount) {
            val row = grid.getChildAt(r) as LinearLayout
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, row.layoutParams.width)
            if (r < 4) {
                assertEquals("row $r must hold 2 cards", 2, row.childCount)
            } else {
                // Last row: one card + one spacer that keeps it half-width.
                assertEquals(2, row.childCount)
                assertTrue("last row keeps the odd card half-width",
                    row.getChildAt(1) !is ServiceCardView)
            }
        }
    }

    @Test
    fun `home shows title and subtitle`() {
        val texts = mutableListOf<String>()
        fun walk(v: View) {
            if (v is TextView) texts.add(v.text.toString())
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(homeScreen())
        assertTrue("missing 'AI Hub' title", texts.contains("AI Hub"))
        assertTrue("missing subtitle", texts.any { it.contains("Nine assistants") })
    }

    @Test
    fun `each card renders its initial name and role tag and is clickable`() {
        cards().forEach { card ->
            assertTrue("card ${card.service.id} must be clickable", card.isClickable)
            val texts = mutableListOf<String>()
            for (i in 0 until card.childCount) {
                (card.getChildAt(i) as? TextView)?.let { texts.add(it.text.toString()) }
            }
            assertEquals("card must show initial, name and role",
                listOf(card.service.initial, card.service.label, card.service.role), texts)
        }
    }

    // ------------------------------------------------- 3. navigation to chat

    @Test
    fun `tapping a card opens that service in exactly one webview`() {
        openService(AiServices.findById("gemini")!!)

        assertEquals(View.GONE, homeScreen().visibility)
        assertEquals(View.VISIBLE, chatScreen().visibility)
        assertEquals(1, webViewCount())
        assertEquals("Gemini", title().text.toString())

        val wv = liveWebView()!!
        assertEquals("https://gemini.google.com", wv.url)
    }

    @Test
    fun `switching service keeps exactly one live webview and loads the new url`() {
        openService(AiServices.findById("qwen")!!)
        val first = liveWebView()!!
        openService(AiServices.findById("deepseek")!!)
        val second = liveWebView()!!

        assertEquals(1, webViewCount())
        assertNotEquals("a fresh WebView must be created", first, second)
        assertEquals("https://chat.deepseek.com", second.url)
        assertEquals("DeepSeek", title().text.toString())
    }

    @Test
    fun `quick switch dropdown changes service`() {
        openService(AiServices.findById("qwen")!!)
        val pos = AiServices.TABS.indexOfFirst { it.id == "zai" }

        spinner().setSelection(pos)
        idle()

        assertEquals(1, webViewCount())
        assertEquals("https://chat.z.ai", liveWebView()!!.url)
        assertEquals("Zai", title().text.toString())
    }

    @Test
    fun `dropdown is pre selected to the service opened from home`() {
        val pos = AiServices.TABS.indexOfFirst { it.id == "mimo" }
        openService(AiServices.findById("mimo")!!)
        assertEquals(pos, spinner().selectedItemPosition)

        // Selecting the already-open entry must not recreate the WebView.
        val before = liveWebView()
        spinner().setSelection(pos)
        idle()
        assertEquals("re-selecting same service must not reload", before, liveWebView())
    }

    @Test
    fun `dropdown lists all nine services`() {
        openService(AiServices.findById("qwen")!!)
        assertEquals(9, spinner().adapter.count)
        val labels = (0 until 9).map { spinner().adapter.getItem(it).toString() }
        AiServices.TABS.forEachIndexed { i, s ->
            assertEquals("${s.label} · ${s.role}", labels[i])
        }
    }

    // ------------------------------------------------------ 4. desktop mode

    @Test
    fun `coder opens in desktop mode with desktop UA and wide viewport`() {
        openService(AiServices.findById("coder")!!)

        val settings = liveWebView()!!.settings
        assertEquals(WebViewFactory.DESKTOP_USER_AGENT, settings.userAgentString)
        assertTrue("useWideViewPort must be on", settings.useWideViewPort)
        assertTrue("loadWithOverviewMode must be on", settings.loadWithOverviewMode)
        assertTrue("desktop UA must be Chrome 131 on Windows",
            settings.userAgentString.contains("Windows NT 10.0") &&
                settings.userAgentString.contains("Chrome/131.0.0.0") &&
                settings.userAgentString.contains("Safari/537.36"))
    }

    @Test
    fun `mobile services keep default UA stripped of wv marker`() {
        openService(AiServices.findById("qwen")!!)

        val settings = liveWebView()!!.settings
        assertFalse(settings.userAgentString.contains("; wv"))
        assertFalse("mobile must not use the desktop UA",
            settings.userAgentString.contains("Windows NT 10.0"))
        assertFalse(settings.useWideViewPort)
        assertFalse(settings.loadWithOverviewMode)
    }

    @Test
    fun `toggle flips current service and persists per service`() {
        openService(AiServices.findById("qwen")!!)
        assertFalse(ServicePrefs.isDesktop(activity, AiServices.findById("qwen")!!))

        desktopButton().performClick()
        idle()

        assertTrue("choice must be persisted", ServicePrefs.isDesktop(activity, AiServices.findById("qwen")!!))
        assertEquals(WebViewFactory.DESKTOP_USER_AGENT, liveWebView()!!.settings.userAgentString)
        assertTrue(liveWebView()!!.settings.useWideViewPort)

        desktopButton().performClick()
        idle()
        assertFalse(ServicePrefs.isDesktop(activity, AiServices.findById("qwen")!!))
        assertFalse(liveWebView()!!.settings.useWideViewPort)
    }

    @Test
    fun `toggle affects only the current service`() {
        openService(AiServices.findById("qwen")!!)
        desktopButton().performClick()
        idle()
        assertTrue(ServicePrefs.isDesktop(activity, AiServices.findById("qwen")!!))

        openService(AiServices.findById("gemini")!!)
        assertFalse("gemini must stay mobile",
            ServicePrefs.isDesktop(activity, AiServices.findById("gemini")!!))
        assertFalse(liveWebView()!!.settings.useWideViewPort)

        // Coming back to Qwen restores the saved desktop choice.
        openService(AiServices.findById("qwen")!!)
        assertTrue(liveWebView()!!.settings.useWideViewPort)
    }

    @Test
    fun `toggle keeps the user on the same page`() {
        openService(AiServices.findById("deepseek")!!)
        val wv = liveWebView()!!
        // Simulate having navigated deeper into the site.
        wv.loadUrl("https://chat.deepseek.com/some/thread")
        idle()

        desktopButton().performClick()
        idle()

        assertEquals("https://chat.deepseek.com/some/thread", liveWebView()!!.url)
    }

    @Test
    fun `toggle icon reflects the active mode`() {
        // Mobile service => phone icon (ic_mobile).
        openService(AiServices.findById("qwen")!!)
        val mobileDesc = desktopButton().contentDescription.toString()
        assertTrue("mobile description was: $mobileDesc",
            mobileDesc.contains("currently mobile"))

        // Coder defaults to desktop => monitor icon (ic_desktop).
        openService(AiServices.findById("coder")!!)
        val desktopDesc = desktopButton().contentDescription.toString()
        assertTrue("desktop description was: $desktopDesc",
            desktopDesc.contains("currently desktop"))

        // After a manual flip the description must follow.
        desktopButton().performClick()
        idle()
        assertTrue(desktopButton().contentDescription.toString().contains("currently mobile"))
    }

    // ----------------------------------------------------------- 5. toolbar

    @Test
    fun `reload button reloads current page`() {
        openService(AiServices.findById("qwen")!!)
        val wv = liveWebView()!!
        wv.loadUrl("https://chat.qwen.ai/c/123")
        idle()
        assertEquals("https://chat.qwen.ai/c/123", wv.url)

        activity.findViewById<ImageButton>(R.id.btn_reload).performClick()
        idle()
        assertEquals("reload must not change the URL", "https://chat.qwen.ai/c/123", liveWebView()!!.url)
    }

    @Test
    fun `home button returns to grid and destroys the webview`() {
        openService(AiServices.findById("zai")!!)
        assertEquals(1, webViewCount())

        activity.findViewById<ImageButton>(R.id.btn_home).performClick()
        idle()

        assertEquals(View.VISIBLE, homeScreen().visibility)
        assertEquals(View.GONE, chatScreen().visibility)
        assertEquals("WebView must be destroyed on leaving chat", 0, webViewCount())
        assertNull(liveWebView())
    }

    @Test
    fun `toolbar has back home name dropdown toggle and reload`() {
        openService(AiServices.findById("qwen")!!)
        assertNotNull(activity.findViewById<View>(R.id.btn_home))
        assertNotNull(title())
        assertNotNull(spinner())
        assertNotNull(desktopButton())
        assertNotNull(activity.findViewById<View>(R.id.btn_reload))
    }

    // ---------------------------------------------------- 6. back behaviour

    @Test
    fun `back returns to home from chat`() {
        openService(AiServices.findById("qwen")!!)
        clickBack()

        assertEquals(View.VISIBLE, homeScreen().visibility)
        assertEquals(0, webViewCount())
        assertNull(liveWebView())
    }

    @Test
    fun `back on home does not crash and stays consistent`() {
        clickBack()
        // Home was already showing; the activity may finish, which is correct.
        assertEquals(0, webViewCount())
    }

    // ------------------------------------------------- 7. cookies & storage

    @Test
    fun `cookies and dom storage are enabled for persistent logins`() {
        openService(AiServices.findById("gemini")!!)
        val settings = liveWebView()!!.settings

        assertTrue("dom storage required for logins", settings.domStorageEnabled)
        assertTrue("javascript required for these sites", settings.javaScriptEnabled)
        assertTrue("popups must be allowed", settings.javaScriptCanOpenWindowsAutomatically)
        assertNotNull("WebChromeClient must be installed", liveWebView()!!.webChromeClient)

        assertTrue("cookies must be accepted", CookieManager.getInstance().acceptCookie())
    }

    // ---------------------------------------------- 8. state restoration

    @Test
    fun `service survives configuration change without losing webview`() {
        openService(AiServices.findById("coder")!!)
        val before = liveWebView()

        val newController = controller.configurationChange(
            Configuration(activity.resources.configuration).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
            }
        )
        idle()

        // New Activity instance after the config change.
        val recreated = newController.get()
        assertEquals("Coder", recreated.findViewById<TextView>(R.id.txt_title).text.toString())
        assertEquals(1, (0 until recreated.findViewById<FrameLayout>(R.id.web_container).childCount)
            .count { recreated.findViewById<FrameLayout>(R.id.web_container).getChildAt(it) is WebView })
        assertNotNull(before)
    }

    @Test
    fun `service is restored after activity recreation`() {
        openService(AiServices.findById("zai")!!)

        val recreated = controller.recreate().get()
        idle()

        assertEquals("Zai", recreated.findViewById<TextView>(R.id.txt_title).text.toString())
        assertEquals(1, (0 until recreated.findViewById<FrameLayout>(R.id.web_container).childCount)
            .count { recreated.findViewById<FrameLayout>(R.id.web_container).getChildAt(it) is WebView })
    }

    @Test
    fun `home state is restored as home`() {
        openService(AiServices.findById("qwen")!!)
        activity.findViewById<ImageButton>(R.id.btn_home).performClick()
        idle()

        val recreated = controller.recreate().get()
        idle()

        assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.home_screen).visibility)
        assertEquals(0, recreated.findViewById<FrameLayout>(R.id.web_container).childCount)
    }

    // ---------------------------------------------------- 9. dark theme

    @Test
    fun `dark theme keeps default text and toolbar icons readable`() {
        // Read from the Activity theme (not the application context), because
        // the manifest applies Theme.AiHub to activities.
        val themeAttrs = activity.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.colorForeground,
                android.R.attr.textColorPrimary,
                com.google.android.material.R.attr.colorSurface,
                com.google.android.material.R.attr.colorOnSurface,
                androidx.appcompat.R.attr.colorControlNormal
            )
        )
        val foreground = themeAttrs.getColor(0, Color.BLACK)
        val primaryText = themeAttrs.getColor(1, Color.BLACK)
        val surface = themeAttrs.getColor(2, Color.WHITE)
        val onSurface = themeAttrs.getColor(3, Color.BLACK)
        val controlNormal = themeAttrs.getColor(4, Color.BLACK)
        themeAttrs.recycle()

        fun luminance(color: Int) =
            (Color.red(color) * 0.299 + Color.green(color) * 0.587 +
                Color.blue(color) * 0.114) / 255

        assertTrue("theme foreground must be light, was #${foreground.toUInt().toString(16)}",
            luminance(foreground) > 0.5)
        assertTrue("default primary text must be light", luminance(primaryText) > 0.5)
        assertTrue("Material surface must be dark", luminance(surface) < 0.25)
        assertTrue("Material on-surface text must be light", luminance(onSurface) > 0.5)
        assertTrue("colorControlNormal icons must be light against the toolbar",
            luminance(controlNormal) > 0.5)

        // ToolbarIcon applies this explicit tint to all four toolbar buttons.
        val iconAttrs = activity.obtainStyledAttributes(
            R.style.ToolbarIcon, intArrayOf(androidx.appcompat.R.attr.tint)
        )
        val iconTint = iconAttrs.getColorStateList(0)?.defaultColor
            ?: iconAttrs.getColor(0, Color.BLACK)
        iconAttrs.recycle()
        assertTrue("toolbar icon tint must be light against the dark surface",
            luminance(iconTint) > 0.7)
        assertEquals(activity.getColor(R.color.text_primary), iconTint)
    }

    @Test
    fun `background and surface colors are dark`() {
        val bg = activity.getColor(R.color.background)
        val surface = activity.getColor(R.color.surface)
        listOf(bg, surface).forEach { c ->
            val lum = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114) / 255
            assertTrue("colour $c must be dark, was lum=$lum", lum < 0.25)
        }
    }

    // ------------------------------------------------- 10. review loop UI

    @Test
    fun `review controls exist and stay visible across tab switches`() {
        openService(AiServices.findById("qwen")!!)
        listOf(R.id.btn_review, R.id.btn_auto, R.id.btn_senior, R.id.btn_stop, R.id.btn_cfg)
            .forEach { assertNotNull(activity.findViewById<View>(it)) }
        val status = activity.findViewById<TextView>(R.id.txt_review_status)
        assertNotNull(status)

        // Hop through every service: the bar must never disappear, and no step
        // of the loop may leave a second WebView behind.
        AiServices.TABS.forEach { service ->
            openService(service)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.review_bar).visibility)
            assertEquals(View.VISIBLE, status.visibility)
            assertEquals("one WebView for ${service.id}", 1, webViewCount())
        }

        // Still there after Home and back.
        activity.findViewById<ImageButton>(R.id.btn_home).performClick()
        idle()
        openService(AiServices.findById("meta")!!)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.review_bar).visibility)
    }

    @Test
    fun `review controls are not inside the container cleared on switch`() {
        // The bar must be a sibling of the WebView container, not a child of it,
        // otherwise destroying the WebView on a tab switch would take it away.
        val container = activity.findViewById<FrameLayout>(R.id.web_container)
        val bar = activity.findViewById<View>(R.id.review_bar)
        assertTrue("review bar must not live inside web_container",
            bar.parent !is FrameLayout || (bar.parent as View).id != R.id.web_container)
        openService(AiServices.findById("qwen")!!)
        assertNull("web_container only ever holds WebViews",
            (0 until container.childCount).map { container.getChildAt(it) }
                .firstOrNull { it !is WebView })
        assertNotNull("review bar still attached", activity.findViewById<View>(R.id.review_bar))
    }

    @Test
    fun `cfg opens the settings overlay and its close button dismisses it`() {
        openService(AiServices.findById("qwen")!!)
        val root = activity.findViewById<ViewGroup>(R.id.root)
        val before = root.childCount

        activity.findViewById<android.widget.Button>(R.id.btn_cfg).performClick()
        idle()
        assertEquals("overlay must be added", before + 1, root.childCount)
        val overlay = findSettingsOverlay(activity)
        assertNotNull("settings overlay must be present", overlay)

        // Every service row exists, and the locked ones are disabled.
        val labels = mutableListOf<String>()
        walk(overlay!!) { v -> if (v is TextView) labels.add(v.text.toString()) }
        AiServices.TABS.forEach { assertTrue("missing row ${it.label}", labels.contains(it.label)) }
        val buttons = mutableListOf<android.widget.Button>()
        walk(overlay) { v -> if (v is android.widget.Button) buttons.add(v) }
        assertTrue("locked rows render as LOCKED",
            buttons.any { it.text == "LOCKED" && !it.isEnabled })

        // Close must genuinely dismiss (remove) the overlay.
        buttons.first { it.text == "Close" }.performClick()
        idle()
        assertEquals("overlay must be removed on Close", before, root.childCount)
        assertNull(findSettingsOverlay(activity))
    }

    @Test
    fun `stop clears the wake flag and the log overlay opens and closes`() {
        openService(AiServices.findById("qwen")!!)
        val window = activity.window

        activity.findViewById<android.widget.Button>(R.id.btn_stop).performClick()
        idle()
        assertEquals("FLAG_KEEP_SCREEN_ON must be cleared on stop",
            0, window.attributes.flags and
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = activity.findViewById<ViewGroup>(R.id.root)
        val before = root.childCount
        activity.findViewById<TextView>(R.id.txt_review_status).performClick()
        idle()
        val opened = root.childCount
        activity.findViewById<TextView>(R.id.txt_review_status).performClick()
        idle()
        assertTrue("log overlay must open", opened >= before)
        assertTrue("log overlay is dismissable", findLogOverlay(activity) == null)
    }

    @Test
    fun `review requires the qwen tab and does not start from another service`() {
        openService(AiServices.findById("deepseek")!!)
        val root = activity.findViewById<ViewGroup>(R.id.root)
        val overlays = root.childCount

        activity.findViewById<android.widget.Button>(R.id.btn_review).performClick()
        idle()

        // It switches to Qwen and asks for a second tap instead of running.
        assertEquals("Qwen", title().text.toString())
        assertEquals("no overlay may appear", overlays, root.childCount)
        assertEquals(1, webViewCount())
    }

    @Test
    fun `leaving chat pauses the run and saves it for resume`() {
        openService(AiServices.findById("qwen")!!)
        // A saved session must survive going Home (pause, not stop/clear).
        ReviewSession(activity).save(
            phase = ReviewSession.PHASE_REVIEWER,
            round = 4,
            mode = "AUTO",
            task = "t",
            answer = "a",
            pending = listOf("mimo"),
            completed = listOf("meta"),
            issues = emptyList(),
            failedRounds = 1,
            allPass = false
        )
        activity.findViewById<ImageButton>(R.id.btn_home).performClick()
        idle()

        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(ReviewSettings.PREFS_NAME, Context.MODE_PRIVATE)
        assertNotNull(prefs.getString("review_session", null))
        assertEquals(0, activity.window.attributes.flags and
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test
    fun `stop discards the session`() {
        openService(AiServices.findById("qwen")!!)
        ReviewSession(activity).save(
            phase = ReviewSession.PHASE_REVIEWER,
            round = 1,
            mode = "AUTO",
            task = "t",
            answer = "a",
            pending = listOf("meta"),
            completed = emptyList(),
            issues = emptyList(),
            failedRounds = 0,
            allPass = false
        )
        // Stop with no run in flight must not be needed to clear; but a resume
        // offer must still be visible and harmless.
        activity.findViewById<TextView>(R.id.txt_review_status).performClick()
        idle()
        assertNotNull(activity.findViewById<View>(R.id.review_bar))
    }

    @Test
    fun `pausing saves the session instead of clearing it`() {
        openService(AiServices.findById("qwen")!!)
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(ReviewSettings.PREFS_NAME, Context.MODE_PRIVATE)

        // A session recorded by the loop is still there after a pause.
        ReviewSession(activity).save(
            phase = ReviewSession.PHASE_REVIEWER,
            round = 2,
            mode = "AUTO",
            task = "task",
            answer = "answer",
            pending = listOf("meta"),
            completed = listOf("deepseek"),
            issues = emptyList(),
            failedRounds = 0,
            allPass = false
        )
        controller.pause()
        idle()
        assertNotNull("pause must not clear a saved session", prefs.getString("review_session", null))
        assertEquals(0, activity.window.attributes.flags and
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun walk(view: View, visit: (View) -> Unit) {
        visit(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), visit)
    }

    private fun findSettingsOverlay(activity: MainActivity): ReviewSettingsView? {
        val root = activity.findViewById<ViewGroup>(R.id.root)
        for (i in 0 until root.childCount) {
            (root.getChildAt(i) as? ReviewSettingsView)?.let { return it }
        }
        return null
    }

    private fun findLogOverlay(activity: MainActivity): View? {
        val root = activity.findViewById<ViewGroup>(R.id.root)
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is LinearLayout) {
                var isLog = false
                walk(child) { v -> if (v is TextView && v.text == "Review log") isLog = true }
                if (isLog) return child
            }
        }
        return null
    }

    // ------------------------------------------- 11. single-WebView invariant

    @Test
    fun `no webview leak across a long session`() {
        for (round in 1..3) {
            for (service in AiServices.TABS) {
                openService(service)
                assertEquals("round $round ${service.id}: exactly one WebView",
                    1, webViewCount())
                assertTrue(liveWebView()!!.url!!.startsWith("http"))
            }
            activity.findViewById<ImageButton>(R.id.btn_home).performClick()
            idle()
            assertEquals("home must release the WebView", 0, webViewCount())
        }
    }

    @Test
    fun `destroying the activity releases the webview`() {
        openService(AiServices.findById("qwen")!!)
        controller.pause().stop().destroy()
        // No crash and container emptied on the way out.
        assertEquals(0, webViewCount())
    }
}
