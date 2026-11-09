/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SettingsPageIds as P

/** An old name ("Slideshow", "Device name"…) that search still understands, and where it lives now. */
internal data class SettingsSearchAlias(
    val oldPlace: String,
    val oldName: String,
    val page: String,
    val control: String? = null,
)

private fun alias(oldPlace: String, oldName: String, page: String, control: String? = null) =
    SettingsSearchAlias(oldPlace, oldName, page, control)

/** From the split-view mock's "Where did X go?" table (old page › old item → new page and row). */
internal val SettingsSearchAliases: List<SettingsSearchAlias> = listOf(
    alias("Settings", "Light appearance", P.DISPLAY, "immortal.settingsTheme"),
    alias("Settings", "Dark appearance", P.DISPLAY, "immortal.settingsTheme"),
    alias("Settings", "Advanced settings", P.ADVANCED, "immortal.advancedSettings"),
    alias("Settings", "Device name", P.ABOUT, "fleet.name"),
    alias("Appearance", "Display", P.DISPLAY),
    alias("Appearance", "App appearance", P.DISPLAY, "immortal.settingsTheme"),
    alias("Appearance › Display", "Volume", P.SOUNDS, "display.mediaVolume"),
    alias("Appearance › App appearance", "Clock", P.DISPLAY, "immortal.clockFormat"),
    alias("Appearance › App appearance", "App tile size", P.HOME, "immortal.tileSize"),
    alias("Photos & screensaver", "Slideshow", P.FRAME),
    alias("Photos & screensaver", "Screensaver", P.FRAME, "screensaver.enabled"),
    alias("Photos › Slideshow", "Show the photo-frame screensaver", P.FRAME, "screensaver.enabled"),
    alias("Photos › Slideshow", "Screensaver style", P.FRAME, "screensaver.mode"),
    alias("Photos › Slideshow", "Power", P.NIGHT, "screensaver.presenceMode"),
    alias("Photos › Slideshow", "Idle screen-off", P.NIGHT, "screensaver.idleSleepMin"),
    alias("Photos › Slideshow", "Gesture wave to wake", P.FRAME, "screensaver.gestureWave"),
    alias("Photos › Slideshow", "Close app on sleep", P.NIGHT, "screensaver.closeAppOnSleep"),
    alias("Photos › Slideshow", "Calendar on the screensaver", P.CALENDAR, "calendar.widgetOn"),
    alias("Photos › Slideshow", "Preview screensaver", P.FRAME, "frame.preview"),
    alias("Photos & screensaver", "Photo clock faces", P.CLOCK, "faces.enabled"),
    alias("Photos & screensaver", "Digital clock", P.CLOCK, "digitalclock.enabled"),
    alias("Photos & screensaver", "Photo sources", P.SOURCES),
    alias("Home & widgets", "Home dashboard", P.WIDGETS),
    alias("Home & widgets › Home dashboard", "Customize home", P.HOME, "ext.customize_home"),
    alias("Home & widgets", "Quick buttons", P.HOME, "quickbar.enabled"),
    alias("Home & widgets", "Alarm", P.ALARMS, "alarm.enabled"),
    alias("Sound & wake", "Touch sounds", P.SOUNDS, "display.touchSounds"),
    alias("Sound & wake › Sounds", "Volume", P.SOUNDS, "chime.volume"),
    alias("Sound & wake", "Welcome greeting", P.GREETING),
    alias("Sound & wake", "Sunrise & wake", P.ALARMS, "sunrise.enabled"),
    alias("Sound & wake", "Lamp", P.NIGHT, "ext.lamp"),
    alias("Connections", "Home Assistant dashboard", P.HOME_ASSISTANT),
    alias("Connections", "Phone remote", P.PHONE, "phone.show"),
    alias("Connections", "Control from your phone", P.PHONE, "phone.show"),
    alias("Input & accessibility", "Android & Portal settings", P.SYSTEM),
    alias("Device", "Sleep timer", P.NIGHT, "screensaver.sleepTimerEnabled"),
    alias("Device", "About & device health", P.ABOUT, "about.checks"),
    alias("Device", "Welcome to Hearthpane", P.ABOUT, "ext.help_tour"),
    alias("Advanced", "Almanac", P.CALENDAR, "calendarpacks.prayer"),
    alias("Advanced", "Multi-room audio", P.MUSIC, "immortal.multiRoomEnabled"),
    alias("Advanced", "Diagnostics", P.ADVANCED, "debug.enabled"),
    alias("Advanced", "Automatically managed settings", P.MANAGED),
)
