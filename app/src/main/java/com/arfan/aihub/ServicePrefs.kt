package com.arfan.aihub

import android.content.Context

/**
 * Persists the per-service rendering mode (desktop or mobile).
 *
 * Each service has its own [AiService.desktopMode] default; once the user flips
 * the toolbar toggle, the choice is stored here and wins on every later visit.
 */
object ServicePrefs {

    private const val PREFS_NAME = "ai_hub_prefs"
    private const val KEY_PREFIX = "desktop_mode_"

    /** The effective desktop flag for [service]: stored override, else the default. */
    fun isDesktop(context: Context, service: AiService): Boolean =
        prefs(context).getBoolean(KEY_PREFIX + service.id, service.desktopMode)

    /** Stores the user's choice for [service]. */
    fun setDesktop(context: Context, service: AiService, desktop: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_PREFIX + service.id, desktop)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
