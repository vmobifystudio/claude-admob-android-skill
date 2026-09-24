package com.example.app.ads

import android.content.Context

/**
 * Time-boxed ad suppression — users earn ad-free minutes by watching a rewarded video.
 *
 * Stored in the same SharedPreferences file TinyDB uses so it's backed up with the rest of
 * app state. No runtime cost when inactive — a single Long read on each ad gate.
 */
object AdsSuppression {
    private const val PREFS_SUFFIX = "_preferences"
    private const val KEY_AD_FREE_UNTIL = "ad_free_until_ms"

    /** One hour of ad suppression per rewarded ad watched. */
    const val SUPPRESSION_DURATION_MS = 60L * 60L * 1000L

    fun isAdFree(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(
            context.packageName + PREFS_SUFFIX, Context.MODE_PRIVATE
        )
        return prefs.getLong(KEY_AD_FREE_UNTIL, 0L) > System.currentTimeMillis()
    }

    fun remainingMs(context: Context): Long {
        val prefs = context.applicationContext.getSharedPreferences(
            context.packageName + PREFS_SUFFIX, Context.MODE_PRIVATE
        )
        return (prefs.getLong(KEY_AD_FREE_UNTIL, 0L) - System.currentTimeMillis())
            .coerceAtLeast(0L)
    }

    fun grant(context: Context, durationMs: Long = SUPPRESSION_DURATION_MS) {
        val prefs = context.applicationContext.getSharedPreferences(
            context.packageName + PREFS_SUFFIX, Context.MODE_PRIVATE
        )
        val current = prefs.getLong(KEY_AD_FREE_UNTIL, 0L)
        val newExpiry = maxOf(current, System.currentTimeMillis()) + durationMs
        prefs.edit().putLong(KEY_AD_FREE_UNTIL, newExpiry).apply()
    }
}
