/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.BoolSpec
import com.immortal.launcher.settings.SettingsDomain

/**
 * Proposed "share" settings domain. INTEGRATOR: add [domain] to `SettingsDomains.all` so it
 * renders on-device and on the phone remote (ShareConfig is classified as registered in
 * SettingsDomainTest on that basis).
 */
internal object ShareSettings {
  val domain: SettingsDomain<ShareConfig.Settings> =
      SettingsDomain(
          id = "share",
          title = "Sharing from phones",
          load = ShareConfig::load,
          specs = listOf(
              BoolSpec("allowSharing", "Allow sharing from phones", get = { it.allowSharing },
                  set = ShareConfig::setAllowSharing,
                  help = "Paired phones can send photos, notes and links to this Portal."),
              BoolSpec("clearNotesOvernight", "Clear notes overnight", get = { it.clearNotesOvernight },
                  set = ShareConfig::setClearNotesOvernight,
                  help = "Notes leave Home at 4:00 AM. They stay under Tools → Shared from phones."),
              BoolSpec("freshPhotoBadge", "Show “New from” on new photos", get = { it.freshPhotoBadge },
                  set = ShareConfig::setFreshPhotoBadge,
                  help = "A shared photo's first turn says who sent it and when."),
              BoolSpec("noteChime", "Chime when a note arrives", get = { it.noteChime },
                  set = ShareConfig::setNoteChime,
                  help = "One soft chime. Never during quiet hours."),
          ),
          defaults = { ShareConfig.Settings() },
      )
}
