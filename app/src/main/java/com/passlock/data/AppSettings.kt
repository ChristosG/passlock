package com.passlock.data

import android.content.Context

/**
 * Small non-secret preferences (theme, throttle counters, opt-in auto-wipe).
 * No secrets are stored here — only settings and a failed-attempt counter, which
 * is meaningless to an attacker (clearing it requires clearing app data, which
 * also deletes the encrypted vault).
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("passlock_settings", Context.MODE_PRIVATE)

    var themeMode: String
        get() = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    var autoWipeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOWIPE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOWIPE, value).apply()

    var failedAttempts: Int
        get() = prefs.getInt(KEY_FAILED, 0)
        set(value) = prefs.edit().putInt(KEY_FAILED, value).apply()

    var lockoutUntilMs: Long
        get() = prefs.getLong(KEY_LOCKOUT, 0L)
        set(value) = prefs.edit().putLong(KEY_LOCKOUT, value).apply()

    /** Global UI text scale (1.0 = normal). */
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_FONT, value).apply()

    /** When true, biometric unlock is only offered after a password unlock in the same session. */
    var requirePasswordColdStart: Boolean
        get() = prefs.getBoolean(KEY_PW_COLD, false)
        set(value) = prefs.edit().putBoolean(KEY_PW_COLD, value).apply()

    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val AUTO_WIPE_THRESHOLD = 10

        private const val KEY_THEME = "theme"
        private const val KEY_AUTOWIPE = "autoWipe"
        private const val KEY_FAILED = "failed"
        private const val KEY_LOCKOUT = "lockoutUntil"
        private const val KEY_FONT = "fontScale"
        private const val KEY_PW_COLD = "pwColdStart"
    }
}
