/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.settings

import com.immortal.launcher.BriefingConfig
import com.immortal.launcher.BriefingTrigger

/**
 * Settings › Alarms & Wake › Morning briefing ([BriefingConfig]); registered in
 * [SettingsDomains.all] so it renders on-device and on the phone remote.
 */
internal object BriefingSettings {
  val domain: SettingsDomain<BriefingConfig.Settings> =
      SettingsDomain(
          id = "briefing",
          title = "Morning briefing",
          load = BriefingConfig::load,
          specs =
              listOf(
                  BoolSpec(
                      "enabled",
                      "Morning briefing",
                      get = { it.enabled },
                      set = BriefingConfig::setEnabled,
                      help = "Weather, today's calendar, the shared list, timers and the first routine. " +
                          "A Good morning card shows on Home from 6 to 11.",
                          scope = SettingScope.HOUSEHOLD),
                  EnumSpec(
                      "trigger",
                      "When it opens",
                      get = { it.trigger },
                      set = BriefingConfig::setTrigger,
                      options = BriefingTrigger.entries.map { it.wire to it.label },
                      coerce = { raw -> BriefingTrigger.fromWire(raw)?.wire },
                      help = "At alarm time, it opens when you turn off the alarm or the sunrise light.",
                      visible = { _, s -> s.enabled },
                      scope = SettingScope.HOUSEHOLD),
                  BoolSpec(
                      "speak",
                      "Read it aloud",
                      get = { it.speak },
                      set = BriefingConfig::setSpeak,
                      help = "Uses the chime voice. Never during quiet hours.",
                      visible = { _, s -> s.enabled },
                      scope = SettingScope.HOUSEHOLD),
              ),
          defaults = { BriefingConfig.Settings() },
      )
}
