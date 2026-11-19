/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.awaitCancellation

private val SidebarFactPrefs = listOf(ScreensaverConfig.PREFS, AlarmConfig.PREFS, WallpaperConfig.PREFS)
private const val SCREENSAVER_ENABLED_KEY = "enabled"
private const val SCREENSAVER_ENABLED_DEFAULT = true

internal fun loadSettingsSidebarFacts(context: Context): SettingsSidebarFacts {
    val frameOn = context.getSharedPreferences(ScreensaverConfig.PREFS, Context.MODE_PRIVATE)
        .getBoolean(SCREENSAVER_ENABLED_KEY, SCREENSAVER_ENABLED_DEFAULT)
    val alarm = AlarmConfig.load(context)
    val wallpaper = wallpaperHintLabel(WallpaperConfig.load(context).mode, WallpaperChoices)
    return SettingsSidebarFacts(frameOn, alarm.enabled, alarm.hour * 60 + alarm.minute, wallpaper)
}

/**
 * Sidebar status facts, read after the first frame (Settings opens without waiting on them) and
 * refreshed whenever one of their preference files changes, so a hint follows its switch at once.
 */
@Composable
internal fun rememberSettingsSidebarFacts(): State<SettingsSidebarFacts> {
    val context = LocalContext.current.applicationContext
    // Read in the first composition: the launcher process already holds these small preference
    // files in memory, and the first frame then shows the final hints (no second relayout of the list).
    val facts = remember { mutableStateOf(loadSettingsSidebarFacts(context)) }
    LaunchedEffect(context) {
        // Held here: SharedPreferences keeps listeners weakly.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            facts.value = loadSettingsSidebarFacts(context)
        }
        val prefs = SidebarFactPrefs.map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        prefs.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        try {
            // Anything that changed between the first composition and registering the listener.
            facts.value = loadSettingsSidebarFacts(context)
            awaitCancellation()
        } finally {
            prefs.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }
    return facts
}
