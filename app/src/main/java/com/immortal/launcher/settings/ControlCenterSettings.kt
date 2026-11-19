/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import android.content.Context
import com.immortal.launcher.ControlCenter
import com.immortal.launcher.ControlCenterConfig
import com.immortal.launcher.ControlCenterConfig.Settings
import org.json.JSONObject

/**
 * Settings › Home Assistant › Control Center ([ControlCenterConfig]): which Portals show the app
 * (Full / Kids / Banners only), the kids lock, what asks first, banners and look. The Control
 * Center's own settings screen writes through [set], so it validates exactly like the phone
 * remote. Kids mode is enforced on the Portal only (HA has no per-entity permissions).
 */
internal object ControlCenterSettings {
  /** Control Center policies are the same in every room; [sharedRoom] is the one per-Portal toggle. */
  private fun bool(key: String, title: String, get: (Settings) -> Boolean, set: (Context, Boolean) -> Unit, help: String? = null,
                   scope: SettingScope) =
      BoolSpec(key, title, get, set, help, scope = scope)

  val domain: SettingsDomain<Settings> =
      SettingsDomain(
          id = "controlcenter",
          title = "Control Center",
          load = ControlCenterConfig::load,
          specs = listOf(
              EnumSpec("portalMode", "This Portal shows", get = { it.portalMode }, set = ControlCenterConfig::setPortalMode,
                  options = listOf(ControlCenterConfig.MODE_FULL to "Full", ControlCenterConfig.MODE_KIDS to "Kids",
                      ControlCenterConfig.MODE_BANNERS to "Banners only"),
                  coerce = { v -> v.takeIf { it in ControlCenterConfig.MODES } },
                  help = "Kids hides locks, garage, alarm, cameras and the water valve. Banners only keeps alerts but no app.",
                  scope = SettingScope.DEVICE),
              bool("kidsHideLocks", "Kids: hide door locks", { it.kidsHideLocks }, ControlCenterConfig::setKidsHideLocks, scope = SettingScope.HOUSEHOLD),
              bool("kidsHideGarage", "Kids: hide the garage door", { it.kidsHideGarage }, ControlCenterConfig::setKidsHideGarage, scope = SettingScope.HOUSEHOLD),
              bool("kidsHideSecurity", "Kids: hide security & alarm", { it.kidsHideSecurity }, ControlCenterConfig::setKidsHideSecurity, scope = SettingScope.HOUSEHOLD),
              bool("kidsHideCameras", "Kids: hide cameras", { it.kidsHideCameras }, ControlCenterConfig::setKidsHideCameras, scope = SettingScope.HOUSEHOLD),
              bool("kidsHideValve", "Kids: hide the water valve", { it.kidsHideValve }, ControlCenterConfig::setKidsHideValve, scope = SettingScope.HOUSEHOLD),
              bool("kidsLockThermostat", "Kids: lock the thermostats", { it.kidsLockThermostat }, ControlCenterConfig::setKidsLockThermostat, scope = SettingScope.HOUSEHOLD),
              bool("askUnlock", "Ask before unlocking", { it.askUnlock }, ControlCenterConfig::setAskUnlock,
                  "Locking never asks; it acts at once.", scope = SettingScope.HOUSEHOLD),
              bool("askGarage", "Ask before moving the garage", { it.askGarage }, ControlCenterConfig::setAskGarage,
                  "Shows the garage camera before it opens or closes.", scope = SettingScope.HOUSEHOLD),
              bool("askValve", "Ask before the water valve", { it.askValve }, ControlCenterConfig::setAskValve, scope = SettingScope.HOUSEHOLD),
              bool("askScenes", "Preview whole-house scenes once", { it.askScenes }, ControlCenterConfig::setAskScenes, scope = SettingScope.HOUSEHOLD),
              bool("bannersListen", "Alert banners on this Portal", { it.bannersListen }, ControlCenterConfig::setBannersListen,
                  "Keeps a small Home Assistant connection open for alerts. Critical alerts (leak, smoke, CO) always take over.", scope = SettingScope.HOUSEHOLD),
              bool("bannersWarnings", "Warnings: doors, windows, garage left open", { it.bannersWarnings }, ControlCenterConfig::setBannersWarnings, scope = SettingScope.HOUSEHOLD),
              bool("sharedRoom", "Shared room", { it.sharedRoom }, ControlCenterConfig::setSharedRoom,
                  "Doors, windows and the garage left open banner only on shared-room Portals, at most once an hour.",
                  scope = SettingScope.DEVICE),
              IntSpec("warningAfterMin", "Warn after", get = { it.warningAfterMin }, set = ControlCenterConfig::setWarningAfterMin,
                  min = ControlCenterConfig.MIN_WARN_MIN, max = ControlCenterConfig.MAX_WARN_MIN, format = { "$it min" },
                  scope = SettingScope.HOUSEHOLD),
              bool("bannersInfo", "Info: washer, dryer, dishwasher done", { it.bannersInfo }, ControlCenterConfig::setBannersInfo,
                  "Never during quiet hours.", scope = SettingScope.HOUSEHOLD),
              bool("criticalSoundAtNight", "Leak, smoke and CO play a sound at night", { it.criticalSoundAtNight },
                  ControlCenterConfig::setCriticalSoundAtNight,
                  "Every other alert is silent at night. Off: these three still take over the screen, silently.", scope = SettingScope.HOUSEHOLD),
              bool("reducedMotion", "Reduced motion", { it.reducedMotion }, ControlCenterConfig::setReducedMotion,
                  "Stops glows pulsing and the scene walk animation.", scope = SettingScope.HOUSEHOLD),
              bool("darkTheme", "Dark plan", { it.darkTheme }, ControlCenterConfig::setDarkTheme, scope = SettingScope.HOUSEHOLD),
              IntSpec("pinLength", "Alarm PIN digits", get = { it.pinLength }, set = ControlCenterConfig::setPinLength,
                  min = ControlCenterConfig.MIN_PIN, max = ControlCenterConfig.MAX_PIN,
                  scope = SettingScope.HOUSEHOLD),
              IntSpec("exitDelaySec", "Exit delay shown when arming", get = { it.exitDelaySec }, set = ControlCenterConfig::setExitDelaySec,
                  min = ControlCenterConfig.MIN_EXIT_S, max = ControlCenterConfig.MAX_EXIT_S, step = 5, format = { "$it s" },
                  help = "Match your alarm panel's exit delay in Home Assistant.",
                  scope = SettingScope.HOUSEHOLD),
          ),
          defaults = { Settings() },
          onApplied = { context, _ -> ControlCenter.reloadSettings(context) },
      )

  /** Writes one setting through the registry (validated, then the runtime reloads). */
  fun set(context: Context, key: String, value: Any): Boolean =
      domain.apply(context, JSONObject().put(key, value)).isNotEmpty()
}
