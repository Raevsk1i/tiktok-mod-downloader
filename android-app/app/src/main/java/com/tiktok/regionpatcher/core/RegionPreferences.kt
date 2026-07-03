package com.tiktok.regionpatcher.core

import android.content.Context

object RegionPreferences {

    private const val PREFS = "region_prefs"
    private const val KEY_PRESET_ID = "preset_id"

    fun getPresetId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PRESET_ID, RegionPresets.defaultId) ?: RegionPresets.defaultId
    }

    fun setPresetId(context: Context, presetId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESET_ID, presetId)
            .apply()
    }

    fun load(context: Context): RegionConfig {
        val preset = RegionPresets.byId(getPresetId(context)) ?: RegionPresets.default()
        return preset.toConfig()
    }

    fun loadPreset(context: Context): RegionPreset {
        return RegionPresets.byId(getPresetId(context)) ?: RegionPresets.default()
    }

    fun displaySummary(context: Context): String {
        val preset = loadPreset(context)
        return "${preset.displayName} (${preset.countryIso})"
    }
}
