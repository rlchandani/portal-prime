/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** What the sidebar shows at a glance beside a few rows; null fields are not known yet. */
internal data class SettingsSidebarFacts(
    val photoFrameOn: Boolean? = null,
    val alarmOn: Boolean? = null,
    val alarmMinuteOfDay: Int = 0,
    val wallpaper: String? = null,
)

private const val ON = "On"
private const val OFF = "Off"
private const val MINUTES_PER_HOUR = 60

/**
 * The grey status beside a sidebar row, as in the approved mock: Photo Frame On/Off, the alarm
 * time (or Off), the wallpaper's name, and Advanced On/Off. Pure; null means no hint.
 */
internal fun settingsSidebarHint(page: String, facts: SettingsSidebarFacts, advanced: Boolean): String? = when (page) {
    SettingsPageIds.FRAME -> facts.photoFrameOn?.let { if (it) ON else OFF }
    SettingsPageIds.ALARMS -> facts.alarmOn?.let { on -> if (on) clockLabel(facts.alarmMinuteOfDay) else OFF }
    SettingsPageIds.WALLPAPER -> facts.wallpaper
    SettingsPageIds.ADVANCED -> if (advanced) ON else OFF
    else -> null
}

/** 24-hour "HH:mm", the alarm page's own format. */
internal fun clockLabel(minuteOfDay: Int): String =
    "%02d:%02d".format(minuteOfDay / MINUTES_PER_HOUR % 24, minuteOfDay % MINUTES_PER_HOUR)

/** The wallpaper's label from the swatch list (the same names the Wallpaper page shows). */
internal fun wallpaperHintLabel(mode: String, choices: List<Pair<String, String>>): String =
    choices.firstOrNull { it.first == mode }?.second ?: "Custom"
