/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.LocalAdvancedSettings
import org.json.JSONObject

/** The existing editor behind each block, unchanged; only its place in the tree moved. */
@Composable
internal fun SettingsBlockContent(block: SettingsBlock) {
    val context = LocalContext.current
    val back = rememberApprovedSettingsBack()
    Column(Modifier.fillMaxWidth()) {
        when (block) {
            SettingsBlock.DISPLAY -> DisplaySection(setOf(DisplayPart.BRIGHTNESS, DisplayPart.NIGHT_SHIFT))
            SettingsBlock.MEDIA_VOLUME -> DisplaySection(setOf(DisplayPart.VOLUME))
            SettingsBlock.TOUCH_SOUNDS -> ApprovedTouchSounds()
            SettingsBlock.CHIMES -> ChimeSettingsContent()
            SettingsBlock.ALARM -> AlarmContent()
            SettingsBlock.SUNRISE -> SunriseContent()
            SettingsBlock.SLEEP_TIMER -> SleepSettingsContent()
            SettingsBlock.SCREENSAVER_PREVIEW -> PrimaryPillButton("Preview full screen") {
                context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
            }
            SettingsBlock.WALLPAPER -> WallpaperSection()
            SettingsBlock.QUICK_BUTTONS -> QuickButtonsSection()
            SettingsBlock.ALMANAC -> AlmanacSettingsContent()
            SettingsBlock.WORLD_CLOCK -> WorldClockContent()
            SettingsBlock.COUNTDOWNS -> CountdownSettingsContent()
            SettingsBlock.TODO -> TodoSettingsContent()
            SettingsBlock.NEWS -> NewsSettingsContent()
            SettingsBlock.STOCKS -> StocksSettingsContent()
            SettingsBlock.ALPACA -> ApprovedEditorsAlpacaForm(onCancel = back)
            SettingsBlock.CRYPTO -> CryptoSettingsContent()
            SettingsBlock.GUEST -> GuestSettingsContent()
            SettingsBlock.OPTIONS -> OptionsSettingsContent()
            SettingsBlock.PHOTO_CLOCK_FACE -> FacePickerContent()
            SettingsBlock.DIGITAL_CLOCK -> ClockSettingsContent()
            SettingsBlock.SOURCES -> ScreensaverSourcesContent(onDone = back)
            SettingsBlock.DISMISS -> ScreensaverDismissContent()
            SettingsBlock.WELCOME -> WelcomeSettingsContent()
            SettingsBlock.HOME_ASSISTANT -> HaSettingsContent()
            SettingsBlock.MQTT -> MqttContent()
            SettingsBlock.PHONE_PAIRING -> PhonePairingContent()
            SettingsBlock.INTERCOM -> IntercomContent()
            SettingsBlock.MUSIC -> MusicSettingsContent()
            SettingsBlock.MULTIROOM -> MultiRoomContent()
            SettingsBlock.DEVICE_NAME -> DeviceNameSection()
            SettingsBlock.ABOUT_HEALTH -> DeviceHealthContent()
            SettingsBlock.ADVANCED_SWITCH -> AdvancedSettingsSwitch()
            SettingsBlock.DEBUG_MODE -> Card { IosDebugRow(context) }
            SettingsBlock.BOOT_APPS -> BootAppsContent()
            SettingsBlock.MANAGED -> ApprovedManagedSettingsPage()
        }
    }
}

/** The global Advanced flag, formerly in the sidebar footer. Writes through the registry. */
@Composable
private fun AdvancedSettingsSwitch() {
    val context = LocalContext.current
    val advanced = LocalAdvancedSettings.current
    var failed by remember { mutableStateOf(false) }
    Card {
        ToggleRow("Advanced settings", advanced) { on ->
            failed = "advancedSettings" !in SettingsDomains.immortal.apply(context,
                JSONObject().put("advancedSettings", on))
        }
    }
    FooterNote(if (failed) "Advanced settings could not be changed. Your settings are unchanged."
        else "Shows technical options inside each page, marked ADV.")
}

private val AndroidSettingsActions = mapOf(
    SettingsExternal.WIFI to Settings.ACTION_WIFI_SETTINGS,
    SettingsExternal.BLUETOOTH to Settings.ACTION_BLUETOOTH_SETTINGS,
    SettingsExternal.DATE_TIME to Settings.ACTION_DATE_SETTINGS,
    SettingsExternal.LANGUAGE to Settings.ACTION_LOCALE_SETTINGS,
    SettingsExternal.ACCESSIBILITY to Settings.ACTION_ACCESSIBILITY_SETTINGS,
    SettingsExternal.STORAGE to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
    SettingsExternal.APP_PERMISSIONS to Settings.ACTION_APPLICATION_SETTINGS,
    SettingsExternal.ALL_ANDROID to Settings.ACTION_SETTINGS,
)

/** Leaves the in-pane tree: an existing Activity, a Home tab, or the system's own settings. */
internal fun launchSettingsExternal(context: Context, item: SettingsExternalItem) {
    fun start(activity: Class<*>) = context.startActivity(Intent(context, activity))
    when (item.target) {
        SettingsExternal.CUSTOMIZE_HOME -> start(HomeActivity::class.java)
        SettingsExternal.LAMP -> start(LampActivity::class.java)
        SettingsExternal.HELP_TOUR -> start(HelpActivity::class.java)
        SettingsExternal.INSTALL_APK -> start(ApkBrowserActivity::class.java)
        SettingsExternal.SPEED_TEST -> context.openApprovedHomeTab(ApprovedLauncherTab.TOOLS)
        SettingsExternal.PORTAL -> AndroidSettingsNavigation.openPortal(context)
        else -> AndroidSettingsNavigation.open(context, item.title, AndroidSettingsActions.getValue(item.target))
    }
}
