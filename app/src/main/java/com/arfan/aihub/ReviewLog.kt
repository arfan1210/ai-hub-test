package com.arfan.aihub

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only review log.
 *
 * The log is the user's only window into what the loop did while it was hopping
 * between services, so entries are **appended**, never replaced: opening the
 * overlay, switching tabs or starting a new round must not lose history.
 * Only the tail is kept in preferences so the file stays small.
 */
object ReviewLog {

    /** Preferences file that holds the log (public so tests can reset it). */
    const val PREFS_NAME = "ai_hub_review_log"
    private const val KEY = "lines"
    private const val MAX_LINES = 400

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Every stored line, oldest first. */
    fun lines(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return raw.split('\n').filter { it.isNotEmpty() }
    }

    fun text(context: Context): String = lines(context).joinToString("\n")

    /** Appends one line (multi-line payloads are folded onto one line each). */
    fun append(context: Context, message: String) {
        val stamped = clock.format(Date()) + "  " + message.replace("\n", " ⏎ ")
        val all = lines(context) + stamped
        val kept = if (all.size > MAX_LINES) all.subList(all.size - MAX_LINES, all.size) else all
        prefs(context).edit().putString(KEY, kept.joinToString("\n")).apply()
    }

    /** Appends [message] and lets a listener refresh its view. */
    fun appendAndNotify(context: Context, message: String, listener: (() -> Unit)?) {
        append(context, message)
        listener?.invoke()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }
}
