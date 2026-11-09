/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Sidebar groups of the split-view Settings, in display order. The first group has no heading. */
internal enum class SettingsGroup(val title: String?) {
    MOST_USED(null),
    PERSONALIZE("Personalize"),
    CONNECTED_HOME("Connected home"),
    GENERAL("General"),
}

/**
 * One page of the split-view Settings. Top-level pages have a [group] and appear in the sidebar;
 * sub-pages have a [parent] and are reached from a link row inside their parent page.
 */
internal data class SettingsPageDef(
    val id: String,
    val title: String,
    val icon: String,
    val color: Long,
    val group: SettingsGroup? = null,
    val parent: String? = null,
    val lede: String? = null,
    val keywords: String = "",
    /** The whole page needs Advanced settings (its link is hidden until Advanced is on). */
    val advanced: Boolean = false,
)

/** Page ids used across the shell; sub-page ids keep the mock's naming. */
internal object SettingsPageIds {
    const val DISPLAY = "display"
    const val SOUNDS = "sounds"
    const val FRAME = "frame"
    const val ALARMS = "alarms"
    const val NIGHT = "night"
    const val WALLPAPER = "wallpaper"
    const val HOME = "home"
    const val WIDGETS = "widgets"
    const val CLOCK = "clock"
    const val SOURCES = "sources"
    const val GREETING = "greeting"
    const val HOME_ASSISTANT = "ha"
    const val PHONE = "phone"
    const val INTERCOM = "intercom"
    const val MUSIC = "music"
    const val ABOUT = "about"
    const val SYSTEM = "system"
    const val ADVANCED = "advanced"
    const val WEATHER = "w-weather"
    const val CALENDAR = "w-calendar"
    const val WORLD_CLOCK = "w-world"
    const val COUNTDOWNS = "w-countdowns"
    const val TODO = "w-todo"
    const val NEWS = "w-news"
    const val STOCKS = "w-stocks"
    const val CRYPTO = "w-crypto"
    const val GUEST = "w-guest"
    const val ALPACA = "alpaca"
    const val OPTIONS = "options"
    const val MQTT = "mqtt"
    const val DISMISS = "dismiss"
    const val BOOT = "adv-boot"
    const val MANAGED = "adv-managed"

    /** First run, and any stored id that no longer exists. */
    const val DEFAULT = DISPLAY
}

/** Icon tile colours from the approved split-view mock. */
internal object SettingsTileColors {
    const val BLUE = 0xFF2F7CF6
    const val PINK = 0xFFEF4466
    const val EMBER = 0xFFE2742E
    const val AMBER = 0xFFEE9B1A
    const val INDIGO = 0xFF5B5BD6
    const val TEAL = 0xFF169FC4
    const val VIOLET = 0xFF4F63E6
    const val GREEN = 0xFF2FAE62
    const val GRAPHITE = 0xFF5E6570
    const val GOLD = 0xFFD9A21A
    const val ROSE = 0xFFE05290
    const val CYAN = 0xFF109E9A
    const val LIME = 0xFF3AAE45
    const val PURPLE = 0xFF9A57D6
    const val RED = 0xFFF0444F
    const val GRAY = 0xFF7D838C
    const val SLATE = 0xFF4F5663
}

/** The page tree: 4 groups, 18 top-level pages, and their sub-pages (at most 3 levels deep). */
internal object SettingsTree {
    private val C = SettingsTileColors
    private val P = SettingsPageIds

    val pages: List<SettingsPageDef> = listOf(
        SettingsPageDef(P.DISPLAY, "Display & Brightness", "sun", C.BLUE, SettingsGroup.MOST_USED,
            lede = "How bright the screen is, light or dark, and warmer colours at night.",
            keywords = "screen appearance theme dark light brightness night shift warmth status bar clock 12 24 hour time format"),
        SettingsPageDef(P.SOUNDS, "Sounds", "bell", C.PINK, SettingsGroup.MOST_USED,
            lede = "Volume, touch feedback, chimes and when the Portal stays quiet.",
            keywords = "volume media touch tap click chime hourly spoken time golden hour quiet hours ambient soundscape sunrise sound"),
        SettingsPageDef(P.FRAME, "Photo Frame", "photo", C.EMBER, SettingsGroup.MOST_USED,
            lede = "What shows when nobody is using the Portal.",
            keywords = "screensaver slideshow idle photos style motion interval shuffle videos fit now playing welcome screen tap to exit wave gesture ambient dashboard"),
        SettingsPageDef(P.ALARMS, "Alarms & Wake", "timer", C.AMBER, SettingsGroup.MOST_USED,
            lede = "One alarm, and an optional sunrise that brightens the screen before it.",
            keywords = "alarm wake sunrise ramp repeat days morning"),
        SettingsPageDef(P.NIGHT, "Night & Sleep", "sleep", C.INDIGO, SettingsGroup.MOST_USED,
            lede = "Screen off overnight, the dim night clock, the sleep timer and the lamp.",
            keywords = "overnight screen off dark room night clock sleep timer countdown power idle battery lamp red light darkness lux"),
        SettingsPageDef(P.WALLPAPER, "Wallpaper", "display", C.TEAL, SettingsGroup.PERSONALIZE,
            lede = "The Home background.", keywords = "background gradient film grain sky star field"),
        SettingsPageDef(P.HOME, "Home Screen", "gridapps", C.VIOLET, SettingsGroup.PERSONALIZE,
            lede = "Layout of the Home screen and the buttons along its edge.",
            keywords = "layout customize edit tiles size mini player quick buttons app switcher"),
        SettingsPageDef(P.WIDGETS, "Widgets & Calendar", "calendar", C.GREEN, SettingsGroup.PERSONALIZE,
            lede = "Content shown by the widgets on Home and on the photo frame.",
            keywords = "weather calendar world clock countdowns to-do todo news stocks crypto guest card almanac"),
        SettingsPageDef(P.CLOCK, "Clock Faces", "clock", C.GRAPHITE, SettingsGroup.PERSONALIZE,
            lede = "The clock drawn over your photos, and the optional digital clock.",
            keywords = "photo clock face digital clock size position seconds date flip corner centred"),
        SettingsPageDef(P.SOURCES, "Photo Sources", "folder", C.GOLD, SettingsGroup.PERSONALIZE,
            lede = "Where the photo frame gets its photos.",
            keywords = "album folder shared link phone immich smb nas webdav web page refresh store media"),
        SettingsPageDef(P.GREETING, "Welcome Greeting", "hand", C.ROSE, SettingsGroup.PERSONALIZE,
            lede = "The message shown when someone walks up to the Portal.",
            keywords = "greeting name morning evening night afternoon speak tts overlay"),
        SettingsPageDef(P.HOME_ASSISTANT, "Home Assistant", "home", C.CYAN, SettingsGroup.CONNECTED_HOME,
            lede = "Show your Home Assistant dashboard and control the Portal from it.",
            keywords = "dashboard url token entities mqtt broker"),
        SettingsPageDef(P.PHONE, "Phone Remote", "remote", C.LIME, SettingsGroup.CONNECTED_HOME,
            lede = "Use your phone as a remote and to send photos.",
            keywords = "pair qr code pin remote control photo imports share sharing notes"),
        SettingsPageDef(P.INTERCOM, "Intercom", "intercom", C.PURPLE, SettingsGroup.CONNECTED_HOME,
            lede = "Talk to other rooms with a Portal.", keywords = "broadcast listen room address dinner call"),
        SettingsPageDef(P.MUSIC, "Music", "music", C.RED, SettingsGroup.CONNECTED_HOME,
            lede = "Music that follows you between rooms, and how loud it may get.",
            keywords = "follow rooms quiet loudest history multi-room snapcast group music assistant"),
        SettingsPageDef(P.ABOUT, "About & Health", "info", C.GRAY, SettingsGroup.GENERAL,
            keywords = "version update device name model health checks tour help welcome uninstall screen lock permission"),
        SettingsPageDef(P.SYSTEM, "Portal & Android", "gear", C.SLATE, SettingsGroup.GENERAL,
            lede = "These open the Portal’s own settings and leave Hearthpane.",
            keywords = "wifi wi-fi bluetooth date time language region accessibility storage permissions android portal system"),
        SettingsPageDef(P.ADVANCED, "Advanced", "tools", C.GRAPHITE, SettingsGroup.GENERAL,
            lede = "Technical options for tinkerers.", keywords = "boot startup apk speed test debug diagnostics managed"),
        // Sub-pages.
        SettingsPageDef(P.WEATHER, "Weather", "weather", C.BLUE, parent = P.WIDGETS, keywords = "temperature celsius fahrenheit"),
        SettingsPageDef(P.CALENDAR, "Calendar", "calendar", C.RED, parent = P.WIDGETS,
            keywords = "feed ics ical events almanac holidays name days prayer times"),
        SettingsPageDef(P.WORLD_CLOCK, "World clock", "clock", C.GRAPHITE, parent = P.WIDGETS, keywords = "cities time zones"),
        SettingsPageDef(P.COUNTDOWNS, "Countdowns", "countdowns", C.AMBER, parent = P.WIDGETS, keywords = "events days until"),
        SettingsPageDef(P.TODO, "To-do", "todo", C.GREEN, parent = P.WIDGETS, keywords = "list household shopping"),
        SettingsPageDef(P.NEWS, "News", "news", C.RED, parent = P.WIDGETS, keywords = "rss atom feeds headlines"),
        SettingsPageDef(P.STOCKS, "Stocks", "stocks", C.SLATE, parent = P.WIDGETS, keywords = "symbols shares market"),
        SettingsPageDef(P.CRYPTO, "Crypto", "crypto", C.GOLD, parent = P.WIDGETS, keywords = "coins bitcoin coingecko"),
        SettingsPageDef(P.GUEST, "Guest card", "wifi", C.ROSE, parent = P.WIDGETS, keywords = "visitors wi-fi password door"),
        SettingsPageDef(P.ALPACA, "Alpaca account", "stocks", C.SLATE, parent = P.STOCKS, advanced = true,
            keywords = "api key secret brokerage"),
        SettingsPageDef(P.OPTIONS, "Options scanner", "options", C.SLATE, parent = P.WIDGETS, advanced = true,
            keywords = "contracts delta expiry"),
        SettingsPageDef(P.MQTT, "MQTT publishing", "plug", C.CYAN, parent = P.HOME_ASSISTANT, advanced = true,
            keywords = "broker host port tls"),
        SettingsPageDef(P.DISMISS, "Open when you tap to exit", "photo", C.EMBER, parent = P.FRAME,
            keywords = "dismiss target leave screensaver"),
        SettingsPageDef(P.BOOT, "Start on boot", "tools", C.GRAPHITE, parent = P.ADVANCED, advanced = true,
            keywords = "startup app reboot"),
        SettingsPageDef(P.MANAGED, "Automatically managed", "tools", C.GRAPHITE, parent = P.ADVANCED, advanced = true,
            keywords = "read-only storage limit burn-in certificate clock style colour font glow"),
    )

    private val byId: Map<String, SettingsPageDef> = pages.associateBy { it.id }

    fun page(id: String?): SettingsPageDef? = id?.let(byId::get)

    fun contains(id: String?): Boolean = page(id) != null

    /** Top-level pages of one sidebar group, in order. */
    fun groupPages(group: SettingsGroup): List<SettingsPageDef> = pages.filter { it.group == group }

    val topLevel: List<SettingsPageDef> get() = pages.filter { it.parent == null }

    /** Root first, ending with [id]; empty for an unknown id. */
    fun chainOf(id: String): List<String> {
        val chain = ArrayList<String>()
        var current = page(id)
        while (current != null && current.id !in chain) {
            chain.add(0, current.id)
            current = page(current.parent)
        }
        return chain
    }

    fun rootOf(id: String): String = chainOf(id).firstOrNull() ?: SettingsPageIds.DEFAULT

    fun childrenOf(id: String): List<SettingsPageDef> = pages.filter { it.parent == id }

    /** Any page that needs Advanced, itself or through an ancestor. */
    fun needsAdvanced(id: String): Boolean = chainOf(id).any { page(it)?.advanced == true }
}
