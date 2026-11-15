package com.arfan.aihub

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * One reviewer's configuration row, as shown in [ReviewSettingsView].
 *
 * @param serviceId stable [AiService.id]
 * @param role      free-text role used in the reviewer prompt ("Code review")
 * @param eligible  whether the service may ever act as a reviewer. Qwen (the
 *                  planner that owns the task) and Coder (the desktop builder)
 *                  are never reviewers, so their rows are locked OFF.
 * @param enabled   whether the reviewer takes part in a review round.
 * @param dailyCap  maximum number of review requests sent to this service per
 *                  calendar day. 0 disables the service without unticking it.
 * @param usedToday number of review requests already sent today.
 */
data class ReviewSpec(
    val serviceId: String,
    val label: String,
    val role: String,
    val eligible: Boolean,
    val enabled: Boolean,
    val dailyCap: Int,
    val usedToday: Int
) {
    val isReviewer: Boolean get() = eligible && enabled && dailyCap > 0
    val remaining: Int get() = (dailyCap - usedToday).coerceAtLeast(0)
}

/**
 * Persisted review-loop configuration: which services may review, how many
 * requests each may receive today, and the loop-wide knobs (max rounds, auto
 * mode, escalation threshold).
 *
 * Daily counters reset automatically the first time the settings are read on a
 * new calendar day, so the app never has to run a background job for it.
 */
class ReviewSettings(context: Context) {

    private val appContext: Context = context.applicationContext

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------
    // Defaults
    // ------------------------------------------------------------------

    private data class Row(
        val role: String,
        val eligible: Boolean,
        val enabled: Boolean,
        val cap: Int
    )

    /**
     * Default reviewer table. Qwen and Coder are structurally excluded: Qwen
     * owns the task/answer being reviewed and Coder is the desktop builder, so
     * neither can judge. Z.ai, MiniMax and Arena start OFF because they are
     * reserved for escalation or opted into by the user.
     */
    private val defaults: Map<String, Row> = mapOf(
        "qwen" to Row("Planner", eligible = false, enabled = false, cap = 0),
        "coder" to Row("Builder", eligible = false, enabled = false, cap = 0),
        "arena" to Row("Builder/agent", eligible = true, enabled = false, cap = 4),
        "gemini" to Row("Design/Language", eligible = true, enabled = true, cap = 6),
        "deepseek" to Row("Code review", eligible = true, enabled = true, cap = 6),
        "meta" to Row("Logic review", eligible = true, enabled = true, cap = 6),
        "zai" to Row("Senior review", eligible = true, enabled = false, cap = 4),
        "minimax" to Row("Optional", eligible = true, enabled = false, cap = 4),
        "mimo" to Row("General", eligible = true, enabled = true, cap = 6)
    )

    private fun row(id: String): Row =
        defaults[id] ?: Row("Optional", eligible = false, enabled = false, cap = 0)

    // ------------------------------------------------------------------
    // Per-service accessors
    // ------------------------------------------------------------------

    fun isEligible(id: String): Boolean = row(id).eligible

    fun role(id: String): String =
        prefs().getString(KEY_ROLE + id, row(id).role) ?: row(id).role

    fun enabled(id: String): Boolean {
        if (!isEligible(id)) return false
        return prefs().getBoolean(KEY_ENABLED + id, row(id).enabled) && dailyCap(id) > 0
    }

    fun dailyCap(id: String): Int =
        prefs().getInt(KEY_CAP + id, row(id).cap).coerceIn(0, 30)

    fun usedToday(id: String): Int {
        resetDailyIfNeeded()
        return prefs().getInt(KEY_USED + id, 0)
    }

    fun remaining(id: String): Int = (dailyCap(id) - usedToday(id)).coerceAtLeast(0)

    fun setRole(id: String, value: String) {
        if (!isEligible(id)) return
        prefs().edit().putString(KEY_ROLE + id, value.trim().take(40)).apply()
    }

    /** Enabling a locked (never-reviewer) service is ignored on purpose. */
    fun setEnabled(id: String, value: Boolean) {
        if (!isEligible(id)) return
        prefs().edit().putBoolean(KEY_ENABLED + id, value).apply()
    }

    fun setDailyCap(id: String, value: Int) {
        if (!isEligible(id)) return
        prefs().edit().putInt(KEY_CAP + id, value.coerceIn(0, 30)).apply()
    }

    /** Counts one review request against [id]'s daily allowance. */
    fun markUsed(id: String) {
        prefs().edit().putInt(KEY_USED + id, usedToday(id) + 1).apply()
    }

    /** Snapshot of every service row, in Home-grid order. */
    fun specs(): List<ReviewSpec> = AiServices.TABS.map { service ->
        ReviewSpec(
            serviceId = service.id,
            label = service.label,
            role = role(service.id),
            eligible = isEligible(service.id),
            enabled = enabled(service.id),
            dailyCap = dailyCap(service.id),
            usedToday = usedToday(service.id)
        )
    }

    /** Ids of reviewers that may take part in the next round, in tab order. */
    fun enabledReviewerIds(): List<String> =
        AiServices.TABS.map { it.id }.filter { isReviewerEligibleForRound(it) }

    private fun isReviewerEligibleForRound(id: String): Boolean =
        isEligible(id) && enabled(id) && dailyCap(id) > 0

    /** Convenience: only the services that are switched ON. */
    fun activeReviewerIds(): List<String> = enabledReviewerIds()

    /** Z.ai is not required to be "enabled" to be used for escalation. */
    fun zaiAvailable(): Boolean = isEligible("zai") && dailyCap("zai") > 0

    // ------------------------------------------------------------------
    // Loop-wide settings
    // ------------------------------------------------------------------

    var maxRounds: Int
        get() = prefs().getInt(KEY_MAX_ROUNDS, DEFAULT_MAX_ROUNDS).coerceIn(1, 20)
        set(value) = prefs().edit()
            .putInt(KEY_MAX_ROUNDS, value.coerceIn(1, 20)).apply()

    var autoMode: Boolean
        get() = prefs().getBoolean(KEY_AUTO_MODE, false)
        set(value) = prefs().edit().putBoolean(KEY_AUTO_MODE, value).apply()

    var escalationAfter: Int
        get() = prefs().getInt(KEY_ESCALATE_AFTER, DEFAULT_ESCALATE_AFTER).coerceIn(1, 20)
        set(value) = prefs().edit()
            .putInt(KEY_ESCALATE_AFTER, value.coerceIn(1, 20)).apply()

    // ------------------------------------------------------------------
    // Daily bookkeeping (caps + Z.ai escalation budget)
    // ------------------------------------------------------------------

    /** Z.ai escalations already used today (hard limit: [MAX_ZAI_PER_DAY]). */
    fun zaiEscalationsToday(): Int {
        resetDailyIfNeeded()
        return prefs().getInt(KEY_ZAI_USED, 0)
    }

    fun zaiEscalationRemaining(): Int =
        (MAX_ZAI_PER_DAY - zaiEscalationsToday()).coerceAtLeast(0)

    fun markZaiEscalation() {
        prefs().edit().putInt(KEY_ZAI_USED, zaiEscalationsToday() + 1).apply()
    }

    /**
     * Clears every per-day counter when the calendar day changed since the last
     * visit. Cheap (one get + at most one commit-set) and called from the few
     * entry points that care, so the counters can never leak across midnight.
     */
    fun resetDailyIfNeeded() {
        val today = dayKey()
        if (prefs().getString(KEY_DAY, null) == today) return
        resetDaily(today)
    }

    /** Explicit reset ("Reset daily counts" in the settings overlay). */
    fun resetDaily(today: String = dayKey()) {
        val editor = prefs().edit().putString(KEY_DAY, today)
        AiServices.TABS.forEach { editor.remove(KEY_USED + it.id) }
        editor.putInt(KEY_ZAI_USED, 0)
        editor.apply()
    }

    private fun dayKey(): String {
        val now = Calendar.getInstance()
        val y = now.get(Calendar.YEAR)
        val m = now.get(Calendar.MONTH) + 1
        val d = now.get(Calendar.DAY_OF_MONTH)
        return "$y-${pad(m)}-${pad(d)}"
    }

    private fun pad(value: Int): String = if (value < 10) "0$value" else value.toString()

    companion object {
        const val PREFS_NAME = "ai_hub_review_prefs"

        const val DEFAULT_MAX_ROUNDS = 8
        const val DEFAULT_ESCALATE_AFTER = 3

        /** Hard daily ceiling for Z.ai escalations, independent of its cap. */
        const val MAX_ZAI_PER_DAY = 2

        private const val KEY_ROLE = "role_"
        private const val KEY_ENABLED = "enabled_"
        private const val KEY_CAP = "cap_"
        private const val KEY_USED = "used_"
        private const val KEY_DAY = "day_key"
        private const val KEY_ZAI_USED = "zai_used"

        private const val KEY_MAX_ROUNDS = "max_rounds"
        private const val KEY_AUTO_MODE = "auto_mode"
        private const val KEY_ESCALATE_AFTER = "escalate_after"

        /** Services that must never act as reviewers. */
        val NEVER_REVIEWERS = setOf("qwen", "coder")
    }
}
