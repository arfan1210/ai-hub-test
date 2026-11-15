package com.arfan.aihub

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the review loop's pure logic: the verdict protocol, the
 * settings/cap bookkeeping, session persistence and the JS escaping used by
 * [SiteAdapters]. These run without a WebView, so they guard the rules that the
 * behavioural tests cannot reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewLogicTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(ReviewSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(ReviewLog.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    // ------------------------------------------------------- verdict parser

    @Test
    fun `parses PASS with NONE issues`() {
        val parsed = VerdictParser.parse("Looks good.\n\nVERDICT: PASS\nISSUES: NONE")
        assertNotNull(parsed)
        assertEquals(Verdict.PASS, parsed!!.verdict)
        assertTrue(parsed.isPass)
        assertEquals("", parsed.issues)
    }

    @Test
    fun `parses REVISE and keeps the issue bullets`() {
        val parsed = VerdictParser.parse(
            "VERDICT: REVISE\nISSUES:\n- Missing null check\n- Wrong colour token"
        )!!
        assertEquals(Verdict.REVISE, parsed.verdict)
        assertTrue(parsed.issues.contains("Missing null check"))
        assertTrue(parsed.issues.contains("Wrong colour token"))
    }

    @Test
    fun `FAIL is folded into REVISE by the loop`() {
        val parsed = VerdictParser.parse("VERDICT: FAIL\nISSUES:\n- It does not compile")!!
        assertEquals(Verdict.FAIL, parsed.verdict)
        assertEquals(Verdict.REVISE, parsed.effective)
        assertFalse(parsed.isPass)
    }

    @Test
    fun `the last verdict wins when the reply restates an earlier one`() {
        val parsed = VerdictParser.parse(
            "Draft: VERDICT: PASS\nAfter checking further:\nVERDICT: REVISE\nISSUES:\n- off by one"
        )!!
        assertEquals(Verdict.REVISE, parsed.verdict)
    }

    @Test
    fun `no verdict line means no parse - never a fabricated PASS`() {
        assertNull(VerdictParser.parse("I think this is probably fine."))
        assertNull(VerdictParser.parse(""))
        assertNull(VerdictParser.parse(null))
    }

    @Test
    fun `our own prompt is never accepted as a verdict`() {
        val echo = """
            You are a strict independent reviewer for the service "DeepSeek".
            Reply using EXACTLY this protocol:
            VERDICT: PASS
            ISSUES: NONE
        """.trimIndent()
        assertNull(VerdictParser.parse(echo))
        assertFalse(VerdictParser.hasVerdict(echo))
    }

    @Test
    fun `tolerates verdict punctuation and casing`() {
        assertEquals(Verdict.PASS, VerdictParser.parse("verdict - pass")!!.verdict)
        assertEquals(Verdict.PASS, VerdictParser.parse("VERDICT:pass")!!.verdict)
        assertEquals(Verdict.REVISE, VerdictParser.parse("VERDICT = revise")!!.verdict)
    }

    // ------------------------------------------------------------ settings

    @Test
    fun `qwen and coder can never be reviewers`() {
        val settings = ReviewSettings(context)
        assertFalse(settings.isEligible("qwen"))
        assertFalse(settings.isEligible("coder"))
        settings.setEnabled("qwen", true)
        settings.setEnabled("coder", true)
        assertFalse("locked services must stay off", settings.enabled("qwen"))
        assertFalse(settings.enabled("coder"))
        assertFalse(settings.activeReviewerIds().contains("qwen"))
        assertFalse(settings.activeReviewerIds().contains("coder"))
    }

    @Test
    fun `default reviewers are deepseek gemini meta and mimo`() {
        val settings = ReviewSettings(context)
        assertEquals(
            listOf("gemini", "deepseek", "meta", "mimo"),
            settings.activeReviewerIds()
        )
        // Arena, MiniMax and Z.ai start OFF.
        listOf("arena", "minimax", "zai").forEach {
            assertFalse("$it must default off", settings.enabled(it))
        }
    }

    @Test
    fun `loop defaults match the spec`() {
        val settings = ReviewSettings(context)
        assertEquals(8, settings.maxRounds)
        assertEquals(3, settings.escalationAfter)
        assertFalse(settings.autoMode)
    }

    @Test
    fun `daily cap counts down and a zero cap disables the reviewer`() {
        val settings = ReviewSettings(context)
        val cap = settings.dailyCap("deepseek")
        assertTrue(cap > 0)
        settings.markUsed("deepseek")
        settings.markUsed("deepseek")
        assertEquals(cap - 2, settings.remaining("deepseek"))

        settings.setDailyCap("deepseek", 0)
        assertFalse("cap 0 disables without unticking", settings.enabled("deepseek"))
        assertFalse(settings.activeReviewerIds().contains("deepseek"))
    }

    @Test
    fun `daily counters reset when the calendar day changes`() {
        val settings = ReviewSettings(context)
        settings.markUsed("meta")
        settings.markZaiEscalation()
        assertEquals(1, settings.usedToday("meta"))

        // Simulate yesterday's counters by rewinding the stored day key.
        context.getSharedPreferences(ReviewSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("day_key", "1999-01-01").commit()

        settings.resetDailyIfNeeded()
        assertEquals(0, settings.usedToday("meta"))
        assertEquals(0, settings.zaiEscalationsToday())
        assertEquals(ReviewSettings.MAX_ZAI_PER_DAY, settings.zaiEscalationRemaining())
    }

    @Test
    fun `z ai escalation is capped at two per day`() {
        val settings = ReviewSettings(context)
        assertEquals(2, ReviewSettings.MAX_ZAI_PER_DAY)
        settings.markZaiEscalation()
        assertEquals(1, settings.zaiEscalationRemaining())
        settings.markZaiEscalation()
        assertEquals(0, settings.zaiEscalationRemaining())
        assertEquals(0, (ReviewSettings.MAX_ZAI_PER_DAY - settings.zaiEscalationsToday())
            .coerceAtLeast(0))
    }

    @Test
    fun `role edits are persisted per service`() {
        val settings = ReviewSettings(context)
        settings.setRole("meta", "Safety review")
        assertEquals("Safety review", settings.role("meta"))
        // Locked services cannot have their role edited.
        settings.setRole("qwen", "Hacked")
        assertEquals("Planner", settings.role("qwen"))
    }

    // ------------------------------------------------------------- session

    @Test
    fun `an empty session is never resumable`() {
        val session = ReviewSession(context)
        session.save(
            phase = ReviewSession.PHASE_REVIEWER,
            round = 1,
            mode = "AUTO",
            task = "t",
            answer = "a",
            pending = emptyList(),
            completed = emptyList(),
            issues = emptyList(),
            failedRounds = 0,
            allPass = false
        )
        val saved = session.load()!!
        assertFalse("empty reviewer list must not resume as PASS", saved.resumable)
    }

    @Test
    fun `a session with pending reviewers is resumable and round-trips`() {
        val session = ReviewSession(context)
        session.save(
            phase = ReviewSession.PHASE_REVIEWER,
            round = 3,
            mode = "AUTO",
            task = "build a page",
            answer = "here is the page",
            pending = listOf("meta", "mimo"),
            completed = listOf("deepseek"),
            issues = listOf("[DeepSeek]\n- fix a"),
            failedRounds = 1,
            allPass = false
        )
        val saved = session.load()!!
        assertTrue(saved.resumable)
        assertEquals(3, saved.round)
        assertEquals(listOf("meta", "mimo"), saved.pending)
        assertEquals(listOf("deepseek"), saved.completed)
        assertEquals("build a page", saved.task)
        assertEquals("here is the page", saved.answer)
    }

    @Test
    fun `a finished session is not resumable`() {
        val session = ReviewSession(context)
        session.save(
            phase = ReviewSession.PHASE_DONE,
            round = 2,
            mode = "AUTO",
            task = "t",
            answer = "a",
            pending = listOf("meta"),
            completed = listOf("deepseek"),
            issues = emptyList(),
            failedRounds = 0,
            allPass = true
        )
        assertFalse(session.load()!!.resumable)
    }

    // ---------------------------------------------------------------- log

    @Test
    fun `the log appends and keeps history`() {
        ReviewLog.append(context, "first")
        ReviewLog.append(context, "second")
        ReviewLog.append(context, "third")
        val lines = ReviewLog.lines(context)
        assertEquals(3, lines.size)
        assertTrue(lines[0].endsWith("first"))
        assertTrue(lines[2].endsWith("third"))
    }

    @Test
    fun `multi line log entries are folded onto one line`() {
        ReviewLog.append(context, "verdict\nwith\nnewlines")
        assertEquals(1, ReviewLog.lines(context).size)
    }

    // ------------------------------------------------------------- adapters

    @Test
    fun `js string escaping survives quotes backslashes and newlines`() {
        val raw = "he said \"hi\"\\ and\nleft"
        val js = JsonUtil.jsonString(raw)
        assertTrue(js.startsWith("\""))
        assertTrue(js.endsWith("\""))
        assertTrue("quotes escaped", js.contains("\\\""))
        assertTrue("newline escaped", js.contains("\\n"))
        assertFalse("no raw newline may break the script", js.contains("\n"))
    }

    @Test
    fun `json helper round-trips a string literal`() {
        val original = "a \"b\" \\n c\\td €😀"
        val encoded = JsonUtil.jsonString(original)
        // Simulate what the WebView does: the whole reply is quoted again.
        val echoed = JsonUtil.jsonString(encoded)
        assertEquals(encoded, JsonUtil.unwrap(echoed))
    }

    @Test
    fun `json helper reads flat fields`() {
        val obj = "{\"ok\":true,\"busy\":false,\"count\":3,\"text\":\"hi\"}"
        assertTrue(JsonUtil.bool(obj, "ok"))
        assertFalse(JsonUtil.bool(obj, "busy"))
        assertEquals(3, JsonUtil.int(obj, "count"))
        assertEquals("hi", JsonUtil.str(obj, "text"))
        assertEquals("", JsonUtil.str(obj, "missing"))
    }

    @Test
    fun `webview json responses decode into results`() {
        val quoted = "\"{\\\"ok\\\":true,\\\"busy\\\":false,\\\"count\\\":2," +
            "\\\"text\\\":\\\"VERDICT: PASS\\\"}\""
        val result = SiteAdapters.decode(quoted)
        assertTrue(result.ok)
        assertFalse(result.busy)
        assertEquals(2, result.count)
        assertEquals("VERDICT: PASS", result.text)
    }

    @Test
    fun `null and empty webview responses decode to a failure`() {
        assertFalse(SiteAdapters.decode(null).ok)
        assertFalse(SiteAdapters.decode("null").ok)
        assertFalse(SiteAdapters.decode("").ok)
    }

    @Test
    fun `capture scripts only ask for assistant nodes when reviewing`() {
        val script = SiteAdapters.captureJs("deepseek", SiteAdapters.Target.REVIEWER_REPLY)
        assertTrue(script.contains("capture('assistant')"))
        assertFalse(script.contains("capture('user')"))

        val userScript = SiteAdapters.captureJs("qwen", SiteAdapters.Target.NEWEST_USER)
        assertTrue(userScript.contains("capture('user')"))
    }

    @Test
    fun `send script carries an escaped payload`() {
        val script = SiteAdapters.sendJs("meta", "VERDICT: PASS\n\"quoted\"")
        assertTrue(script.contains("send("))
        assertTrue(script.contains("\\\"quoted\\\""))
        assertTrue(script.contains("\\n"))
    }
}
