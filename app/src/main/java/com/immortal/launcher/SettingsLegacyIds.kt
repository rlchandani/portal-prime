/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SettingsPageIds as P

/** Where an old page id now lives: an in-pane page (optionally a row to highlight) or an Activity. */
internal sealed interface SettingsDestination {
    data class Page(val page: String, val control: String? = null) : SettingsDestination
    /** A full-screen editor that keeps its own Activity; [id] keys `SettingsActivityDestinations`. */
    data class Activity(val id: String) : SettingsDestination
}

/**
 * Old Settings page and category ids (deep links from Home widgets, Music, Guest, the phone remote
 * and saved instance state) mapped onto the split-view tree. New page ids resolve to themselves.
 */
internal object SettingsLegacyIds {
    /** Pages that were (and stay) separate Activities; their launch classes live in the Android layer. */
    val activityPages: Set<String> = setOf(
        "folder", "album", "immich", "albums", "smb", "dav", "web", "cameras", "calendar-url",
        "lamp", "ha-entities", "ha-dashboard", "apk", "help",
    )

    /** The page each full-screen editor belongs to, so its sidebar highlights the right row. */
    val activityHomes: Map<String, String> = mapOf(
        "folder" to P.SOURCES, "album" to P.SOURCES, "immich" to P.SOURCES, "albums" to P.SOURCES,
        "smb" to P.SOURCES, "dav" to P.SOURCES, "web" to P.SOURCES, "cameras" to P.WIDGETS,
        "calendar-url" to P.CALENDAR, "lamp" to P.NIGHT, "ha-entities" to P.HOME_ASSISTANT,
        "ha-dashboard" to P.HOME_ASSISTANT, "apk" to P.ADVANCED, "help" to P.ABOUT,
    )

    private fun page(id: String, control: String? = null) = SettingsDestination.Page(id, control)

    /** Every page id of the previous 8-category Settings, with its new home. */
    val pages: Map<String, SettingsDestination.Page> = mapOf(
        "touch" to page(P.SOUNDS, "display.touchSounds"),
        "display" to page(P.DISPLAY),
        "appearance" to page(P.DISPLAY, "immortal.settingsTheme"),
        "wallpaper" to page(P.WALLPAPER),
        "screensaver" to page(P.FRAME),
        "sources" to page(P.SOURCES),
        "styles" to page(P.FRAME, "screensaver.mode"),
        "faces" to page(P.CLOCK, "faces.enabled"),
        // The old "clock" id was the Digital clock page; it is now a section of Clock Faces ("clock").
        "clock" to page(P.CLOCK),
        "dismiss" to page(P.DISMISS),
        "widgets" to page(P.WIDGETS),
        "weather" to page(P.WEATHER),
        "calendar" to page(P.CALENDAR),
        "almanac" to page(P.CALENDAR, "calendarpacks.romanian"),
        "world" to page(P.WORLD_CLOCK),
        "countdowns" to page(P.COUNTDOWNS),
        "guests" to page(P.GUEST),
        "quickbar" to page(P.HOME, "quickbar.enabled"),
        "alarm" to page(P.ALARMS, "alarm.enabled"),
        "crypto" to page(P.CRYPTO),
        "todo" to page(P.TODO),
        "news" to page(P.NEWS),
        "stocks" to page(P.STOCKS),
        "alpaca" to page(P.ALPACA),
        "options" to page(P.OPTIONS),
        "chime" to page(P.SOUNDS, "chime.hourlyChimeOn"),
        "music" to page(P.MUSIC),
        "welcome" to page(P.GREETING),
        "sunrise" to page(P.ALARMS, "sunrise.enabled"),
        "multiroom" to page(P.MUSIC, "immortal.multiRoomEnabled"),
        "mqtt" to page(P.MQTT),
        "ha" to page(P.HOME_ASSISTANT),
        "phone" to page(P.PHONE),
        "intercom" to page(P.INTERCOM),
        "boot" to page(P.BOOT),
        "device" to page(P.ABOUT, "fleet.name"),
        "sleep" to page(P.NIGHT, "screensaver.sleepTimerEnabled"),
        "health" to page(P.ABOUT, "about.checks"),
        "speed" to page(P.ADVANCED, "ext.speed_test"),
        "system" to page(P.SYSTEM),
        "managed" to page(P.MANAGED),
        "debug" to page(P.ADVANCED, "debug.enabled"),
        "advanced" to page(P.ADVANCED),
    )

    /** The old sidebar categories, for intents that carry only a category. */
    val categories: Map<String, String> = mapOf(
        "appearance" to P.DISPLAY,
        "photos" to P.FRAME,
        "home" to P.HOME,
        "sound" to P.SOUNDS,
        "connections" to P.HOME_ASSISTANT,
        "access" to P.SYSTEM,
        "device" to P.ABOUT,
        "advanced" to P.ADVANCED,
    )

    /**
     * Resolve an old or new page id (and an optional old category) to its destination. New ids win
     * over old ones so `display`, `music`, `sources`, `clock`… keep pointing at the tree page. An
     * explicit [control] from the caller replaces the mapping's default highlight.
     */
    fun resolve(page: String?, category: String? = null, control: String? = null): SettingsDestination? {
        if (page != null) {
            if (page in activityPages) return SettingsDestination.Activity(page)
            if (SettingsTree.contains(page)) return page(page, control)
            pages[page]?.let { return it.copy(control = control ?: it.control) }
        }
        val root = category?.let(categories::get) ?: return null
        return page(root, control)
    }
}
