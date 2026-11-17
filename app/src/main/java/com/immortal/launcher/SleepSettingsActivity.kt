/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import com.immortal.launcher.ApprovedSettingsSwitch as Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlin.math.max
import org.json.JSONObject

class SleepSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { SleepSettingsScreen() } }
  }
}

@Composable
private fun SleepSettingsScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  SettingsScaffold(
      title = "Sleep Timer",
      subtitle =
          if (ScreensaverConfig.load(context).sleepTimerEnabled)
              "Set the timer, then start the countdown."
          else "Enable Sleep Timer below before starting.",
      onBack = { activity?.finish() },
  ) { SleepSettingsContent() }
}

/** Inlinable body of the Sleep settings screen — rendered both by [SleepSettingsScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun SleepSettingsContent() {
  val context = LocalContext.current
  val advanced = com.immortal.launcher.ui.theme.LocalAdvancedSettings.current
  var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  fun startCountdown() {
    if (!settings.sleepTimerEnabled) return
    SleepScheduler.armSleepTimer(context)
  }

  fun stopCountdown() = SleepScheduler.cancelSleepTimer(context)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Box(Modifier.focusRequester(firstFocus).focusGroup()) {
        SleepCountdownCard(settings.sleepTimerEnabled, ScreensaverConfig.clampSleepTimer(settings.sleepTimerMin),
            onStart = ::startCountdown, onStop = ::stopCountdown)
      }

      SleepToggleRow("Sleep Timer", settings.sleepTimerEnabled) {
        SettingsDomains.screensaver.apply(context, JSONObject().put("sleepTimerEnabled", it))
        settings = ScreensaverConfig.load(context)
        if (!it) stopCountdown()
      }

      if (settings.sleepTimerEnabled) {
        SleepMinuteStepper(settings.sleepTimerMin) { v ->
          val c = ScreensaverConfig.clampSleepTimer(v)
          SettingsDomains.screensaver.apply(context, JSONObject().put("sleepTimerMin", c))
          settings = ScreensaverConfig.load(context)
          // A running timer restarts at the new length, as before.
          if (SleepCountdown.isRunning(SleepScheduler.sleepTimerEndsAtMs, System.currentTimeMillis())) {
            SleepScheduler.armSleepTimer(context)
          }
        }
      }

      SleepToggleRow("Pause audio before sleeping", settings.pauseAudioOnSleep) {
        SettingsDomains.screensaver.apply(context, JSONObject().put("pauseAudioOnSleep", it))
        settings = ScreensaverConfig.load(context)
      }

      if (advanced) SleepToggleRow("Close Hearthpane before sleeping", settings.closeAppOnSleep) {
        SettingsDomains.screensaver.apply(context, JSONObject().put("closeAppOnSleep", it))
        settings = ScreensaverConfig.load(context)
      }

      Spacer(Modifier.size(8.dp))
      FooterNote(
          if (advanced) "When the timer ends, the screen sleeps using your audio and app preferences."
          else "Turn the screen off after a set time.",
      )
    }
}

@Composable
private fun SleepToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onChange(!checked) }
              .heightIn(min = 100.dp).padding(horizontal = 28.dp, vertical = 22.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(title, color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = null, colors = iosSwitchColors())
  }
}

@Composable
private fun SleepMinuteStepper(minutes: Int, onChange: (Int) -> Unit) {
  val src = remember { MutableInteractionSource() }
  val focused by src.collectIsFocusedAsState()
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                  when (e.key) {
                    Key.DirectionLeft -> { onChange(max(1, minutes - 5)); true }
                    Key.DirectionRight -> { onChange(minutes + 5); true }
                    else -> false
                  }
                } else false
              }
              .focusable(interactionSource = src)
              .background(if (focused) IosFocusTint else Color.Transparent)
              .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text("Turn off after", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
    ArrowButton("◀", focused) { onChange(max(1, minutes - 5)) }
    Text(
        "${minutes}m",
        color = if (focused) IosLabel else IosSecondary,
        fontSize = Sizes.rowValue,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(min = 64.dp),
    )
    ArrowButton("▶", focused) { onChange(minutes + 5) }
  }
}
