package com.arfan.aihub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for an in-flight review session.
 *
 * Written after every state change and read back on [ReviewController.resumeIfPossible]
 * so a pause, a tab hop or even process death does not lose the round — and,
 * critically, so resuming never re-sends an instruction that already went out.
 *
 * [resumable] deliberately rejects empty reviewer lists: a session with nothing
 * left to ask must never be interpreted as "everyone passed".
 */
class ReviewSession(context: Context) {

    private val appContext: Context = context.applicationContext

    /** In-memory mirror of one saved session. */
    data class Snapshot(
        val phase: String,
        val round: Int,
        val mode: String,
        val task: String,
        val answer: String,
        val pending: List<String>,
        val completed: List<String>,
        val issues: List<String>,
        val failedRounds: Int,
        val allPass: Boolean
    ) {
        val resumable: Boolean
            get() = phase != PHASE_DONE && !allPass &&
                (pending.isNotEmpty() || completed.isNotEmpty())

        val reason: String
            get() = when {
                allPass -> "run already finished with ALL PASS"
                phase == PHASE_DONE -> "run already finished"
                pending.isEmpty() && completed.isEmpty() -> "no reviewers recorded"
                else -> ""
            }
    }

    private fun prefs() =
        appContext.getSharedPreferences(ReviewSettings.PREFS_NAME, Context.MODE_PRIVATE)

    fun save(
        phase: String,
        round: Int,
        mode: String,
        task: String,
        answer: String,
        pending: List<String>,
        completed: List<String>,
        issues: List<String>,
        failedRounds: Int,
        allPass: Boolean
    ) {
        val json = JSONObject().apply {
            put("phase", phase)
            put("round", round)
            put("mode", mode)
            put("task", task.take(MAX_TASK))
            put("answer", answer.take(MAX_ANSWER))
            put("pending", JSONArray(pending))
            put("completed", JSONArray(completed))
            put("issues", JSONArray(issues.map { it.take(MAX_ISSUE) }))
            put("failedRounds", failedRounds)
            put("allPass", allPass)
            put("savedAt", System.currentTimeMillis())
        }
        prefs().edit().putString(KEY, json.toString()).apply()
    }

    fun load(): Snapshot? {
        val raw = prefs().getString(KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Snapshot(
                phase = json.optString("phase", PHASE_IDLE),
                round = json.optInt("round", 0),
                mode = json.optString("mode", "MANUAL"),
                task = json.optString("task", ""),
                answer = json.optString("answer", ""),
                pending = json.optJSONArray("pending").toStringList(),
                completed = json.optJSONArray("completed").toStringList(),
                issues = json.optJSONArray("issues").toStringList(),
                failedRounds = json.optInt("failedRounds", 0),
                allPass = json.optBoolean("allPass", false)
            )
        }.getOrNull()
    }

    fun clear() {
        prefs().edit().remove(KEY).apply()
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until length()) out.add(optString(i, ""))
        return out.filter { it.isNotEmpty() }
    }

    companion object {
        const val PHASE_IDLE = "idle"
        const val PHASE_CAPTURE = "capture"
        const val PHASE_REVIEWER = "reviewer"
        const val PHASE_QWEN_REWORK = "qwen_rework"
        const val PHASE_DONE = "done"

        private const val KEY = "review_session"
        private const val MAX_TASK = 6000
        private const val MAX_ANSWER = 12000
        private const val MAX_ISSUE = 4000
    }
}
