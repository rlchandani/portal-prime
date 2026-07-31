/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/** Paste-a-link screen for the calendar widget's ICS feed. Validates the link up-front. */
class CalendarUrlEntryActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        CalendarUrlEntryScreen(
            onSave = { url ->
              ScreensaverConfig.setCalendarUrl(this, url)
              finish()
            },
            onRemove = {
              ScreensaverConfig.clearCalendarUrl(this)
              finish()
            },
            onCancel = { finish() },
        )
      }
    }
  }
}

@Composable
private fun CalendarUrlEntryScreen(
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
  val context = LocalContext.current
  val existing = remember { ScreensaverConfig.load(context).calendarUrl.orEmpty() }
  var url by remember { mutableStateOf(existing) }
  val (_, initialFocus) = rememberInitialFocus()

  val trimmed = url.trim()
  val supported = trimmed.isNotEmpty() && CalendarFeed.isSupported(trimmed)
  val provider = if (supported) CalendarFeed.providerName(trimmed) else null

  BackHandler { onCancel() }

  SettingsScaffold(
      title = "Calendar Link",
      subtitle = "Paste a private iCalendar (.ics) link from Google Calendar or Apple iCloud. " +
          "Immortal reads it directly — it can't sign in to your account.",
      onBack = onCancel,
  ) {
    Column {
      OutlinedTextField(
          colors = iosTextFieldColors(),
          value = url,
          onValueChange = { url = it },
          placeholder = {
            Text("https://calendar.google.com/calendar/ical/…/basic.ics")
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
          shape = RoundedCornerShape(14.dp),
      )

      Text(
          when {
            trimmed.isEmpty() -> "Examples: Google \"secret address in iCal format\", Apple iCloud public calendar."
            supported -> "Looks like a $provider link."
            else -> "That doesn't look like a calendar (.ics) link yet."
          },
          color = if (supported || trimmed.isEmpty()) IosSecondary else Color(0xFFFF3B30),
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp),
      )

      Spacer(Modifier.heightIn(min = 26.dp))

      Surface(
          color = if (supported) IosBlue else IosDivider,
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().then(initialFocus).tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                if (supported) onSave(trimmed)
              },
      ) {
        Text(
            "Use this calendar",
            color = if (supported) Color.White else IosSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
        )
      }

      Spacer(Modifier.heightIn(min = 12.dp))

      if (existing.isNotBlank()) {
        Surface(
            color = IosCard,
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                  onRemove()
                },
        ) {
          Text(
              "Remove calendar",
              color = Color(0xFFFF3B30),
              fontSize = Sizes.rowTitle,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
          )
        }
        Spacer(Modifier.heightIn(min = 12.dp))
      }

      Surface(
          color = IosCard,
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                onCancel()
              },
      ) {
        Text(
            "Cancel",
            color = IosBlue,
            fontSize = Sizes.rowTitle,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
        )
      }

      Spacer(Modifier.heightIn(min = 26.dp))

      Text(
          "How to get a link:",
          color = IosLabel,
          fontSize = Sizes.rowSub,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
      )
      Text(
          "• Google Calendar (on a computer) → Settings → your calendar → " +
              "\"Integrate calendar\" → copy the \"Secret address in iCal format\". " +
              "Keep it private — anyone with it can read the calendar.\n" +
              "• Apple iCloud → Calendar app → tap a calendar → turn on \"Public Calendar\" " +
              "→ Copy Link (a webcal:// link works too).",
          color = IosTertiary,
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(start = 4.dp),
      )
    }
  }
}
