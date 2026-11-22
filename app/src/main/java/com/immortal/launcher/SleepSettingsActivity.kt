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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import com.immortal.launcher.settings.IntSpec
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
  val sleepAfterSpec = remember {
    SettingsDomains.screensaver.specs.filterIsInstance<IntSpec<ScreensaverConfig.Settings>>()
        .single { it.key == "sleepTimerMin" }
  }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  fun startCountdown() {
    if (!settings.sleepTimerEnabled) return
    SleepScheduler.armSleepTimer(context)
  }

  fun stopCountdown() = SleepScheduler.cancelSleepTimer(context)

    Column {
      Box(Modifier.focusRequester(firstFocus).focusGroup()) {
        SleepCountdownCard(settings.sleepTimerEnabled, ScreensaverConfig.clampSleepTimer(settings.sleepTimerMin),
            onStart = ::startCountdown, onStop = ::stopCountdown)
      }

      SettingsCardGap()
      // The rows share one card, with the same switches and tiles as every other page.
      Card {
        ToggleRow("Sleep timer", settings.sleepTimerEnabled) {
          SettingsDomains.screensaver.apply(context, JSONObject().put("sleepTimerEnabled", it))
          settings = ScreensaverConfig.load(context)
          if (!it) stopCountdown()
        }
        if (settings.sleepTimerEnabled) {
          Divider()
          SettingsNumberControl("screensaver", sleepAfterSpec, sleepAfterSpec.title,
              ScreensaverConfig.clampSleepTimer(settings.sleepTimerMin), null, enabled = true) { v ->
            val c = ScreensaverConfig.clampSleepTimer(v)
            SettingsDomains.screensaver.apply(context, JSONObject().put("sleepTimerMin", c))
            settings = ScreensaverConfig.load(context)
            // A running timer restarts at the new length, as before.
            if (SleepCountdown.isRunning(SleepScheduler.sleepTimerEndsAtMs, System.currentTimeMillis())) {
              SleepScheduler.armSleepTimer(context)
            }
          }
        }
        Divider()
        ToggleRow("Pause audio before sleeping", settings.pauseAudioOnSleep) {
          SettingsDomains.screensaver.apply(context, JSONObject().put("pauseAudioOnSleep", it))
          settings = ScreensaverConfig.load(context)
        }
        if (advanced) {
          Divider()
          ToggleRow("Close Hearthpane before sleeping", settings.closeAppOnSleep) {
            SettingsDomains.screensaver.apply(context, JSONObject().put("closeAppOnSleep", it))
            settings = ScreensaverConfig.load(context)
          }
        }
      }

      FooterNote(
          if (advanced) "When the timer ends, the screen sleeps using your audio and app preferences."
          else "Turn the screen off after a set time.",
      )
    }
}

