/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** A searchable row drawn by a bespoke block (not a registry spec). Id is `area.field`. */
internal data class SettingsNativeRow(
    val id: String,
    val title: String,
    val keywords: String = "",
    /** Shown only with Advanced settings on (in addition to the registry's presentation policy). */
    val advanced: Boolean = false,
)

private fun row(id: String, title: String, keywords: String = "", advanced: Boolean = false) =
    SettingsNativeRow(id, title, keywords, advanced)

/**
 * Existing bespoke editors, moved unchanged into the new pages. [claims] lists the registry
 * controls each one renders (`domain.key`, or `domain.*` for the whole domain) so the tree test
 * can prove every registered control has exactly one home. [rows] are its non-registry rows,
 * indexed by search.
 */
internal enum class SettingsBlock(
    val claims: Set<String> = emptySet(),
    val rows: List<SettingsNativeRow> = emptyList(),
    /** Sub-pages the block's own rows open (e.g. Stocks › Alpaca account). */
    val opens: Set<String> = emptySet(),
) {
    DISPLAY(rows = listOf(row("display.brightness", "Brightness"), row("display.auto", "Auto-brightness"),
        row("display.nightShift", "Night Shift", "warm amber tint"), row("display.from", "From"),
        row("display.until", "Until"), row("display.warmth", "Warmth"))),
    MEDIA_VOLUME(rows = listOf(row("display.mediaVolume", "Media volume", "music video"))),
    TOUCH_SOUNDS(rows = listOf(row("display.touchSounds", "Touch sounds", "tap click feedback"))),
    CHIMES(claims = setOf("chime.*"), rows = listOf(row("chime.voice", "Spoken-time voice"),
        row("chime.preview", "Preview sounds", "test ping voice"))),
    ALARM(rows = listOf(row("alarm.enabled", "Alarm"),
        row("alarm.hour", "Alarm time", "hour minute alarm.minute"), row("alarm.days", "Repeat on"))),
    SUNRISE(claims = setOf("sunrise.*"), rows = listOf(row("sunrise.days", "Wake on", "repeat"),
        row("sunrise.preview", "Preview (1-min ramp)", "sunrise preview"))),
    SLEEP_TIMER(claims = setOf("screensaver.sleepTimerEnabled", "screensaver.sleepTimerMin",
        "screensaver.pauseAudioOnSleep", "screensaver.closeAppOnSleep"),
        rows = listOf(row("sleep.countdown", "Start countdown", "sleep timer"))),
    SCREENSAVER_PREVIEW(rows = listOf(row("frame.preview", "Preview full screen", "preview screensaver"))),
    WALLPAPER(rows = listOf(row("wallpaper.mode", "Background", "gradient sky star field"),
        row("wallpaper.grain", "Film grain"))),
    QUICK_BUTTONS(claims = setOf("quickbar.*")),
    ALMANAC(claims = setOf("calendarpacks.*")),
    WORLD_CLOCK(rows = listOf(row("world.city", "Search city"))),
    COUNTDOWNS(rows = listOf(row("countdowns.name", "Name"), row("countdowns.year", "Year"),
        row("countdowns.month", "Month"), row("countdowns.day", "Day"), row("countdowns.emoji", "Icon"),
        row("countdowns.repeat", "Repeats every year"))),
    TODO(rows = listOf(row("todo.item", "What needs doing?"))),
    NEWS(rows = listOf(row("news.name", "Name (optional)"), row("news.url", "RSS / Atom URL"))),
    STOCKS(rows = listOf(row("stocks.symbol", "Symbol")), opens = setOf(SettingsPageIds.ALPACA)),
    ALPACA(rows = listOf(row("alpaca.key", "API Key ID"), row("alpaca.secret", "API Secret Key"))),
    CRYPTO(rows = listOf(row("crypto.id", "CoinGecko id"), row("crypto.symbol", "Display symbol (optional)"))),
    GUEST(claims = setOf("guests.*")),
    OPTIONS(rows = listOf(row("options.type", "Contract type"), row("options.deltaMin", "Delta min"),
        row("options.deltaMax", "Delta max"), row("options.expiry", "Expiry within (days)"),
        row("options.symbol", "Symbol"))),
    PHOTO_CLOCK_FACE(rows = listOf(row("faces.enabled", "Show clock face"),
        row("faces.id", "Photo clock face", "face style"), row("faces.size", "Size"))),
    DIGITAL_CLOCK(claims = setOf("digitalclock.enabled", "digitalclock.face", "digitalclock.size",
        "digitalclock.layout", "digitalclock.background", "digitalclock.showDate", "digitalclock.showSeconds")),
    SOURCES(claims = setOf("screensaver.albumRefreshMin"), rows = listOf(
        row("sources.selected", "Photo source", "current source"), row("sources.default", "Hearthpane photos"),
        row("sources.folder", "My photos & videos", "folder"), row("sources.album", "Shared album link"),
        row("sources.phone", "Connect your phone"), row("sources.immich", "Immich"),
        row("sources.smb", "SMB / NAS", "network share"), row("sources.dav", "WebDAV"),
        row("sources.web", "Web page"))),
    DISMISS(rows = listOf(row("dismiss.target", "Open when you tap to exit"),
        row("dismiss.haPath", "Home Assistant dashboard path"))),
    WELCOME(claims = setOf("welcome.*"), rows = listOf(row("welcome.voice", "Greeting voice"),
        row("welcome.userName", "Your name"), row("welcome.greetingNight", "Night greeting"),
        row("welcome.greetingMorning", "Morning greeting"), row("welcome.greetingAfternoon", "Afternoon greeting"),
        row("welcome.greetingEvening", "Evening greeting"), row("welcome.textScale", "Text size"),
        row("welcome.opacity", "Background opacity"))),
    HOME_ASSISTANT(claims = setOf("home_dashboard.url"), rows = listOf(row("ha.url", "Base URL"),
        row("ha.token", "Long-lived token"), row("ha.entities", "Choose Home Assistant entities", advanced = true),
        row("ha.dashboard", "Home Assistant dashboard"))),
    MQTT(claims = setOf("mqtt.enabled", "mqtt.host", "mqtt.port", "mqtt.username", "mqtt.password", "mqtt.useTls")),
    PHONE_PAIRING(rows = listOf(row("phone.show", "Show pairing code", "qr pin pair"),
        row("phone.generate", "Generate new code"), row("phone.imports", "Allow photo imports"))),
    INTERCOM(rows = listOf(row("intercom.address", "Other Portal’s address"),
        row("intercom.broadcast", "Broadcast this room"), row("intercom.listen", "Listen"))),
    MUSIC(claims = setOf("followme.*", "music.*"), rows = listOf(row("music.clearHistory", "Clear music history"))),
    MULTIROOM(claims = setOf("immortal.multiRoomEnabled", "immortal.snapcastHost", "immortal.maPort",
        "immortal.maUsername", "immortal.maPassword", "immortal.maApiToken", "immortal.maPlayerId"),
        rows = listOf(row("multiroom.test", "Test connection"))),
    DEVICE_NAME(claims = setOf("fleet.*"), rows = listOf(row("about.model", "Model"))),
    ABOUT_HEALTH(rows = listOf(row("about.version", "Hearthpane version", "check for updates update now"),
        row("about.checks", "Device checks", "health permissions reconnect"),
        row("about.screenLock", "Remove screen-lock permission", "uninstall"))),
    ADVANCED_SWITCH(claims = setOf("immortal.advancedSettings")),
    DEBUG_MODE(rows = listOf(row("debug.enabled", "Debug mode", "bug button diagnostics"))),
    BOOT_APPS(claims = setOf("immortal.stayInStartupApp"), rows = listOf(row("boot.apps", "Open at startup"))),
    MANAGED(claims = ApprovedManagedControls),
}

/** Destinations outside the in-pane tree: their own Activity, Home, or the system settings. */
internal enum class SettingsExternal {
    CUSTOMIZE_HOME, LAMP, HELP_TOUR, INSTALL_APK, SPEED_TEST,
    WIFI, BLUETOOTH, DATE_TIME, LANGUAGE, ACCESSIBILITY, STORAGE, APP_PERMISSIONS, ALL_ANDROID, PORTAL,
}

/** Rows registered by the settings registry, plus bespoke content and navigation. */
internal sealed interface SettingsItem {
    /** Stable lazy-list key, unique within its page. */
    val key: String
}

/** Registry controls of one domain, drawn by the shared renderer in this order. */
internal data class SettingsDomainRows(val domain: String, val keys: List<String>) : SettingsItem {
    override val key: String get() = "rows:$domain:${keys.first()}"
    fun ids(): List<String> = keys.map { "$domain.$it" }
}

internal data class SettingsBlockItem(val block: SettingsBlock) : SettingsItem {
    override val key: String get() = "block:${block.name}"
}

/** A row that opens a sub-page in the right column. */
internal data class SettingsLinkItem(val title: String, val target: String, val keywords: String = "") : SettingsItem {
    override val key: String get() = "link.$target"
}

/** A row that leaves the in-pane tree. */
internal data class SettingsExternalItem(
    val title: String,
    val target: SettingsExternal,
    val subtitle: String = "",
    val keywords: String = "",
) : SettingsItem {
    override val key: String get() = "ext.${target.name.lowercase()}"
}

/** One inset grouped card. Advanced sections are hidden until Advanced settings is on. */
internal data class SettingsSection(
    val header: String?,
    val items: List<SettingsItem>,
    val footer: String? = null,
    val advanced: Boolean = false,
)

/** Domain key matching for block claims, including `domain.*`. */
internal fun SettingsBlock.claimsControl(id: String): Boolean =
    id in claims || "${id.substringBefore('.')}.*" in claims
