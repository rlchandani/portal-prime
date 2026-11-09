/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.MusicNightCapMath
import com.immortal.launcher.MusicNightConfig

/** Settings › Music ([MusicNightConfig]); registered in [SettingsDomains.all] (on-device + phone remote). */
internal object MusicSettings {
  val domain: SettingsDomain<MusicNightConfig.Settings> =
      SettingsDomain(
          id = "music",
          title = "Music",
          load = MusicNightConfig::load,
          specs =
              listOf(
                  BoolSpec(
                      "quietCap",
                      "Quieter music during quiet hours",
                      get = { it.quietCap },
                      set = MusicNightConfig::setQuietCap,
                      help = "Music can't go above the level below while quiet hours are on. " +
                          "Announcements aren't affected."),
                  IntSpec(
                      "quietMaxLevel",
                      "Loudest music during quiet hours",
                      get = { it.quietMaxLevel },
                      set = MusicNightConfig::setQuietMaxLevel,
                      min = MusicNightCapMath.MIN_LEVEL,
                      max = MusicNightCapMath.SCALE,
                      format = { "$it of ${MusicNightCapMath.SCALE}" },
                      visible = { _, s -> s.quietCap }),
              ),
          defaults = { MusicNightConfig.Settings() },
      )
}
