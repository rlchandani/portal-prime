/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.DoorbellConfig
import com.immortal.launcher.DoorbellEntities
import com.immortal.launcher.DoorbellListener
import com.immortal.launcher.DoorbellPickerActivity

/**
 * Settings › Home Assistant › Doorbell ([DoorbellConfig]). The three entity rows open the
 * [DoorbellPickerActivity] on the Portal (a list from Home Assistant) and are plain validated
 * text on the phone remote. Quiet hours stay in the `chime` domain. Any change restarts the
 * Home Assistant listener so it watches the new entities.
 */
internal object DoorbellSettings {
  private val PICKER = Entry.Nav(DoorbellPickerActivity::class.java)

  val domain: SettingsDomain<DoorbellConfig.Settings> =
      SettingsDomain(
          id = "doorbell",
          title = "Doorbell",
          load = DoorbellConfig::load,
          specs =
              listOf(
                  StringSpec(
                      "trigger",
                      "Doorbell button",
                      get = { it.trigger },
                      set = DoorbellConfig::setTrigger,
                      entry = PICKER,
                      applyWhen = DoorbellEntities::validTriggerSetting,
                      help = "The Home Assistant event.* or binary_sensor.* that fires when someone presses the doorbell."),
                  StringSpec(
                      "camera",
                      "Door camera",
                      get = { it.camera },
                      set = DoorbellConfig::setCamera,
                      entry = PICKER,
                      applyWhen = DoorbellEntities::validCameraSetting,
                      help = "The camera.* shown live on the doorbell card."),
                  StringSpec(
                      "motion",
                      "Motion sensors",
                      get = { it.motion },
                      set = DoorbellConfig::setMotion,
                      entry = PICKER,
                      applyWhen = DoorbellEntities::validMotionSetting,
                      help = "Optional binary_sensor.* motion sensors, separated by commas. Motion shows the camera too."),
                  BoolSpec(
                      "chime",
                      "Doorbell chime",
                      get = { it.chime },
                      set = DoorbellConfig::setChime,
                      help = "During quiet hours the card shows without sound."),
                  IntSpec(
                      "cooldownSec",
                      "Wait between alerts",
                      get = { it.cooldownSec },
                      set = DoorbellConfig::setCooldown,
                      min = DoorbellConfig.MIN_COOLDOWN_SEC,
                      max = DoorbellConfig.MAX_COOLDOWN_SEC,
                      step = DoorbellConfig.COOLDOWN_STEP_SEC,
                      format = { "$it s" },
                      help = "Per camera. A doorbell press after motion still shows."),
                  BoolSpec(
                      "listen",
                      "Watch for the doorbell here",
                      get = { it.listen },
                      set = DoorbellConfig::setListen,
                      help = "Keeps a Home Assistant connection open on this Portal. One Portal is enough; " +
                          "every Portal shows the doorbell either way."),
              ),
          defaults = { DoorbellConfig.Settings() },
          onApplied = { context, _ -> DoorbellListener.restart(context) },
      )
}
