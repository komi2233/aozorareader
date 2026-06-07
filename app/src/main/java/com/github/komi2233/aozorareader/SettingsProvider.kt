package com.github.komi2233.aozorareader

import android.content.Context
import android.content.SharedPreferences

class SettingsProvider(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var fontSize: Float
        get() = prefs.getFloat("font_size", 36f)
        set(value) = prefs.edit().putFloat("font_size", value).apply()

    var isDarkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(value) = prefs.edit().putBoolean("dark_mode", value).apply()

    companion object {
        const val FONT_SIZE_SMALL = 32f
        const val FONT_SIZE_MEDIUM = 36f
        const val FONT_SIZE_LARGE = 44f
    }
}
