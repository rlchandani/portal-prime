/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import com.immortal.launcher.settings.SettingsRegistry
import org.json.JSONObject

/** Native calendar form, preserving [CalendarFeed] providers and webcal links. */
class CalendarUrlEntryActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        UrlEntryScreen(
            title = "Calendar feed",
            fieldLabel = "Calendar feed",
            placeholder = "https://…",
            initial = ScreensaverConfig.load(this).calendarUrl.orEmpty(),
            emptyHint = "Google secret address in iCal format or Apple iCloud public calendar.",
            invalidHint = "Enter a complete calendar link, including the website name.",
            primaryLabel = "Use this calendar",
            validate = { if (CalendarFeed.isSupported(it)) CalendarFeed.providerName(it) else null },
            onSave = { url ->
              SettingsRegistry.apply(this, "calendar", JSONObject().put("url", url))
              approvedSourcesReturn(this, "calendar")
            },
            onCancel = { finish() },
            removeLabel = "Remove calendar",
            removeHelp = "Events from this feed will disappear from Home and the screensaver. " +
                "Your original calendar won't be deleted.",
            onRemove = {
              SettingsRegistry.apply(this, "calendar", JSONObject().put("url", ""))
              approvedSourcesReturn(this, "calendar")
            },
            fieldHelp = "One feed supplies Home and the screensaver. Use a public iCalendar (.ics) link, " +
                "a Google secret iCal address or an Apple iCloud public-calendar link.",
        )
      }
    }
  }
}
