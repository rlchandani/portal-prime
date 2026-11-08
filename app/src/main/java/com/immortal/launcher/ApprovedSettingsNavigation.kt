/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.immortal.launcher.settings.SettingsPresentation

internal const val APPROVED_SETTINGS_CATEGORY = "approved_settings_category"
internal const val APPROVED_SETTINGS_PAGE = "approved_settings_page"
internal const val APPROVED_SETTINGS_CONTROL = "approved_settings_control"

internal data class ApprovedSettingsRoute(
    val page: String? = null,
    val category: String = "appearance",
    val control: String? = null,
) {
    val detailKey: String get() = "$category/${page ?: "overview"}"
}

/** Only the main Settings host handles scalar routes in its existing detail pane. */
internal val LocalApprovedSettingsNavigate =
    staticCompositionLocalOf<((ApprovedSettingsRoute) -> Unit)?> { null }
internal val LocalApprovedSettingsBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Only the heading consumes this changing state; retained editor bodies need not recompose. */
internal data class ApprovedSettingsHistoryControls(
    val onBack: (() -> Unit)?, val onForward: (() -> Unit)?,
    val backTitle: String? = null, val forwardTitle: String? = null,
)
internal val LocalApprovedSettingsHistoryControls = compositionLocalOf<ApprovedSettingsHistoryControls?> { null }

/** Cancel leaves a local editor; standalone wrappers still finish their own Activity. */
@Composable
internal fun rememberApprovedSettingsBack(): () -> Unit {
    val context = LocalContext.current
    val local = LocalApprovedSettingsBack.current
    return remember(context, local) { local ?: { (context as? Activity)?.finish(); Unit } }
}

internal fun approvedSettingsRoute(
    page: String,
    control: String? = null,
    category: String = approvedSettingsCategory(page),
) = if (page == "advanced") ApprovedSettingsRoute(category = "advanced", control = control)
    else ApprovedSettingsRoute(page, category, control)

@Composable
internal fun rememberApprovedSettingsNavigator(): (ApprovedSettingsRoute) -> Unit {
    val context = LocalContext.current
    val local = LocalApprovedSettingsNavigate.current
    return remember(context, local) {
        { route ->
            if (local != null && (route.page == null || approvedSettingsPage(route.page)?.activity == null)) {
                local(route)
            } else {
                context.startActivity(
                    route.page?.let { approvedSettingsIntent(context, it, route.control) }
                        ?: approvedCategoryIntent(context, route.category).apply {
                            route.control?.let { putExtra(APPROVED_SETTINGS_CONTROL, it) }
                        },
                )
            }
        }
    }
}

internal data class ApprovedSettingsCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: Int,
    val pages: List<String>,
)

/** Order and labels are the revision 04 contract, including the short Home list. */
internal val ApprovedSettingsCategories = listOf(
    ApprovedSettingsCategory("appearance", "Appearance", "Adjust your display and app appearance.",
        approvedIconResource("display"), listOf("display", "appearance", "wallpaper")),
    ApprovedSettingsCategory("photos", "Photos & screensaver", "Choose your photos and screensaver.",
        approvedIconResource("photo"), listOf("screensaver", "faces", "clock", "sources")),
    ApprovedSettingsCategory("home", "Home & widgets",
        "Set up your home layout and individual widgets.",
        approvedIconResource("gridapps"), listOf("widgets", "weather", "calendar", "alarm", "quickbar")),
    ApprovedSettingsCategory("sound", "Sound & wake", "Sounds, greetings and wake schedules.",
        approvedIconResource("bell"), listOf("touch", "chime", "welcome", "sunrise", "alarm", "lamp")),
    ApprovedSettingsCategory("connections", "Connections", "Connect your phone and home.",
        approvedIconResource("wifi"), listOf("ha", "phone", "intercom")),
    ApprovedSettingsCategory("access", "Input & accessibility", "Touch, buttons and accessibility.",
        approvedIconResource("hand"), listOf("touch", "system", "quickbar")),
    ApprovedSettingsCategory("device", "Device", "Manage your Portal.",
        approvedIconResource("info"), listOf("device", "sleep", "health", "help", "system")),
    ApprovedSettingsCategory("advanced", "Advanced", "Technical connections, automation and diagnostics.",
        approvedIconResource("tools"),
        listOf("almanac", "immich", "albums", "smb", "dav", "web", "multiroom", "mqtt", "boot",
            "alpaca", "options", "speed", "apk", "managed", "debug")),
)

internal data class ApprovedSettingsPage(
    val id: String,
    val title: String,
    val category: String,
    val activity: Class<*>? = null,
    val standaloneActivity: Class<*>? = activity,
)

/**
 * Existing Activity wrappers remain registered for direct/platform launches. A null activity
 * renders the same existing body in Settings; standaloneActivity identifies registry NavSpecs.
 */
internal val ApprovedSettingsPages = listOf(
    ApprovedSettingsPage("touch", "Touch sounds", "sound"),
    ApprovedSettingsPage("display", "Display", "appearance"),
    ApprovedSettingsPage("appearance", "App appearance", "appearance"),
    ApprovedSettingsPage("wallpaper", "Wallpaper", "appearance"),
    ApprovedSettingsPage("screensaver", "Slideshow", "photos", standaloneActivity = ScreensaverSettingsActivity::class.java),
    ApprovedSettingsPage("sources", "Photo sources", "photos", standaloneActivity = ScreensaverSourcesActivity::class.java),
    ApprovedSettingsPage("styles", "Screensaver style", "photos", standaloneActivity = ScreensaverModeActivity::class.java),
    ApprovedSettingsPage("faces", "Photo clock faces", "photos", standaloneActivity = FacePickerActivity::class.java),
    ApprovedSettingsPage("clock", "Digital clock", "photos", standaloneActivity = ClockSettingsActivity::class.java),
    ApprovedSettingsPage("dismiss", "Open when you tap to exit", "photos", standaloneActivity = ScreensaverDismissAppActivity::class.java),
    ApprovedSettingsPage("folder", "Choose photo folder", "photos", FolderPickerActivity::class.java),
    ApprovedSettingsPage("album", "Shared album link", "photos", AlbumUrlEntryActivity::class.java),
    ApprovedSettingsPage("immich", "Connect Immich", "photos", ImmichConnectActivity::class.java),
    // Album selection is the second stage of the existing atomic Immich connection Activity.
    ApprovedSettingsPage("albums", "Choose Immich album", "photos", ImmichConnectActivity::class.java),
    ApprovedSettingsPage("smb", "Connect SMB / NAS", "photos", SmbConnectActivity::class.java),
    ApprovedSettingsPage("dav", "Connect WebDAV", "photos", DavConnectActivity::class.java),
    ApprovedSettingsPage("web", "Web page", "photos", WebUrlEntryActivity::class.java),
    ApprovedSettingsPage("widgets", "Home dashboard", "home"),
    ApprovedSettingsPage("cameras", "Cameras", "home", CameraViewerActivity::class.java),
    ApprovedSettingsPage("weather", "Weather", "home"),
    ApprovedSettingsPage("calendar", "Calendar", "home"),
    ApprovedSettingsPage("almanac", "Almanac", "photos"),
    ApprovedSettingsPage("calendar-url", "Calendar feed", "home", CalendarUrlEntryActivity::class.java),
    ApprovedSettingsPage("world", "World clock", "home", standaloneActivity = WorldClockActivity::class.java),
    ApprovedSettingsPage("countdowns", "Countdowns", "home", standaloneActivity = CountdownSettingsActivity::class.java),
    ApprovedSettingsPage("quickbar", "Quick buttons", "home"),
    ApprovedSettingsPage("alarm", "Alarm", "sound", standaloneActivity = AlarmSettingsActivity::class.java),
    ApprovedSettingsPage("crypto", "Crypto", "home", standaloneActivity = CryptoSettingsActivity::class.java),
    ApprovedSettingsPage("todo", "To-do", "home", standaloneActivity = TodoSettingsActivity::class.java),
    ApprovedSettingsPage("news", "News", "home", standaloneActivity = NewsSettingsActivity::class.java),
    ApprovedSettingsPage("stocks", "Stocks", "home", standaloneActivity = StocksSettingsActivity::class.java),
    // Alpaca is an atomic form within the existing Stocks editor.
    ApprovedSettingsPage("alpaca", "Alpaca account", "home", standaloneActivity = StocksSettingsActivity::class.java),
    ApprovedSettingsPage("options", "Options scanner", "home", standaloneActivity = OptionsSettingsActivity::class.java),
    ApprovedSettingsPage("chime", "Sounds", "sound", standaloneActivity = ChimeSettingsActivity::class.java),
    ApprovedSettingsPage("welcome", "Welcome greeting", "sound", standaloneActivity = WelcomeSettingsActivity::class.java),
    ApprovedSettingsPage("sunrise", "Sunrise & wake", "sound", standaloneActivity = SunriseSettingsActivity::class.java),
    ApprovedSettingsPage("lamp", "Lamp", "sound", LampActivity::class.java),
    ApprovedSettingsPage("multiroom", "Multi-room audio", "connections", standaloneActivity = MultiRoomActivity::class.java),
    ApprovedSettingsPage("mqtt", "MQTT publishing", "connections", standaloneActivity = MqttActivity::class.java),
    ApprovedSettingsPage("ha", "Home Assistant dashboard", "connections", standaloneActivity = HaSettingsActivity::class.java),
    ApprovedSettingsPage("ha-entities", "Choose Home Assistant entities", "connections", HaSettingsActivity::class.java),
    ApprovedSettingsPage("ha-dashboard", "Home Assistant", "connections", HaDashboardActivity::class.java),
    ApprovedSettingsPage("phone", "Phone remote", "connections", RemotePairActivity::class.java),
    ApprovedSettingsPage("intercom", "Intercom", "connections", standaloneActivity = IntercomActivity::class.java),
    ApprovedSettingsPage("boot", "Start on boot", "device", standaloneActivity = BootAppsActivity::class.java),
    ApprovedSettingsPage("device", "Device name", "device"),
    ApprovedSettingsPage("sleep", "Sleep timer", "device", standaloneActivity = SleepSettingsActivity::class.java),
    ApprovedSettingsPage("health", "About & device health", "device", standaloneActivity = DeviceHealthActivity::class.java),
    ApprovedSettingsPage("apk", "Install an APK", "device", ApkBrowserActivity::class.java),
    ApprovedSettingsPage("help", "Welcome to ${Brand.NAME}", "device", HelpActivity::class.java),
    ApprovedSettingsPage("speed", "Speed test", "connections"),
    ApprovedSettingsPage("system", "Android & Portal settings", "device"),
    ApprovedSettingsPage("managed", "Automatically managed settings", "advanced"),
    ApprovedSettingsPage("debug", "Diagnostics", "advanced"),
)

internal fun approvedSettingsPage(id: String) = ApprovedSettingsPages.firstOrNull { it.id == id }

internal fun approvedSettingsCategory(page: String): String {
    if (SettingsPresentation.isAdvancedPage(page)) return "advanced"
    return approvedSettingsPage(page)?.category ?: "appearance"
}

internal fun approvedSettingsIntent(context: Context, page: String, control: String? = null): Intent {
    // The global flag lives in the shared sidebar and Advanced overview, not a scalar detail page.
    if (page == "advanced") return approvedCategoryIntent(context, "advanced")
        .apply { control?.let { putExtra(APPROVED_SETTINGS_CONTROL, it) } }
    val destination = approvedSettingsPage(page)
    return Intent(context, destination?.activity ?: ImmortalSettingsActivity::class.java)
        .putExtra(APPROVED_SETTINGS_PAGE, page)
        .putExtra(APPROVED_SETTINGS_CATEGORY, approvedSettingsCategory(page))
        .apply {
            if (destination?.activity == null) {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            control?.let { putExtra(APPROVED_SETTINGS_CONTROL, it) }
        }
}

internal fun approvedCategoryIntent(context: Context, category: String) =
    Intent(context, ImmortalSettingsActivity::class.java)
        .putExtra(APPROVED_SETTINGS_CATEGORY, category)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Direct launches from Home/widgets also select the correct sidebar category. */
internal fun approvedPageForHost(context: Context, title: String): String {
    (context as? Activity)?.intent?.getStringExtra(APPROVED_SETTINGS_PAGE)?.let { return it }
    val activity = context.javaClass
    ApprovedSettingsPages.firstOrNull { it.standaloneActivity == activity }?.let { return it.id }
    // The shared URL editor and Activity wrappers can have a context wrapper.
    return when (title.lowercase()) {
        "home assistant" -> "ha"
        "home assistant (mqtt)", "mqtt publishing" -> "mqtt"
        "photo source" -> "sources"
        "clock", "clock face" -> "clock"
        "multi-room audio" -> "multiroom"
        "device health" -> "health"
        "start on boot" -> "boot"
        else -> ApprovedSettingsPages.firstOrNull { it.title.equals(title, true) }?.id ?: "appearance"
    }
}
