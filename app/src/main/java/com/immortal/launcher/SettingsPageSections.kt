/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SettingsBlock as B
import com.immortal.launcher.SettingsExternal as E
import com.immortal.launcher.SettingsPageIds as P

private fun rows(domain: String, vararg keys: String) = SettingsDomainRows(domain, keys.toList())
private fun block(block: B) = SettingsBlockItem(block)
private fun link(title: String, target: String, keywords: String = "") = SettingsLinkItem(title, target, keywords)
private fun ext(title: String, target: E, subtitle: String = "", keywords: String = "") =
    SettingsExternalItem(title, target, subtitle, keywords)
private fun section(header: String?, vararg items: SettingsItem, footer: String? = null, advanced: Boolean = false) =
    SettingsSection(header, items.toList(), footer, advanced)

private const val SCREENSAVER = "screensaver"
private const val IMMORTAL = "immortal"

/**
 * What each page shows, section by section, following the split-view mock's mapping. Every
 * existing row has exactly one home here; `SettingsTreeTest` enforces it against the registry.
 */
internal object SettingsPageSections {
    private val sections: Map<String, List<SettingsSection>> = mapOf(
        P.DISPLAY to listOf(
            section("Appearance", rows(IMMORTAL, "settingsTheme")),
            section(null, block(B.DISPLAY)),
            section("Time & status bar", rows(IMMORTAL, "clockFormat", "hideStatusBar"),
                footer = "Time format applies to Home, the photo frame and every clock face."),
        ),
        P.SOUNDS to listOf(
            section(null, block(B.MEDIA_VOLUME)),
            section(null, block(B.TOUCH_SOUNDS)),
            section(null, block(B.CHIMES),
                footer = "Music has its own quiet-hours limit in Music."),
            section("Ambient soundscape", rows(SCREENSAVER, "soundscape", "soundscapeVolume"),
                footer = "Plays softly while the photo frame is showing."),
        ),
        P.FRAME to listOf(
            section(null, rows(SCREENSAVER, "enabled")),
            section("Style", rows(SCREENSAVER, "mode", "photo_motion", "keep_people_in_frame", "fit")),
            section("Photos", rows(SCREENSAVER, "photoSource", "intervalSec", "shuffle", "includeVideo")),
            section("On top of photos", rows(SCREENSAVER, "clockFace", "showNowPlaying", "welcomeEnabled"),
                link("Calendar on the photo frame", P.CALENDAR, "calendar widget events")),
            section("When you tap", rows(SCREENSAVER, "dismissTarget", "gestureWave")),
            section("Advanced", rows(SCREENSAVER, "ambientDashboard"), advanced = true),
            section(null, block(B.SCREENSAVER_PREVIEW)),
        ),
        P.ALARMS to listOf(
            section(null, block(B.ALARM)),
            section(null, block(B.SUNRISE)),
        ),
        P.NIGHT to listOf(
            section("Overnight", rows(SCREENSAVER, "overnightEnabled", "overnightStartMin", "overnightEndMin",
                "overnightNightClock")),
            section("Dark room", rows(SCREENSAVER, "darkRoomClock", "nightClockBrightness", "nightClockTone",
                "darkRoomLux")),
            section(null, block(B.SLEEP_TIMER)),
            section("Power", rows(SCREENSAVER, "presenceMode", "idleSleepMin", "batterySaver"), advanced = true),
            section("Lamp", ext("Open Lamp", E.LAMP, "Brightness, warmth and a red night light.", "light night light")),
        ),
        P.WALLPAPER to listOf(section(null, block(B.WALLPAPER))),
        P.HOME to listOf(
            section(null, ext("Customize Home…", E.CUSTOMIZE_HOME,
                "Touch and hold a widget on Home to move, resize or remove it.", "edit layout arrange widgets")),
            section("Layout", rows(IMMORTAL, "tileSize", "showMiniPlayer")),
            section(null, block(B.QUICK_BUTTONS)),
        ),
        P.WIDGETS to listOf(
            section(null, link("Calendar", P.CALENDAR, "feed events")),
            section("Widgets", link("Weather", P.WEATHER), link("World clock", P.WORLD_CLOCK),
                link("Countdowns", P.COUNTDOWNS), link("To-do", P.TODO), link("News", P.NEWS),
                link("Stocks", P.STOCKS), link("Crypto", P.CRYPTO), link("Guest card", P.GUEST),
                footer = "Alarm and Home Assistant widgets are set up in Alarms & Wake and Home Assistant."),
            section("Advanced", link("Options scanner", P.OPTIONS), advanced = true),
        ),
        P.WEATHER to listOf(section(null, rows(IMMORTAL, "weatherUnit", "weatherWidget"))),
        P.CALENDAR to listOf(
            section("Feed", rows("calendar", "url")),
            section("Photo frame", rows("calendar", "widgetOn", "range", "size", "side")),
            section("Almanac", block(B.ALMANAC), advanced = true),
        ),
        P.WORLD_CLOCK to listOf(section(null, block(B.WORLD_CLOCK))),
        P.COUNTDOWNS to listOf(section(null, block(B.COUNTDOWNS))),
        P.TODO to listOf(
            section(null, block(B.TODO)),
            section("Shared list", rows("householdlist", "showWho", "widgetShowsDone")),
        ),
        P.NEWS to listOf(section(null, block(B.NEWS))),
        P.STOCKS to listOf(section(null, block(B.STOCKS))),
        P.ALPACA to listOf(section(null, block(B.ALPACA))),
        P.CRYPTO to listOf(section(null, block(B.CRYPTO))),
        P.GUEST to listOf(section(null, block(B.GUEST))),
        P.OPTIONS to listOf(section(null, block(B.OPTIONS))),
        P.CLOCK to listOf(
            section(null, block(B.PHOTO_CLOCK_FACE),
                footer = "Time format (12/24-hour) is set once, in Display & Brightness."),
            section(null, block(B.DIGITAL_CLOCK)),
        ),
        P.SOURCES to listOf(
            section(null, block(B.SOURCES)),
            section("Self-hosted", rows(SCREENSAVER, "cacheEnabled"), advanced = true,
                footer = "Keeps a copy so photos show when offline."),
        ),
        P.DISMISS to listOf(section(null, block(B.DISMISS))),
        P.GREETING to listOf(section(null, block(B.WELCOME))),
        P.HOME_ASSISTANT to listOf(
            section(null, block(B.HOME_ASSISTANT)),
            section("MQTT", link("MQTT publishing", P.MQTT, "broker"), advanced = true),
        ),
        P.MQTT to listOf(section(null, block(B.MQTT))),
        P.PHONE to listOf(
            section("Pairing", block(B.PHONE_PAIRING),
                footer = "The QR code and PIN appear only after you tap Show pairing code."),
            section("Sharing", rows("share", "allowSharing", "freshPhotoBadge", "noteChime", "clearNotesOvernight")),
        ),
        P.INTERCOM to listOf(
            section(null, block(B.INTERCOM)),
            section("Calls from other rooms", rows("dinner", "receive", "speak")),
        ),
        P.MUSIC to listOf(
            section(null, block(B.MUSIC)),
            section("Multi-room audio", block(B.MULTIROOM), advanced = true),
        ),
        P.ABOUT to listOf(
            section("This Portal", block(B.DEVICE_NAME)),
            section(null, block(B.ABOUT_HEALTH)),
            section("Help", ext("Take the Hearthpane tour", E.HELP_TOUR, keywords = "welcome to hearthpane guided")),
        ),
        P.SYSTEM to listOf(
            section("Network", ext("Wi-Fi", E.WIFI), ext("Bluetooth", E.BLUETOOTH)),
            section("System", ext("Date & time", E.DATE_TIME), ext("Language & region", E.LANGUAGE),
                ext("Accessibility", E.ACCESSIBILITY), ext("Storage", E.STORAGE),
                ext("App permissions", E.APP_PERMISSIONS)),
            section(null, ext("All Android Settings", E.ALL_ANDROID), ext("Portal Settings", E.PORTAL),
                footer = "Press Home to come back."),
        ),
        P.ADVANCED to listOf(
            section(null, block(B.ADVANCED_SWITCH)),
            section("Startup", link("Start on boot", P.BOOT), ext("Install an APK", E.INSTALL_APK), advanced = true),
            section("Tools", ext("Speed test", E.SPEED_TEST, "Open Tools to measure your connection."), advanced = true),
            section("Diagnostics", block(B.DEBUG_MODE), link("Automatically managed settings", P.MANAGED),
                advanced = true),
        ),
        P.BOOT to listOf(section(null, block(B.BOOT_APPS))),
        P.MANAGED to listOf(section(null, block(B.MANAGED), footer = "Read-only. These follow from other choices.")),
    )

    fun of(page: String): List<SettingsSection> = sections[page].orEmpty()

    val pageIds: Set<String> get() = sections.keys

    /** The page whose registry row list draws [control] (`domain.key`), if any. */
    fun domainRowPage(control: String): String? = sections.entries.firstOrNull { (_, list) ->
        list.any { section -> section.items.any { it is SettingsDomainRows && control in it.ids() } }
    }?.key
}
