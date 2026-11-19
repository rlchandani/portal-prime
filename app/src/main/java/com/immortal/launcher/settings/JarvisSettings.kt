/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.jarvis.wake.HearthpaneWake
import com.immortal.launcher.jarvis.wake.JarvisWakeConfig

/** Registry domain for Settings › Jarvis ([JarvisWakeConfig]); renders on-device and on the phone remote. */
internal object JarvisSettings {
  val domain: SettingsDomain<JarvisWakeConfig.Settings> =
      SettingsDomain(
          id = "jarvis",
          title = "Jarvis",
          load = JarvisWakeConfig::load,
          specs =
              listOf(
                  BoolSpec(
                      "wakeOnScreens",
                      "Hey Jarvis on Hearthpane screens",
                      get = { it.wakeOnScreens },
                      set = JarvisWakeConfig::setWakeOnScreens,
                      help = "Say “Hey Jarvis” while Home, Control Center, Music or Settings is on screen. " +
                          "Listening happens on this Portal and nothing is recorded. It stops during calls, " +
                          "while the camera is in use, on the photo frame and in quiet hours.",
                      scope = SettingScope.HOUSEHOLD),
              ),
          defaults = { JarvisWakeConfig.Settings() },
          onApplied = { context, _ -> HearthpaneWake.refresh(context) },
      )
}
