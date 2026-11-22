/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.jarvis.wake.HearthpaneWake
import com.immortal.launcher.jarvis.wake.JarvisWakeConfig
import com.immortal.launcher.memory.JarvisKnowsActivity

/** Registry domain for Settings › Jarvis ([JarvisWakeConfig]); renders on-device and on the phone remote. */
internal object JarvisSettings {
  /** The nav row to "What Jarvis knows"; it stores nothing itself. */
  const val KNOWS_KEY = "whatJarvisKnows"

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
                  BoolSpec(
                      "learnFromConversations",
                      "Jarvis learns from conversations",
                      get = { it.learnFromConversations },
                      set = JarvisWakeConfig::setLearnFromConversations,
                      help = "Jarvis picks up the teams, stocks and news you talk about, with nothing to set up, " +
                          "and uses them in later answers. It stays sealed on this Portal. Guests and children " +
                          "are never learned.",
                      scope = SettingScope.HOUSEHOLD),
                  NavSpec(
                      KNOWS_KEY,
                      "What Jarvis knows",
                      value = { _, s -> if (s.learnFromConversations) "" else "Off" },
                      activity = JarvisKnowsActivity::class.java,
                      help = "See, keep or forget what Jarvis learned.",
                      scope = SettingScope.HOUSEHOLD),
              ),
          defaults = { JarvisWakeConfig.Settings() },
          onApplied = { context, _ -> HearthpaneWake.refresh(context) },
      )
}
