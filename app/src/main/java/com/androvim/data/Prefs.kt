package com.androvim.data

import android.content.Context
import android.content.SharedPreferences

/** Shared preference keys used across activities. */
object Prefs {
    const val FILE = "androvim"
    const val TEXT_SIZE = "text_size"
    const val FONT_FAMILY = "font_family"
    const val COLOR_SCHEME = "color_scheme"
    const val EXTRA_KEYS_MODE = "extra_keys_mode" // auto | show | hide
    const val EXTRA_KEYS_AUTO = "auto"
    const val EXTRA_KEYS_SHOW = "show"
    const val EXTRA_KEYS_HIDE = "hide"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val SAFE_MODE = "safe_mode"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
