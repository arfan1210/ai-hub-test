package com.arfan.aihub

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * Drives the multi-AI review loop.
 *
 * Everything runs on the main thread through a single [Handler]: no coroutines,
 * no worker threads. Work is expressed as a small queue of [Step]s that the
 * controller dispatches one at a time into the app's ONE WebView.
 *
 * Invariants encoded here — each one fixes a bug seen in earlier attempts:
 *
 *  1. Only assistant-only DOM nodes are parsed (see [SiteAdapters]); the page as
 *     a whole and the user's own prompt can never become a "verdict".
 *  2. The task/answer pair is frozen ONCE per round and every reviewer in that
 *     round receives that same snapshot — never a re-capture per reviewer.
 *  3. No parsable verdict => visible `CALIBRATION NEEDED`, never a fake PASS.
 *  4. An empty reviewer list can never be reported as ALL PASS.
 *  5. The Senior/Z.ai path is a separate step and never rewrites the reviewer
 *     list that the round was built from.
 *  6. Daily caps are counted *before* a send; STOP drops every pending callback.
 *  7. Pausing (tab change / onDestroy / login loss) SAVES the session; only a
 *     real finish clears it. Resume never re-sends an already-sent step.
 */
class ReviewController(
    private val activity: MainActivity,
    private val settings: ReviewSettings = ReviewSettings(activity)
) {

    /** Callbacks into the Activity; the Activity owns the views. */
    interface Listener {
        fun onStatus(text: String)

        /** Log line — the Activity appends it to the persistent log. */
        fun onLog(line: String)

        /** The loop needs [serviceId] in the single WebView. */
        fun onNeedsService(serviceId: String)
    }

    // ------------------------------------------------------------------
    // Internal state
    // ------------------------------------------------------------------

    private enum class Phase { IDLE, CAPTURE, REVIEWING, REWORKING, WAITING_QWEN }

    private enum class Mode { MANUAL, AUTO, SENIOR }

    private enum class Kind { REVIEW, SENIOR }

    /** One queued instruction. */
    private data class Step(
        val serviceId: String,
        val label: String,
        val prompt: String,
        val kind: Kind,
        /** Resumed mid-flight: poll for the reply instead of sending again. */
        val pollOnly: Boolean = false
    )

    private val handler = Handler(Looper.getMainLooper())
    private val session = ReviewSession(activity)

    private var listener: Listener? = null
    private var webView: WebView? = null

    /** True once onWebViewReady has armed a WebView at least once. */
    private var webViewArmed = false

    /** Service currently loaded in the single WebView. */
    private var currentServiceId: String? = null

    /** Bumped by every cancel/pause/finish so stale callbacks can bail out. */
    private var sequence = 0

    var isRunning: Boolean = false
        private set

    private var mode: Mode = Mode.MANUAL
    private var phase: Phase = Phase.IDLE
    private var round = 1

    private var frozenTask: String = ""
    private var frozenAnswer: String = ""

    /** Reviewer ids captured when the run started; the round is built from this. */
    private var runReviewers: List<String> = emptyList()

    private val queue = ArrayDeque<Step>()
    private var inFlight: Step? = null

    /** Service id we have asked the Activity to open, or null. */
    private var requestedTab: String? = null

    /** Bumped each time a tab request is (re)issued, to expire old timeouts. */
    private var tabRequestToken = 0

    private var completedReviewers: MutableList<String> = mutableListOf()

    /**
     * Reviewers actually instructed in this round. The ALL PASS decision is
     * based on this set — never on a count that could silently be empty.
     */
    private var askedThisRound: MutableList<String> = mutableListOf()
    private var issuesThisRound: MutableList<String> = mutableListOf()
    private var failedRounds = 0
    private var escalatedThisRound = false

    /** Newest assistant text captured right before a reviewer was instructed. */
    private var baselineText: String = ""

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    fun setListener(value: Listener?) {
        listener = value
    }

    fun detach() {
        listener = null
    }

    /** True while the loop is waiting for [serviceId] to appear in the WebView. */
    fun isAwaitingTab(serviceId: String): Boolean =
        isRunning && requestedTab != null && requestedTab == serviceId

    /**
     * Called by the Activity after a fresh WebView has been installed for
     * [serviceId]. Readiness is polled before any script is allowed to type.
     */
    fun onWebViewReady(serviceId: String, wv: WebView) {
        webView = wv
        currentServiceId = serviceId
        val firstArm = !webViewArmed
        webViewArmed = true
        if (firstArm) {
            // Offer to resume a saved session instead of dropping it silently.
            offerResume()
        }
        requestedTab = null
        tabRequestToken++
        if (!isRunning) return
        awaitPageReady(serviceId, attempt = 0) { advance() }
    }

    /** True when a saved session can be resumed (used by the Activity). */
    fun hasSavedSession(): Boolean = session.load()?.resumable == true

    /**
     * A session saved by a previous process is offered once, never resumed
     * blindly: resuming re-opens tabs, which the user must expect.
     */
    private fun offerResume() {
        val saved = session.load() ?: return
        if (!saved.resumable) {
            session.clear()
            return
        }
        log(
            "Saved session found (round ${saved.round}, ${saved.pending.size} reviewer(s) left) " +
                "— press Resume in Cfg to continue"
        )
        status("Saved session — Cfg ▸ Resume")
    }

    /** Explicit resume, driven by the settings overlay button. */
    fun resumeSaved() {
        if (isRunning) return
        if (!resumeIfPossible()) status("Nothing to resume")
    }

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** One reviewer round, from the current Qwen conversation. */
    fun startManualRound() {
        if (!canStart()) return
        val reviewers = settings.activeReviewerIds()
        if (reviewers.isEmpty()) {
            abort("No reviewers enabled — open Cfg")
            return
        }
        begin(Mode.MANUAL, reviewers, "Manual review")
    }

    /** Sequential multi-round automation until ALL PASS or maxRounds. */
    fun startAutoLoop() {
        if (!canStart()) return
        val reviewers = settings.activeReviewerIds()
        if (reviewers.isEmpty()) {
            abort("No reviewers enabled — open Cfg")
            return
        }
        begin(Mode.AUTO, reviewers, "Auto review")
        log("Max ${settings.maxRounds} rounds, escalate to Z.ai after ${settings.escalationAfter}")
    }

    /**
     * Manual Senior review: always Z.ai, always one shot, and never touches the
     * configured reviewer list.
     */
    fun startSeniorEscalation(manual: Boolean) {
        if (!canStart()) return
        if (!settings.zaiAvailable()) {
            abort("Z.ai is unavailable (cap is 0)")
            return
        }
        val remaining = settings.zaiEscalationRemaining()
        if (remaining <= 0) {
            abort("Z.ai escalation used up today (max ${ReviewSettings.MAX_ZAI_PER_DAY})")
            return
        }
        // The Z.ai *escalation budget* is counted here, before anything is sent.
        // The per-service daily cap is counted in sendStep() like any other
        // request, so it is never counted twice.
        settings.markZaiEscalation()
        begin(Mode.SENIOR, listOf(ZAI), "Senior review (Z.ai)")
        log(
            "Escalations left today: ${settings.zaiEscalationRemaining()} " +
                "(Z.ai cap remaining: ${settings.remaining(ZAI)})"
        )
    }

    /**
     * Resume after a pause / process death.
     *
     * The reviewer list is restored verbatim from the session, and the first
     * still-pending reviewer is marked [Step.pollOnly] because its instruction
     * may already have been sent — resuming waits for that reply instead of
     * sending it twice. An empty list is refused outright, so a resume can never
     * masquerade as ALL PASS.
     */
    fun resumeIfPossible(): Boolean {
        val saved = session.load() ?: return false
        if (!saved.resumable) {
            log("Nothing to resume (${saved.reason})")
            session.clear()
            return false
        }
        mode = runCatching { Mode.valueOf(saved.mode) }.getOrDefault(Mode.MANUAL)
        round = saved.round.coerceAtLeast(1)
        frozenTask = saved.task
        frozenAnswer = saved.answer
        // Restore exactly the reviewers the run was built from.
        runReviewers = AiServices.TABS.map { it.id }
            .filter { saved.pending.contains(it) || saved.completed.contains(it) }
        if (runReviewers.isEmpty()) {
            log("Refusing to resume: no reviewers recorded")
            session.clear()
            return false
        }
        completedReviewers = saved.completed.toMutableList()
        askedThisRound = saved.completed.toMutableList()
        issuesThisRound = saved.issues.toMutableList()
        failedRounds = saved.failedRounds
        escalatedThisRound = false
        queue.clear()
        saved.pending.forEachIndexed { index, id ->
            AiServices.findById(id)?.let { svc ->
                val senior = mode == Mode.SENIOR
                queue.addLast(
                    Step(
                        serviceId = svc.id,
                        label = svc.label,
                        prompt = if (senior) seniorPrompt() else reviewerPromptFor(svc, id),
                        kind = if (senior) Kind.SENIOR else Kind.REVIEW,
                        pollOnly = index == 0 && saved.phase == ReviewSession.PHASE_REVIEWER
                    )
                )
            }
        }
        if (queue.isEmpty()) {
            log("Refusing to resume: nothing left to ask (not a PASS)")
            session.clear()
            return false
        }
        stopCallbacks()
        isRunning = true
        requestedTab = null
        inFlight = null
        phase = if (frozenAnswer.isBlank()) Phase.CAPTURE else Phase.REVIEWING
        activity.setKeepScreenOn(true)
        log(
            "Resumed round $round — ${queue.size} reviewer(s) left: " +
                queue.joinToString(", ") { it.label }
        )
        advance()
        return true
    }

    /** Save progress without clearing it; used from onDestroy and on pause. */
    fun pauseSession(reason: String = "paused") {
        if (!isRunning) return
        persist()
        stopCallbacks()
        isRunning = false
        activity.setKeepScreenOn(false)
        log("Session saved ($reason) — ${queue.size} reviewer(s) pending")
        status("Paused ($reason) — Cfg ▸ Resume")
    }

    /** STOP, pause, or a manual service change: drop everything and discard. */
    fun cancel(reason: String) {
        val wasRunning = isRunning
        stopCallbacks()
        isRunning = false
        phase = Phase.IDLE
        queue.clear()
        inFlight = null
        requestedTab = null
        activity.setKeepScreenOn(false)
        if (!wasRunning) return
        session.clear()
        log("Review stopped ($reason)")
        status("Stopped")
    }

    // ------------------------------------------------------------------
    // Run setup
    // ------------------------------------------------------------------

    private fun canStart(): Boolean {
        if (isRunning) {
            status("Already running — press Stop first")
            return false
        }
        if (currentServiceId != QWEN) {
            status("Open Qwen first")
            log("Review needs the Qwen tab (task + answer live there)")
            return false
        }
        return true
    }

    private fun begin(newMode: Mode, reviewers: List<String>, title: String) {
        stopCallbacks()
        sequence++
        mode = newMode
        round = 1
        frozenTask = ""
        frozenAnswer = ""
        runReviewers = reviewers.toList()
        queue.clear()
        inFlight = null
        requestedTab = null
        completedReviewers = mutableListOf()
        askedThisRound = mutableListOf()
        issuesThisRound = mutableListOf()
        failedRounds = 0
        escalatedThisRound = false
        isRunning = true
        phase = Phase.CAPTURE
        activity.setKeepScreenOn(true)
        log("$title started — reviewers: ${labels(reviewers)}")
        persist()
        advance()
    }

    private fun abort(message: String) {
        log(message)
        status(message)
        isRunning = false
        activity.setKeepScreenOn(false)
    }

    // ------------------------------------------------------------------
    // The state machine
    // ------------------------------------------------------------------

    private fun advance() {
        if (!isRunning) return
        when (phase) {
            Phase.CAPTURE -> stepCapture()
            Phase.REVIEWING -> stepReview()
            Phase.REWORKING -> stepRework()
            Phase.WAITING_QWEN -> stepWaitForQwen()
            Phase.IDLE -> Unit
        }
    }

    // ---- capture ------------------------------------------------------

    private fun stepCapture() {
        if (currentServiceId != QWEN) {
            requestTab(QWEN) { advance() }
            return
        }
        status("Reading Qwen's answer…")
        val captureArmed = eval(QWEN, SiteAdapters.captureJs(QWEN, SiteAdapters.Target.NEWEST_ASSISTANT)) { raw ->
            val answer = SiteAdapters.decode(raw)
            if (!answer.ok || answer.text.isBlank()) {
                calibration("Qwen's reply could not be read (${answer.reason})")
                return@eval
            }
            eval(QWEN, SiteAdapters.captureJs(QWEN, SiteAdapters.Target.NEWEST_USER)) { userRaw ->
                val task = SiteAdapters.decode(userRaw)
                frozenTask = if (task.ok) task.text else ""
                frozenAnswer = answer.text
                baselineText = answer.text
                log(
                    "Frozen snapshot: task ${frozenTask.length} chars, answer ${frozenAnswer.length} chars"
                )
                if (frozenTask.isBlank()) {
                    log("Task text unavailable — reviewers get the answer only")
                }
                buildRoundQueue()
            }
        }
        if (!captureArmed) requestTab(QWEN) { advance() }
    }

    /**
     * Builds the reviewer queue for this round from [runReviewers]. Every step
     * carries the SAME frozen prompt, which is what keeps reviewers independent
     * of each other.
     */
    private fun buildRoundQueue() {
        queue.clear()
        completedReviewers.clear()
        issuesThisRound.clear()
        askedThisRound.clear()
        escalatedThisRound = false
        runReviewers.forEach { id ->
            val svc = AiServices.findById(id) ?: return@forEach
            if (settings.remaining(id) <= 0) {
                log("${svc.label}: daily cap reached (${settings.dailyCap(id)}) — skipping")
                return@forEach
            }
            queue.addLast(
                if (mode == Mode.SENIOR) {
                    Step(svc.id, svc.label, seniorPrompt(), Kind.SENIOR)
                } else {
                    Step(svc.id, svc.label, reviewerPromptFor(svc, id), Kind.REVIEW)
                }
            )
        }
        if (queue.isEmpty()) {
            // Never fall through to "ALL PASS" with nobody having reviewed.
            finish(false, "No reviewer had quota left — nothing was reviewed")
            return
        }
        phase = Phase.REVIEWING
        persist()
        log("Round $round — asking ${queue.size} reviewer(s) sequentially")
        advance()
    }

    // ---- reviewers ----------------------------------------------------

    private fun stepReview() {
        if (queue.isEmpty()) {
            onRoundReviewed()
            return
        }
        if (inFlight != null) {
            // Polls (or a later phase) are already scheduled; nothing to do.
            return
        }
        if (requestedTab != null) {
            // Waiting for the Activity to install the reviewer tab; the
            // readiness handshake re-enters advance() when it is ready.
            return
        }
        val next = queue.first()
        if (currentServiceId != next.serviceId) {
            requestTab(next.serviceId) { sendStep(next) }
            return
        }
        sendStep(next)
    }

    /**
     * Asks the Activity for [serviceId] in the single WebView. The Activity
     * rebuilds the WebView, [onWebViewReady] fires, readiness is polled and the
     * state machine re-enters [advance]. If the tab never arrives (login wall,
     * site down) the run pauses with the service and reason visible.
     */
    private fun requestTab(serviceId: String, then: () -> Unit = {}) {
        if (currentServiceId == serviceId && webView != null) {
            then()
            return
        }
        if (requestedTab == serviceId) return
        requestedTab = serviceId
        tabRequestToken++
        val token = tabRequestToken
        status("Opening ${AiServices.findById(serviceId)?.label ?: serviceId}…")
        // Posted, never called inline: the request usually originates inside an
        // evaluateJavascript callback, and tearing a WebView down while its JS is
        // still on the stack crashes on some Android versions.
        handler.post {
            if (isRunning && requestedTab == serviceId) listener?.onNeedsService(serviceId)
        }
        handler.postDelayed({
            if (!isRunning || token != tabRequestToken) return@postDelayed
            if (currentServiceId != serviceId) {
                pauseWithReason(serviceId, "tab did not open")
            }
        }, TAB_TIMEOUT_MS)
    }

    /**
     * Sends one reviewer step:
     * 1. capture that page's current newest reply (baseline),
     * 2. verify the tab guard,
     * 3. count the request against the cap (before sending),
     * 4. type + send,
     * 5. poll for a new, stable, parsable verdict.
     */
    private fun sendStep(step: Step) {
        if (!isRunning) return
        // Tab guard: never type into whatever page happens to be open.
        if (currentServiceId != step.serviceId) {
            cancel("tab changed before send")
            return
        }
        if (settings.remaining(step.serviceId) <= 0) {
            log("${step.label}: daily cap reached — skipped")
            queue.removeFirstOrNull()
            advance()
            return
        }
        inFlight = step
        if (step.pollOnly) {
            // Resumed mid-flight: the instruction already went out, so only the
            // reply is awaited. No second send, no second cap count.
            askedThisRound.add(step.serviceId)
            log("${step.label}: waiting for the reply to the earlier request")
            val armed = eval(
                step.serviceId,
                SiteAdapters.captureJs(step.serviceId, SiteAdapters.Target.NEWEST_ASSISTANT)
            ) { raw ->
                val before = SiteAdapters.decode(raw)
                val already = if (before.ok) VerdictParser.parse(before.text) else null
                if (already != null) {
                    // The reply landed before the session was saved — take it
                    // rather than waiting for a reply that will never change.
                    log("${step.label}: verdict already on screen")
                    onVerdict(step, already)
                    return@eval
                }
                baselineText = if (before.ok) before.text else ""
                pollForVerdict(step, attempt = 0)
            }
            if (!armed) {
                requestedTab = null
                inFlight = null
                if (isRunning) requestTab(step.serviceId) { sendStep(step) }
            }
            return
        }
        status("Asking ${step.label}…")
        val armed = eval(
            step.serviceId,
            SiteAdapters.captureJs(step.serviceId, SiteAdapters.Target.NEWEST_ASSISTANT)
        ) { raw ->
            val before = SiteAdapters.decode(raw)
            baselineText = if (before.ok) before.text else ""
            // Counted BEFORE the send, as required.
            settings.markUsed(step.serviceId)
            if (!askedThisRound.contains(step.serviceId)) askedThisRound.add(step.serviceId)
            log(
                "${step.label}: request ${settings.usedToday(step.serviceId)}/" +
                    "${settings.dailyCap(step.serviceId)} today"
            )
            typeAndSend(step, retry = 0)
        }
        if (!armed) {
            // The WebView vanished between the tab guard and the call.
            requestedTab = null
            inFlight = null
            if (isRunning) requestTab(step.serviceId) { sendStep(step) }
        }
    }

    private fun typeAndSend(step: Step, retry: Int) {
        if (!isRunning) return
        if (currentServiceId != step.serviceId) {
            cancel("tab guard")
            return
        }
        val guard = sequence
        eval(step.serviceId, SiteAdapters.sendJs(step.serviceId, step.prompt)) { raw ->
            if (guard != sequence) return@eval
            val result = SiteAdapters.decode(raw)
            if (result.ok) {
                log("${step.label}: instruction sent")
                pollForVerdict(step, attempt = 0)
                return@eval
            }
            if (retry >= MAX_SEND_RETRIES) {
                calibration("${step.label} never accepted the instruction (${result.reason})")
                return@eval
            }
            val noInput = result.reason.contains("composer", true) ||
                result.reason.contains("typing", true) ||
                result.reason.contains("unchanged", true) ||
                result.busy
            log(
                "${step.label}: ${if (noInput) "NO_INPUT" else "send failed"} " +
                    "(${result.reason}) — retry ${retry + 1}/$MAX_SEND_RETRIES in 2s"
            )
            handler.postDelayed({
                if (guard != sequence || !isRunning) return@postDelayed
                typeAndSend(step, retry + 1)
            }, RETRY_DELAY_MS)
        }
    }

    /** Polls the reviewer page until a new, stable, parsable verdict appears. */
    private fun pollForVerdict(step: Step, attempt: Int) {
        if (!isRunning) return
        if (attempt > MAX_POLLS) {
            calibration("${step.label} produced no parsable verdict")
            return
        }
        val guard = sequence
        handler.postDelayed({
            if (guard != sequence || !isRunning) return@postDelayed
            if (currentServiceId != step.serviceId) {
                cancel("tab guard while polling")
                return@postDelayed
            }
            eval(step.serviceId, SiteAdapters.captureJs(step.serviceId, SiteAdapters.Target.REVIEWER_REPLY)) { raw ->
                if (guard != sequence) return@eval
                val capture = SiteAdapters.decode(raw)
                if (capture.busy) {
                    status("${step.label} is thinking…")
                    pollForVerdict(step, attempt + 1)
                    return@eval
                }
                if (!capture.ok || capture.text.isBlank()) {
                    pollForVerdict(step, attempt + 1)
                    return@eval
                }
                if (capture.text == baselineText) {
                    // No new reply yet. If we wait too long the instruction was
                    // probably swallowed, so re-send once or twice.
                    if (attempt >= STALE_ATTEMPTS && attempts_left(step) > 0) {
                        log("${step.label}: no new reply — re-sending the instruction")
                        markResent(step)
                        typeAndSend(step, retry = 0)
                        return@eval
                    }
                    pollForVerdict(step, attempt + 1)
                    return@eval
                }
                val parsed = VerdictParser.parse(capture.text)
                if (parsed == null) {
                    if (attempt >= STALE_ATTEMPTS && attempts_left(step) > 0) {
                        log("${step.label}: reply has no VERDICT line yet — re-sending")
                        markResent(step)
                        typeAndSend(step, retry = 0)
                        return@eval
                    }
                    pollForVerdict(step, attempt + 1)
                    return@eval
                }
                // Require one more identical poll so a half-streamed answer is
                // never parsed as final.
                if (attempt < MIN_STABLE_POLLS) {
                    pollForVerdict(step, attempt + 1)
                    return@eval
                }
                onVerdict(step, parsed)
            }
        }, POLL_INTERVAL_MS)
    }

    /** How many re-sends this step still gets (bounded independently of polls). */
    private var resentCount = 0
    private var resentForStep: String? = null

    private fun attempts_left(step: Step): Int {
        if (resentForStep != step.serviceId) {
            resentForStep = step.serviceId
            resentCount = 0
        }
        return MAX_RESENDS - resentCount
    }

    private fun markResent(step: Step) {
        if (resentForStep != step.serviceId) {
            resentForStep = step.serviceId
            resentCount = 0
        }
        resentCount++
        // Each re-send is another request against the daily cap.
        if (settings.remaining(step.serviceId) > 0) settings.markUsed(step.serviceId)
    }

    private fun onVerdict(step: Step, parsed: ParsedVerdict) {
        if (step.kind == Kind.SENIOR) {
            log(
                "${step.label}: ${parsed.verdict}" +
                    if (parsed.isPass) "" else " — ${parsed.issues.take(300)}"
            )
            if (parsed.isPass) {
                finish(true, "Z.ai PASS — releasing")
                return
            }
            // A senior REVISE only ends a manual/senior run. In auto mode the
            // issues join the round and the loop carries on (the senior step is
            // appended to the round, never swapped for the reviewer list).
            if (mode == Mode.AUTO && round < settings.maxRounds) {
                issuesThisRound.add("[${step.label} — senior]\n${parsed.issues}")
                queue.removeFirstOrNull()
                inFlight = null
                phase = Phase.REWORKING
                persist()
                advance()
                return
            }
            finish(false, "Z.ai wants changes")
            return
        }
        completedReviewers.add(step.serviceId)
        if (parsed.isPass) {
            log("${step.label}: PASS")
        } else {
            log("${step.label}: ${parsed.verdict} — issues recorded")
            issuesThisRound.add("[${step.label}]\n${parsed.issues}")
        }
        queue.removeFirstOrNull()
        inFlight = null
        persist()
        advance()
    }

    // ---- round hand-off ----------------------------------------------

    private fun onRoundReviewed() {
        inFlight = null
        val reviewed = completedReviewers.size
        if (completedReviewers.isEmpty()) {
            // Belt and braces: never ALL PASS from an empty reviewer set.
            finish(false, "No reviewer returned a verdict")
            return
        }
        if (mode == Mode.MANUAL) {
            finish(issuesThisRound.isEmpty(), manualSummary())
            return
        }
        if (issuesThisRound.isEmpty()) {
            // Every reviewer that actually answered said PASS. Reviewers skipped
            // for quota were logged and never asked, so they are not silently
            // counted as passing either.
            val skipped = runReviewers.size - reviewed
            val note = if (skipped > 0) " ($skipped skipped: daily cap)" else ""
            finish(true, "ALL PASS after $round round(s)$note")
            return
        }
        if (round >= settings.maxRounds) {
            finish(false, "Gave up after ${settings.maxRounds} rounds")
            return
        }
        failedRounds++
        // Escalation: after N failed rounds, ask Z.ai once. This appends a
        // SENIOR step; it never rewrites [runReviewers].
        if (mode == Mode.AUTO && !escalatedThisRound &&
            failedRounds >= settings.escalationAfter &&
            settings.zaiEscalationRemaining() > 0 &&
            settings.remaining(ZAI) > 0
        ) {
            escalatedThisRound = true
            settings.markZaiEscalation()
            val zai = AiServices.findById(ZAI)!!
            log("Escalating to Z.ai after $failedRounds failed round(s)")
            queue.clear()
            queue.addLast(Step(zai.id, zai.label, seniorPrompt(), Kind.SENIOR))
            phase = Phase.REVIEWING
            persist()
            advance()
            return
        }
        phase = Phase.REWORKING
        persist()
        advance()
    }

    private fun manualSummary(): String {
        val issueCount = issuesThisRound.size
        return if (issueCount == 0) {
            "Round complete — ALL PASS (${completedReviewers.size} reviewer(s))"
        } else {
            "Round complete — $issueCount of ${askedThisRound.size} reviewer(s) raised issues"
        }
    }

    // ---- rework -------------------------------------------------------

    private fun stepRework() {
        val issues = issuesThisRound.joinToString("\n\n")
        if (issues.isBlank()) {
            finish(false, "Reviewers returned no issues text to send back")
            return
        }
        if (currentServiceId != QWEN) {
            requestTab(QWEN) { advance() }
            return
        }
        status("Sending issues back to Qwen…")
        val guard = sequence
        val armed = eval(QWEN, SiteAdapters.sendJs(QWEN, reworkPrompt(issues))) { raw ->
            if (guard != sequence) return@eval
            val result = SiteAdapters.decode(raw)
            if (!result.ok) {
                log("Qwen did not accept the issues (${result.reason}) — retrying")
                handler.postDelayed({
                    if (guard == sequence && isRunning) advance()
                }, RETRY_DELAY_MS)
                return@eval
            }
            log("Qwen: issues delivered — waiting for it to finish")
            phase = Phase.WAITING_QWEN
            persist()
            advance()
        }
        if (!armed) requestTab(QWEN) { advance() }
    }

    /** Polls Qwen until it is idle AND its answer changed (no fixed delays). */
    private fun stepWaitForQwen() {
        if (currentServiceId != QWEN) {
            requestTab(QWEN) { advance() }
            return
        }
        waitAttempt++
        if (waitAttempt > MAX_POLLS) {
            waitAttempt = 0
            finish(false, "Qwen did not produce a new answer in time")
            return
        }
        status("Waiting for Qwen to finish…")
        val guard = sequence
        if (webView == null) {
            requestTab(QWEN) { advance() }
            return
        }
        handler.postDelayed({
            if (guard != sequence || !isRunning) return@postDelayed
            if (currentServiceId != QWEN) {
                cancel("tab guard while waiting for Qwen")
                return@postDelayed
            }
            eval(QWEN, SiteAdapters.busyJs(QWEN)) { raw ->
                if (guard != sequence) return@eval
                if (SiteAdapters.decode(raw).busy) {
                    advance()
                    return@eval
                }
                eval(QWEN, SiteAdapters.captureJs(QWEN, SiteAdapters.Target.NEWEST_ASSISTANT)) { capRaw ->
                    if (guard != sequence) return@eval
                    val fresh = SiteAdapters.decode(capRaw)
                    if (!fresh.ok || fresh.text == frozenAnswer) {
                        // Either unreadable or Qwen has not written yet: keep
                        // polling instead of assuming a fixed delay.
                        advance()
                        return@eval
                    }
                    waitAttempt = 0
                    log("Qwen produced a new answer (${fresh.text.length} chars)")
                    round++
                    frozenTask = ""
                    frozenAnswer = ""
                    phase = Phase.CAPTURE
                    persist()
                    advance()
                }
            }
        }, POLL_INTERVAL_MS)
    }

    private var waitAttempt = 0

    // ------------------------------------------------------------------
    // Terminal states
    // ------------------------------------------------------------------

    /** Unrecognised DOM: stop loudly. Never guess a verdict. */
    private fun calibration(reason: String) {
        log("CALIBRATION NEEDED — $reason")
        finish(false, "CALIBRATION NEEDED")
    }

    private fun finish(allPass: Boolean, message: String) {
        stopCallbacks()
        isRunning = false
        phase = Phase.IDLE
        queue.clear()
        inFlight = null
        requestedTab = null
        activity.setKeepScreenOn(false)
        session.clear()
        log(message)
        status(message)
    }

    /** Pauses with a visible service + reason (login expiry / site down). */
    private fun pauseWithReason(serviceId: String, reason: String) {
        val label = AiServices.findById(serviceId)?.label ?: serviceId
        log("PAUSED — $label: $reason")
        status("Paused: $label — $reason")
        pauseSession(reason)
    }

    private fun stopCallbacks() {
        sequence++
        handler.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------
    // Page readiness / login detection
    // ------------------------------------------------------------------

    private var readyAttempt = 0

    /**
     * Polls the freshly loaded page until a composer exists. A page that stays
     * composer-less is treated as a login expiry / outage and PAUSES with the
     * service and reason visible.
     */
    private fun awaitPageReady(serviceId: String, attempt: Int, then: () -> Unit) {
        if (!isRunning) return
        if (currentServiceId != serviceId) return
        val guard = sequence
        handler.postDelayed({
            if (guard != sequence || !isRunning) return@postDelayed
            eval(serviceId, SiteAdapters.probeJs(serviceId)) { raw ->
                if (guard != sequence) return@eval
                val probe = SiteAdapters.decode(raw)
                if (probe.ok) {
                    handler.post { if (guard == sequence && isRunning) then() }
                    return@eval
                }
                if (attempt >= LOGIN_ATTEMPTS) {
                    pauseWithReason(serviceId, "login expired or page unavailable")
                    return@eval
                }
                awaitPageReady(serviceId, attempt + 1, then)
            }
        }, READY_INTERVAL_MS)
    }

    // ------------------------------------------------------------------
    // Prompts (frozen snapshot only)
    // ------------------------------------------------------------------

    private fun reviewerPromptFor(service: AiService, id: String): String {
        val role = settings.role(id)
        val task = frozenTask.ifBlank {
            "(the original task could not be read — review the answer for internal consistency)"
        }
        return """
You are a strict independent reviewer for the service "${service.label}" (role: $role).

TASK GIVEN TO THE PLANNER:
---
$task
---

ANSWER UNDER REVIEW:
---
$frozenAnswer
---

Check the answer against the task: correctness, missing requirements,
contradictions, unsafe or destructive steps, and anything that breaks.
Be concrete and terse.

Reply using EXACTLY this protocol — nothing else:

VERDICT: PASS
ISSUES: NONE

or

VERDICT: REVISE
ISSUES:
- <issue>
- <issue>

or

VERDICT: FAIL
ISSUES:
- <issue>

Use PASS only if nothing at all must change.
""".trim()
    }

    private fun seniorPrompt(): String {
        val task = frozenTask.ifBlank { "(task text unavailable)" }
        return """
You are the senior reviewer and final arbiter (Z.ai).

TASK GIVEN TO THE PLANNER:
---
$task
---

ANSWER UNDER REVIEW:
---
$frozenAnswer
---

Other reviewers disputed this answer. Decide whether it is releasable.
Reply using EXACTLY this protocol:

VERDICT: PASS
ISSUES: NONE

or

VERDICT: REVISE
ISSUES:
- <issue>
""".trim()
    }

    private fun reworkPrompt(issues: String): String {
        return """
Independent reviewers found problems with your last answer. Fix them and
reply with the complete corrected answer.

REVIEWER ISSUES:
---
${issues.take(MAX_ISSUES_CHARS)}
---

Address every issue, keep everything that was already correct, and end with a
complete answer rather than a summary of changes.
""".trim()
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** Runs [script] in the WebView iff it is still showing [serviceId]. */
    private fun eval(serviceId: String, script: String, onResult: (String?) -> Unit): Boolean {
        val wv = webView
        if (wv == null || currentServiceId != serviceId) return false
        return runCatching {
            wv.evaluateJavascript(script) { value ->
                if (isRunning) onResult(value)
            }
            true
        }.getOrDefault(false)
    }

    private fun persist() {
        session.save(
            phase = when (phase) {
                Phase.CAPTURE -> ReviewSession.PHASE_CAPTURE
                Phase.REVIEWING -> ReviewSession.PHASE_REVIEWER
                Phase.REWORKING, Phase.WAITING_QWEN -> ReviewSession.PHASE_QWEN_REWORK
                Phase.IDLE -> ReviewSession.PHASE_IDLE
            },
            round = round,
            mode = mode.name,
            task = frozenTask,
            answer = frozenAnswer,
            // During CAPTURE the queue is still empty; persist the run's reviewer
            // list instead so a pause there is still resumable.
            pending = if (queue.isEmpty()) runReviewers else queue.map { it.serviceId },
            completed = completedReviewers.toList(),
            issues = issuesThisRound.toList(),
            failedRounds = failedRounds,
            allPass = false
        )
    }

    private fun status(text: String) {
        listener?.onStatus(text)
    }

    private fun log(line: String) {
        listener?.onLog(line)
    }

    private fun labels(ids: List<String>): String =
        ids.mapNotNull { AiServices.findById(it)?.label }.joinToString(", ")

    companion object {
        const val QWEN = "qwen"
        const val ZAI = "zai"

        /** Reviewer reply polling. */
        private const val POLL_INTERVAL_MS = 2000L
        private const val MAX_POLLS = 75          // ~2.5 min per reply
        private const val STALE_ATTEMPTS = 8      // ~16 s before re-sending
        private const val MIN_STABLE_POLLS = 1

        /** Composer retries for the NO_INPUT case (5 tries, 2 s apart). */
        private const val MAX_SEND_RETRIES = 5
        private const val RETRY_DELAY_MS = 2000L

        /** Page readiness probe. */
        private const val READY_INTERVAL_MS = 800L
        private const val LOGIN_ATTEMPTS = 15     // ~12 s before pausing

        /** Re-sends driven by polling (each counts against the cap). */
        private const val MAX_RESENDS = 2

        private const val MAX_ISSUES_CHARS = 3000

        /** How long a tab request may take before the run pauses. */
        private const val TAB_TIMEOUT_MS = 25000L
    }
}
