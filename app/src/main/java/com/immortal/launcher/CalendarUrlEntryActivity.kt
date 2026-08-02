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

/** Paste-a-link screen for the calendar widget's ICS feed — a [UrlEntryScreen] configuration. */
class CalendarUrlEntryActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        UrlEntryScreen(
            title = "Calendar Link",
            subtitle =
                "Paste a private iCalendar (.ics) link from Google Calendar or Apple iCloud. " +
                    "Immortal reads it directly — it can't sign in to your account.",
            placeholder = "https://calendar.google.com/calendar/ical/…/basic.ics",
            initial = ScreensaverConfig.load(this).calendarUrl.orEmpty(),
            emptyHint = "Examples: Google \"secret address in iCal format\", Apple iCloud public calendar.",
            invalidHint = "That doesn't look like a calendar (.ics) link yet.",
            primaryLabel = "Use this calendar",
            validate = { if (CalendarFeed.isSupported(it)) CalendarFeed.providerName(it) else null },
            onSave = { url ->
              ScreensaverConfig.setCalendarUrl(this, url)
              finish()
            },
            onCancel = { finish() },
            removeLabel = "Remove calendar",
            onRemove = {
              ScreensaverConfig.clearCalendarUrl(this)
              finish()
            },
            helpTitle = "How to get a link:",
            helpBody =
                "• Google Calendar (on a computer) → Settings → your calendar → " +
                    "\"Integrate calendar\" → copy the \"Secret address in iCal format\". " +
                    "Keep it private — anyone with it can read the calendar.\n" +
                    "• Apple iCloud → Calendar app → tap a calendar → turn on \"Public Calendar\" " +
                    "→ Copy Link (a webcal:// link works too).",
        )
      }
    }
  }
}
