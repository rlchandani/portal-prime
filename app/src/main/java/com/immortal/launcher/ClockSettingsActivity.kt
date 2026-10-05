/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import org.json.JSONObject

/**
 * Dedicated settings screen for the digital clock. Opened from a "Clock" tile
 * in the launcher's Settings folder. The clock-specific preferences (enable, style,
 * color, font, size, layout, background, glow, show date, show seconds) render from
 * the `digitalclock` registry domain — the same specs the phone remote uses. The
 * screensaver-activation toggles route through the `immortal` domain so its `onApplied`
 * fires. The back-gesture section (accessibility service, overlay permission, test) is
 * genuinely bespoke system-action UI the registry can't model, so it stays hand-built.
 */
class ClockSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { ClockSettingsScreen() } }
  }
}

@Composable
private fun ClockSettingsScreen() {
  val activity = LocalContext.current as? Activity
  SettingsScaffold(
      title = "Clock",
      subtitle = "Choose the digital clock and how it looks on the screensaver.",
      onBack = { activity?.finish() },
  ) { ClockSettingsContent() }
}

/** Inlinable body of the Clock settings screen — rendered both by [ClockSettingsScreen] (standalone
 *  Activity host) and by the two-panel settings detail panel. */
@Composable
internal fun ClockSettingsContent() {
  val context = LocalContext.current
  var clockConfig by remember { mutableStateOf(DigitalClockConfig.load(context)) }
  var settings by remember { mutableStateOf(ImmortalSettings.load(context)) }

  // Re-read on resume so a change in another screen is reflected here.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) {
        clockConfig = DigitalClockConfig.load(context)
        settings = ImmortalSettings.load(context)
      }
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

      Column(modifier = Modifier.focusRequester(firstFocus).focusGroup()) {
        // Clock preferences render from the `digitalclock` registry domain. Apply routes through
        // the domain so its onApplied (reaffirm the dream when `enabled` toggles) fires here too.
        SettingsList(SettingsDomains.digitalclock, clockConfig) { k, v ->
          SettingsDomains.digitalclock.apply(context, JSONObject().put(k, v))
          clockConfig = DigitalClockConfig.load(context)
        }

        // Preview button — opens fullscreen preview (a bespoke action, not a setting).
        SectionLabel("Preview")
        Card {
          ActionSettingRow(
              title = "Preview",
              subtitle = "See the clock full-screen with your current settings.",
              actionLabel = "Preview",
          ) {
            context.startActivity(
                Intent(context, DigitalClockPreviewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          }
        }

        FooterNote(
            "Changes apply immediately. Tap the clock icon on the home screen to start the screensaver.",
        )
      }
}
