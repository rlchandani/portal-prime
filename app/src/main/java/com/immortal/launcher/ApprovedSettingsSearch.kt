/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.settings.BoolSpec
import com.immortal.launcher.settings.DerivedSpec
import com.immortal.launcher.settings.EnumSpec
import com.immortal.launcher.settings.Entry
import com.immortal.launcher.settings.IntSpec
import com.immortal.launcher.settings.NavSpec
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsPresentation
import com.immortal.launcher.settings.SettingsRegistry
import com.immortal.launcher.settings.SettingSpec
import com.immortal.launcher.settings.StringSpec

internal data class ApprovedSettingsSearchResult(
    val title: String,
    val page: String,
    val domain: String = "",
    val key: String = "",
    val help: String = "",
    val automatic: Boolean = false,
) {
    val id: String get() = if (domain.isEmpty()) "" else "$domain.$key"
    val advanced: Boolean get() = SettingsPresentation.isAdvancedPage(page) ||
        (domain.isNotEmpty() && SettingsPresentation.isAdvanced(domain, key))
}

/** Routes correspond to the controls actually rendered by each native page body. */
internal fun approvedPageForControl(domain: String, key: String): String? = when (domain) {
    "immortal" -> when (key) {
        "advancedSettings" -> "advanced"
        "settingsTheme", "tileSize", "showMiniPlayer", "hideStatusBar", "clockFormat" -> "appearance"
        "weatherUnit", "weatherWidget" -> "weather"
        "multiRoomEnabled", "snapcastHost", "maPort", "maUsername", "maPassword" -> "multiroom"
        else -> null
    }
    "screensaver" -> when (key) {
        "mode", "screensaverStyle" -> "styles"
        "clockFace" -> "faces"
        "photoSource", "albumRefreshMin" -> "sources"
        "dismissTarget" -> "dismiss"
        "sleepTimerEnabled", "sleepTimerMin", "pauseAudioOnSleep", "closeAppOnSleep" -> "sleep"
        else -> "screensaver"
    }
    "calendar" -> if (key == "url") "calendar-url" else "calendar"
    "digitalclock" -> "clock"
    "fleet" -> "device"
    "home_dashboard" -> "ha"
    else -> approvedSettingsPage(domain)?.id
}

/** Registry navigation and atomic editors own their Activity; search uses that same destination. */
private fun approvedPageForSpec(domain: String, spec: SettingSpec<*>): String? {
    val activity = when (spec) {
        is NavSpec<*> -> spec.activity
        is StringSpec<*> -> (spec.entry as? Entry.Nav)?.activity
        else -> null
    }
    return if (activity != null) {
        ApprovedSettingsPages.firstOrNull { it.standaloneActivity == activity }?.id
    } else {
        approvedPageForControl(domain, spec.key)
    }
}

/** Registry titles/help plus native editor field names. No fixture values or credentials. */
internal fun approvedSettingsSearchIndex(): List<ApprovedSettingsSearchResult> {
    val entries = mutableListOf<ApprovedSettingsSearchResult>()
    ApprovedSettingsPages.forEach { entries += ApprovedSettingsSearchResult(it.title, it.id) }
    SettingsRegistry.domains.forEach { raw ->
        @Suppress("UNCHECKED_CAST")
        val domain = raw as SettingsDomain<Any>
        domain.specs.forEach { spec ->
            val title: String
            val help: String?
            when (spec) {
                is BoolSpec<*> -> { title = spec.title; help = spec.help }
                is IntSpec<*> -> { title = spec.title; help = spec.help }
                is EnumSpec<*> -> { title = spec.title; help = spec.help }
                is StringSpec<*> -> { title = spec.title; help = spec.help }
                is NavSpec<*> -> { title = spec.title; help = spec.help }
                is DerivedSpec<*> -> return@forEach
            }
            // Registry visibility remains authoritative. Hidden managed controls appear in
            // the read-only audit; conditional controls remain searchable with prerequisites.
            val managed = "${domain.id}.${spec.key}" in ApprovedManagedControls
            val page = (if (managed) "managed" else approvedPageForSpec(domain.id, spec))
                ?: return@forEach
            entries += ApprovedSettingsSearchResult(title, page,
                domain.id, spec.key, help.orEmpty(), managed)
        }
    }
    entries += ApprovedNativeSettingsFields
    return entries.distinctBy { it.id.ifBlank { "page:${it.page}" } }
}

internal fun approvedSettingsSearch(index: List<ApprovedSettingsSearchResult>, query: String): List<ApprovedSettingsSearchResult> {
    if (query.isBlank()) return emptyList()
    return index.asSequence().filter {
        "${it.title} ${it.id} ${it.help} ${approvedSettingsPage(it.page)?.title.orEmpty()}"
            .contains(query.trim(), ignoreCase = true)
    }.take(12).toList()
}

internal val ApprovedManagedControls = setOf(
    "screensaver.cacheBudgetGb", "screensaver.antiBurnIn",
    "mqtt.validateCert", "digitalclock.style", "digitalclock.color", "digitalclock.font",
    "digitalclock.glow",
)

internal fun approvedSettingsPrerequisite(context: Context, control: String): String? {
    val domainId = control.substringBefore('.')
    val key = control.substringAfter('.', "")
    val raw = SettingsRegistry.domain(domainId) ?: return null
    @Suppress("UNCHECKED_CAST")
    val domain = raw as SettingsDomain<Any>
    val spec = domain.specs.firstOrNull { it.key == key } ?: return null
    if (control in ApprovedManagedControls) return "This value is managed by the existing app and remains read-only."
    if (domainId == "screensaver" && key != "enabled" && !ScreensaverConfig.load(context).enabled)
        return "Turn on the photo-frame screensaver to change this setting."
    // This legacy-hidden scalar is edited by the atomic Sources screen, not SettingsList.
    if (domainId == "screensaver" && key == "albumRefreshMin")
        return if (ScreensaverConfig.load(context).usesUrl) null
            else "Choose a shared album source to change its refresh interval."
    if (spec.visibleWhen(context, domain.load(context))) return null
    return when {
        domainId == "calendar" -> "Add a calendar feed and turn on Show calendar to see these options."
        domainId == "digitalclock" -> "Turn on the digital clock to see this setting."
        key == "cacheEnabled" -> "Connect an Immich or WebDAV photo source to see cache settings."
        key == "batterySaver" -> "This setting is unavailable on a Portal without a battery."
        key.startsWith("overnight") -> "Turn on Overnight screen-off to set its times."
        key.startsWith("sleepTimer") -> "Turn on Sleep timer to set its duration."
        key == "fit" -> "Choose the Classic screensaver style to change Fit."
        key == "soundVolume" -> "Choose an ambient sound to set its volume."
        domainId == "quickbar" -> "Turn on the app-switcher button to see this option."
        domainId == "chime" && key.startsWith("quiet") -> "Turn on Quiet hours to set its times."
        domainId == "chime" -> "Turn on the corresponding sound to see its options."
        else -> "This setting depends on the parent options on this page."
    }
}

private fun nativeField(id: String, title: String, page: String) =
    ApprovedSettingsSearchResult(title, page, id.substringBefore('.'), id.substringAfter('.'))

/** Supplemental searchable names for atomic forms and native-only editors. */
private val ApprovedNativeSettingsFields = listOf(
    nativeField("display.brightness", "Brightness", "display"),
    nativeField("display.auto", "Auto-brightness", "display"),
    nativeField("display.mediaVolume", "Media volume", "display"),
    nativeField("display.nightShift", "Night Shift", "display"),
    nativeField("display.from", "From", "display"),
    nativeField("display.until", "Until", "display"),
    nativeField("display.warmth", "Warmth", "display"),
    nativeField("display.touchSounds", "Touch sounds", "touch"),
    nativeField("wallpaper.mode", "Background", "wallpaper"),
    nativeField("wallpaper.grain", "Film grain", "wallpaper"),
    nativeField("sources.selected", "Photo source", "sources"),
    nativeField("sources.albumRefresh", "Refresh album", "sources"),
    nativeField("immich.url", "Server address", "immich"),
    nativeField("immich.key", "API key", "immich"),
    nativeField("immich.album", "Album", "albums"),
    nativeField("smb.host", "Server", "smb"),
    nativeField("smb.share", "Share name", "smb"),
    nativeField("smb.path", "Folder in the share", "smb"),
    nativeField("smb.username", "Username", "smb"),
    nativeField("smb.password", "Password", "smb"),
    nativeField("dav.url", "WebDAV folder address", "dav"),
    nativeField("dav.username", "Username (optional)", "dav"),
    nativeField("dav.password", "Password (optional)", "dav"),
    nativeField("web.url", "Web page address", "web"),
    nativeField("album.url", "Shared album link", "album"),
    nativeField("folder.path", "Folder", "folder"),
    nativeField("screensaver.mode", "Screensaver style", "styles"),
    nativeField("faces.enabled", "Show clock face", "faces"),
    nativeField("faces.id", "Photo clock face", "faces"),
    nativeField("faces.size", "Size", "faces"),
    nativeField("dismiss.target", "Open when you tap to exit", "dismiss"),
    nativeField("dismiss.haPath", "Home Assistant dashboard path", "dismiss"),
    nativeField("welcome.voice", "Greeting voice", "welcome"),
    nativeField("welcome.userName", "Your name", "welcome"),
    nativeField("welcome.greetingNight", "Night greeting", "welcome"),
    nativeField("welcome.greetingMorning", "Morning greeting", "welcome"),
    nativeField("welcome.greetingAfternoon", "Afternoon greeting", "welcome"),
    nativeField("welcome.greetingEvening", "Evening greeting", "welcome"),
    nativeField("welcome.textScale", "Text size", "welcome"),
    nativeField("welcome.opacity", "Background opacity", "welcome"),
    nativeField("chime.voice", "Spoken-time voice", "chime"),
    nativeField("sunrise.days", "Repeat on", "sunrise"),
    nativeField("alarm.enabled", "Enabled", "alarm"),
    nativeField("alarm.hour", "Hour", "alarm"),
    nativeField("alarm.minute", "Minute", "alarm"),
    nativeField("alarm.days", "Repeat on", "alarm"),
    nativeField("countdowns.name", "Name", "countdowns"),
    nativeField("countdowns.year", "Year", "countdowns"),
    nativeField("countdowns.month", "Month", "countdowns"),
    nativeField("countdowns.day", "Day", "countdowns"),
    nativeField("countdowns.emoji", "Icon", "countdowns"),
    nativeField("countdowns.repeat", "Repeats every year", "countdowns"),
    nativeField("ha.url", "Base URL", "ha"),
    nativeField("ha.token", "Long-lived token", "ha"),
    nativeField("ha.search", "Search entities", "ha-entities"),
    nativeField("world.city", "Search city", "world"),
    nativeField("crypto.id", "CoinGecko id", "crypto"),
    nativeField("crypto.symbol", "Display symbol (optional)", "crypto"),
    nativeField("todo.item", "What needs doing?", "todo"),
    nativeField("news.name", "Name (optional)", "news"),
    nativeField("news.url", "RSS / Atom URL", "news"),
    nativeField("stocks.symbol", "Symbol", "stocks"),
    nativeField("alpaca.key", "API Key ID", "alpaca"),
    nativeField("alpaca.secret", "API Secret Key", "alpaca"),
    nativeField("options.type", "Contract type", "options"),
    nativeField("options.deltaMin", "Delta min", "options"),
    nativeField("options.deltaMax", "Delta max", "options"),
    nativeField("options.expiry", "Expiry within (days)", "options"),
    nativeField("options.symbol", "Symbol", "options"),
    nativeField("camera.name", "Name (optional)", "cameras"),
    nativeField("camera.url", "RTSP URL", "cameras"),
    nativeField("lamp.brightness", "Brightness", "lamp"),
    nativeField("lamp.warmth", "Warmth", "lamp"),
    nativeField("lamp.red", "Red night light", "lamp"),
    nativeField("intercom.address", "Other Portal’s address", "intercom"),
    nativeField("debug.enabled", "Debug Mode", "debug"),
)
