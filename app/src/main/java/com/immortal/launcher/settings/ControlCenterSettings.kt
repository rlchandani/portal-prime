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
  private fun bool(key: String, title: String, get: (Settings) -> Boolean, set: (Context, Boolean) -> Unit, help: String? = null) =
      BoolSpec(key, title, get, set, help)

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
                  help = "Kids hides locks, garage, alarm, cameras and the water valve. Banners only keeps alerts but no app."),
              bool("kidsHideLocks", "Kids: hide door locks", { it.kidsHideLocks }, ControlCenterConfig::setKidsHideLocks),
              bool("kidsHideGarage", "Kids: hide the garage door", { it.kidsHideGarage }, ControlCenterConfig::setKidsHideGarage),
              bool("kidsHideSecurity", "Kids: hide security & alarm", { it.kidsHideSecurity }, ControlCenterConfig::setKidsHideSecurity),
              bool("kidsHideCameras", "Kids: hide cameras", { it.kidsHideCameras }, ControlCenterConfig::setKidsHideCameras),
              bool("kidsHideValve", "Kids: hide the water valve", { it.kidsHideValve }, ControlCenterConfig::setKidsHideValve),
              bool("kidsLockThermostat", "Kids: lock the thermostats", { it.kidsLockThermostat }, ControlCenterConfig::setKidsLockThermostat),
              bool("askUnlock", "Ask before unlocking", { it.askUnlock }, ControlCenterConfig::setAskUnlock,
                  "Locking never asks; it acts at once."),
              bool("askGarage", "Ask before moving the garage", { it.askGarage }, ControlCenterConfig::setAskGarage,
                  "Shows the garage camera before it opens or closes."),
              bool("askValve", "Ask before the water valve", { it.askValve }, ControlCenterConfig::setAskValve),
              bool("askScenes", "Preview whole-house scenes once", { it.askScenes }, ControlCenterConfig::setAskScenes),
              bool("bannersListen", "Alert banners on this Portal", { it.bannersListen }, ControlCenterConfig::setBannersListen,
                  "Keeps a small Home Assistant connection open for alerts. Critical alerts (leak, smoke, CO) always take over."),
              bool("bannersWarnings", "Warnings: doors, windows, garage left open", { it.bannersWarnings }, ControlCenterConfig::setBannersWarnings),
              IntSpec("warningAfterMin", "Warn after", get = { it.warningAfterMin }, set = ControlCenterConfig::setWarningAfterMin,
                  min = ControlCenterConfig.MIN_WARN_MIN, max = ControlCenterConfig.MAX_WARN_MIN, format = { "$it min" }),
              bool("bannersInfo", "Info: washer, dryer, dishwasher done", { it.bannersInfo }, ControlCenterConfig::setBannersInfo,
                  "Never during quiet hours."),
              bool("criticalSoundAtNight", "Leak, smoke and CO alarms sound in quiet hours", { it.criticalSoundAtNight },
                  ControlCenterConfig::setCriticalSoundAtNight,
                  "Every other alert is silent at night. Off: these three still take over the screen, silently."),
              bool("reducedMotion", "Reduced motion", { it.reducedMotion }, ControlCenterConfig::setReducedMotion,
                  "Stops glows pulsing and the scene walk animation."),
              bool("darkTheme", "Dark plan", { it.darkTheme }, ControlCenterConfig::setDarkTheme),
              IntSpec("pinLength", "Alarm PIN digits", get = { it.pinLength }, set = ControlCenterConfig::setPinLength,
                  min = ControlCenterConfig.MIN_PIN, max = ControlCenterConfig.MAX_PIN),
              IntSpec("exitDelaySec", "Exit delay shown when arming", get = { it.exitDelaySec }, set = ControlCenterConfig::setExitDelaySec,
                  min = ControlCenterConfig.MIN_EXIT_S, max = ControlCenterConfig.MAX_EXIT_S, step = 5, format = { "$it s" },
                  help = "Match your alarm panel's exit delay in Home Assistant."),
          ),
          defaults = { Settings() },
          onApplied = { context, _ -> ControlCenter.reloadSettings(context) },
      )

  /** Writes one setting through the registry (validated, then the runtime reloads). */
  fun set(context: Context, key: String, value: Any): Boolean =
      domain.apply(context, JSONObject().put(key, value)).isNotEmpty()
}
