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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

internal const val APPROVED_SETTINGS_CATEGORY = "approved_settings_category"
internal const val APPROVED_SETTINGS_PAGE = "approved_settings_page"
internal const val APPROVED_SETTINGS_CONTROL = "approved_settings_control"

/** A request to show a Settings page (old or new id) and optionally highlight one of its rows. */
internal data class ApprovedSettingsRoute(
    val page: String? = null,
    val category: String? = null,
    val control: String? = null,
)

internal fun approvedSettingsRoute(page: String, control: String? = null) =
    ApprovedSettingsRoute(page = page, control = control)

/** Provided by the Settings host: opens a page in its right column (a plain state update). */
internal val LocalApprovedSettingsNavigate =
    staticCompositionLocalOf<((ApprovedSettingsRoute) -> Unit)?> { null }
internal val LocalApprovedSettingsBack = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Cancel leaves a local editor; standalone wrappers still finish their own Activity. */
@Composable
internal fun rememberApprovedSettingsBack(): () -> Unit {
    val context = LocalContext.current
    val local = LocalApprovedSettingsBack.current
    return remember(context, local) { local ?: { (context as? Activity)?.finish(); Unit } }
}

/** In the Settings host, pages open in place; elsewhere, and for full-screen editors, an Activity starts. */
@Composable
internal fun rememberApprovedSettingsNavigator(): (ApprovedSettingsRoute) -> Unit {
    val context = LocalContext.current
    val local = LocalApprovedSettingsNavigate.current
    return remember(context, local) {
        { route ->
            when (SettingsLegacyIds.resolve(route.page, route.category, route.control)) {
                is SettingsDestination.Page -> if (local != null) local(route)
                    else context.startActivity(approvedSettingsIntent(context, route))
                is SettingsDestination.Activity, null -> context.startActivity(approvedSettingsIntent(context, route))
            }
        }
    }
}

/** Full-screen editors that keep their own Activity, by their (unchanged) page id. */
internal val SettingsActivityDestinations: Map<String, Class<*>> = mapOf(
    "folder" to FolderPickerActivity::class.java,
    "album" to AlbumUrlEntryActivity::class.java,
    "immich" to ImmichConnectActivity::class.java,
    // Album selection is the second stage of the existing atomic Immich connection Activity.
    "albums" to ImmichConnectActivity::class.java,
    "smb" to SmbConnectActivity::class.java,
    "dav" to DavConnectActivity::class.java,
    "web" to WebUrlEntryActivity::class.java,
    "cameras" to CameraViewerActivity::class.java,
    "calendar-url" to CalendarUrlEntryActivity::class.java,
    "lamp" to LampActivity::class.java,
    "ha-entities" to HaSettingsActivity::class.java,
    "ha-dashboard" to HaDashboardActivity::class.java,
    "apk" to ApkBrowserActivity::class.java,
    "help" to HelpActivity::class.java,
)

/**
 * Standalone wrapper Activities and the page that now shows the same body in the split view.
 * Registry NavSpecs that name one of these open the page in place.
 */
internal val SettingsStandalonePages: Map<Class<*>, String> = mapOf(
    ScreensaverSettingsActivity::class.java to SettingsPageIds.FRAME,
    ScreensaverModeActivity::class.java to SettingsPageIds.FRAME,
    ScreensaverSourcesActivity::class.java to SettingsPageIds.SOURCES,
    FacePickerActivity::class.java to SettingsPageIds.CLOCK,
    ClockSettingsActivity::class.java to SettingsPageIds.CLOCK,
    ScreensaverDismissAppActivity::class.java to SettingsPageIds.DISMISS,
    WorldClockActivity::class.java to SettingsPageIds.WORLD_CLOCK,
    CountdownSettingsActivity::class.java to SettingsPageIds.COUNTDOWNS,
    GuestSettingsActivity::class.java to SettingsPageIds.GUEST,
    AlarmSettingsActivity::class.java to SettingsPageIds.ALARMS,
    CryptoSettingsActivity::class.java to SettingsPageIds.CRYPTO,
    TodoSettingsActivity::class.java to SettingsPageIds.TODO,
    NewsSettingsActivity::class.java to SettingsPageIds.NEWS,
    StocksSettingsActivity::class.java to SettingsPageIds.STOCKS,
    OptionsSettingsActivity::class.java to SettingsPageIds.OPTIONS,
    ChimeSettingsActivity::class.java to SettingsPageIds.SOUNDS,
    WelcomeSettingsActivity::class.java to SettingsPageIds.GREETING,
    SunriseSettingsActivity::class.java to SettingsPageIds.ALARMS,
    MultiRoomActivity::class.java to SettingsPageIds.MUSIC,
    MqttActivity::class.java to SettingsPageIds.MQTT,
    IntercomActivity::class.java to SettingsPageIds.INTERCOM,
    BootAppsActivity::class.java to SettingsPageIds.BOOT,
    SleepSettingsActivity::class.java to SettingsPageIds.NIGHT,
    DeviceHealthActivity::class.java to SettingsPageIds.ABOUT,
    RemotePairActivity::class.java to SettingsPageIds.PHONE,
)

internal fun approvedSettingsIntent(context: Context, page: String, control: String? = null): Intent =
    approvedSettingsIntent(context, ApprovedSettingsRoute(page = page, control = control))

/** Old and new page ids open the split view on their new page; full-screen editors keep their Activity. */
internal fun approvedSettingsIntent(context: Context, route: ApprovedSettingsRoute): Intent =
    when (val destination = SettingsLegacyIds.resolve(route.page, route.category, route.control)) {
        is SettingsDestination.Activity ->
            Intent(context, SettingsActivityDestinations.getValue(destination.id))
                .putExtra(APPROVED_SETTINGS_PAGE, destination.id)
        is SettingsDestination.Page -> settingsPageIntent(context, destination.page, destination.control)
        null -> Intent(context, ImmortalSettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

internal fun settingsPageIntent(context: Context, page: String, control: String? = null): Intent =
    Intent(context, ImmortalSettingsActivity::class.java)
        .putExtra(APPROVED_SETTINGS_PAGE, page)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .apply { control?.let { putExtra(APPROVED_SETTINGS_CONTROL, it) } }

/** The split-view page a standalone wrapper stands for, so its sidebar highlights the right row. */
internal fun approvedPageForHost(context: Context): String {
    val activity = context as? Activity
    when (val requested = SettingsLegacyIds.resolve(activity?.intent?.getStringExtra(APPROVED_SETTINGS_PAGE))) {
        is SettingsDestination.Page -> return requested.page
        is SettingsDestination.Activity -> SettingsLegacyIds.activityHomes[requested.id]?.let { return it }
        null -> Unit
    }
    val type = activity?.javaClass ?: return SettingsPageIds.DEFAULT
    SettingsStandalonePages[type]?.let { return it }
    val editor = SettingsActivityDestinations.entries.firstOrNull { it.value == type }?.key
    return editor?.let(SettingsLegacyIds.activityHomes::get) ?: SettingsPageIds.DEFAULT
}

/** Registry NavSpecs name an Activity; split-view pages open in place, others launch. */
internal fun settingsPageForActivity(activity: Class<*>): String? = SettingsStandalonePages[activity]
