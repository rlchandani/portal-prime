/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.FollowMeConfig
import com.immortal.launcher.FollowMeMode

/** Registry domain for "Music follows you" ([FollowMeConfig]); shown in Settings › Music and on the phone remote. */
internal object FollowMeSettings {
  val modeOptions: List<Pair<String, String>> = listOf(
      FollowMeMode.ASK.wire to "Ask first",
      FollowMeMode.AUTO.wire to "Automatic",
      FollowMeMode.OFF.wire to "Off",
  )

  val domain: SettingsDomain<FollowMeConfig.Settings> =
      SettingsDomain(
          id = "followme",
          title = "Music follows you",
          load = FollowMeConfig::load,
          specs =
              listOf(
                  EnumSpec(
                      key = "mode",
                      title = "Music follows you",
                      get = { it.mode },
                      set = FollowMeConfig::setMode,
                      options = modeOptions,
                      coerce = { v -> v.takeIf { raw -> modeOptions.any { it.first == raw } } },
                      help = "When you walk in from a room playing music, this Portal can offer to " +
                          "continue it here (Ask first) or bring it over on its own (Automatic)."),
              ),
          defaults = { FollowMeConfig.Settings() },
      )
}
