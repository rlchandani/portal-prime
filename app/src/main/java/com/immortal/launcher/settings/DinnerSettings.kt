/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.DinnerConfig

/**
 * PROPOSED registry domain for Call everyone ([DinnerConfig]). Integrator: add [domain] to
 * [SettingsDomains.all] so the toggles render on-device and on the phone remote. Quiet hours and
 * volume stay in the `chime` domain (the prototype's "Shared with the hourly chime").
 */
internal object DinnerSettings {
  val domain: SettingsDomain<DinnerConfig.Settings> =
      SettingsDomain(
          id = "dinner",
          title = "Call everyone",
          load = DinnerConfig::load,
          specs =
              listOf(
                  BoolSpec(
                      "receive",
                      "Let other rooms call this Portal",
                      get = { it.receive },
                      set = DinnerConfig::setReceive,
                      help = "Messages show here full width for 30 seconds."),
                  BoolSpec(
                      "speak",
                      "Say the message out loud",
                      get = { it.speak },
                      set = DinnerConfig::setSpeak,
                      help = "After the chime, the message is spoken once."),
              ),
          defaults = { DinnerConfig.Settings() },
      )
}
