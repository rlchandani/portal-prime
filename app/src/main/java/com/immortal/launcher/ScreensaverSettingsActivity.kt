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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.settings.SettingsDomain
import com.immortal.launcher.settings.SettingsDomains
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import org.json.JSONObject

/**
 * Settings screen for the photo-frame screensaver. Reached from a "Screensaver" tile in the
 * launcher's Settings folder. The controls are now rendered generically from the `screensaver` and
 * `calendar` settings domains (the same registry that drives the phone remote) via [SettingsList];
 * the rich sub-screens (clock-face picker, photo-source setup, calendar/dismiss entry) stay bespoke
 * and are reached through the registry's `NavSpec`s.
 */
class ScreensaverSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { ScreensaverSettingsScreen() } }
  }
}

@Composable
private fun ScreensaverSettingsScreen() {
  val context = LocalContext.current
  var settings by remember { mutableStateOf(ScreensaverConfig.load(context)) }

  // Re-read config when we come back from a sub-screen (source picker, face picker, dismiss target).
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val obs = LifecycleEventObserver { _, e ->
      if (e == Lifecycle.Event.ON_RESUME) settings = ScreensaverConfig.load(context)
    }
    lifecycleOwner.lifecycle.addObserver(obs)
    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
  }

  val activity = context as? Activity
  val firstFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

  // Every change routes through the registry's apply (which fires the domain's onApplied side
  // effects — reaffirm + overnight reschedule), then we re-read for the next recomposition.
  fun apply(domain: SettingsDomain<ScreensaverConfig.Settings>, key: String, value: Any) {
    domain.apply(context, JSONObject().put(key, value))
    settings = ScreensaverConfig.load(context)
  }

  SettingsScaffold(
      title = "Screensaver",
      subtitle = "Choose what shows on the photo frame when your Portal is idle.",
      onBack = { activity?.finish() },
  ) {
    Column(modifier = Modifier.focusRequester(firstFocus).focusGroup()) {
      // Master toggle gates everything below (kept here rather than in the list so the rest only
      // renders when the frame is on, matching the original screen).
      Card {
        ToggleRow("Show the photo-frame screensaver", settings.enabled) {
          apply(SettingsDomains.screensaver, "enabled", it)
        }
      }
      FooterNote(
          "Turn this off to let your Portal's screen sleep on its own timer (or run your own " +
              "screensaver). Immortal won't switch it back on.",
      )

      if (settings.enabled) {
        Spacer(Modifier.size(20.dp))
        SettingsList(SettingsDomains.screensaver, settings, exclude = setOf("enabled")) { k, v ->
          apply(SettingsDomains.screensaver, k, v)
        }
        SectionLabel("Calendar")
        SettingsList(SettingsDomains.calendar, settings) { k, v ->
          apply(SettingsDomains.calendar, k, v)
        }
        Spacer(Modifier.size(6.dp))
        Surface(
            color = IosBlue,
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                  context.startActivity(Intent(context, PhotoFramePreviewActivity::class.java))
                },
        ) {
          Text(
              "Preview screensaver",
              color = Color.White,
              fontSize = 18.sp,
              fontWeight = FontWeight.SemiBold,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
          )
        }
      }
    }
  }
}
