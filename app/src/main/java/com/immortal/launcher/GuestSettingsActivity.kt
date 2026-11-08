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

/** Settings → Guest card as its own screen; the body is the guest setup flow ([GuestSettingsContent]). */
class GuestSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        SettingsScaffold(title = "Guest card", subtitle = "Pick your Wi-Fi and a few house basics for visitors.",
            onBack = rememberApprovedSettingsBack()) { GuestSettingsContent() }
      }
    }
  }
}
