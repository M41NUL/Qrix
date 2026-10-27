package com.qrix.scanner

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object Prefs {
    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("qrix_prefs", Context.MODE_PRIVATE)

    fun theme(c: Context): Int = sp(c).getInt("theme", THEME_SYSTEM)

    fun setTheme(c: Context, value: Int) {
        sp(c).edit().putInt("theme", value).apply()
        applyTheme(c)
    }

    fun applyTheme(c: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme(c)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun vibrate(c: Context): Boolean = sp(c).getBoolean("vibrate", true)
    fun setVibrate(c: Context, v: Boolean) = sp(c).edit().putBoolean("vibrate", v).apply()

    fun autoFlash(c: Context): Boolean = sp(c).getBoolean("auto_flash", true)
    fun setAutoFlash(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_flash", v).apply()

    fun saveHistory(c: Context): Boolean = sp(c).getBoolean("save_history", true)
    fun setSaveHistory(c: Context, v: Boolean) = sp(c).edit().putBoolean("save_history", v).apply()

    fun autoZoom(c: Context): Boolean = sp(c).getBoolean("auto_zoom", true)
    fun setAutoZoom(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto_zoom", v).apply()

    /** Version the user tapped "Later" on, so the popup is not shown again for it. */
    fun skippedUpdate(c: Context): String = sp(c).getString("skipped_update", "") ?: ""
    fun setSkippedUpdate(c: Context, v: String) = sp(c).edit().putString("skipped_update", v).apply()
}
