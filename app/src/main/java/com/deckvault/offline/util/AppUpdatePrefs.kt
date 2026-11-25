package com.deckvault.offline.util

import android.content.Context

object AppUpdatePrefs {
    private const val PREFS_NAME = "deckvault_settings"
    private const val KEY_CHECK_ON_LAUNCH = "check_on_launch"
    private const val KEY_INCLUDE_PRERELEASE = "include_prerelease"
    private const val KEY_DEFAULT_COMPRESSION = "default_compression_preset"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCheckOnLaunchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHECK_ON_LAUNCH, true)

    fun setCheckOnLaunchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHECK_ON_LAUNCH, enabled).apply()
    }

    fun isIncludePreReleases(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INCLUDE_PRERELEASE, false)

    fun setIncludePreReleases(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INCLUDE_PRERELEASE, enabled).apply()
    }

    fun getDefaultCompressionPresetId(context: Context): String =
        prefs(context).getString(KEY_DEFAULT_COMPRESSION, "original") ?: "original"

    fun setDefaultCompressionPresetId(context: Context, presetId: String) {
        prefs(context).edit().putString(KEY_DEFAULT_COMPRESSION, presetId).apply()
    }
}
