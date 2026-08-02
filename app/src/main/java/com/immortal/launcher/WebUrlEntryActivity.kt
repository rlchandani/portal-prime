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

/** Paste-a-link screen for the web-page screensaver source — a [UrlEntryScreen] configuration. */
class WebUrlEntryActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        UrlEntryScreen(
            title = "Web Page",
            subtitle =
                "Show any web page as your screensaver — Immortal displays it fullscreen and the " +
                    "page provides its own clock and layout. Great for Immich Kiosk or a Home " +
                    "Assistant dashboard. Tap the screen to exit.",
            placeholder = "http://192.168.1.50:3000",
            initial = ScreensaverConfig.load(this).webUrl.orEmpty(),
            emptyHint = "Enter the full address, starting with http:// or https://.",
            invalidHint = "Start the address with http:// or https://.",
            primaryLabel = "Use this page",
            validate = { if (it.startsWith("http://") || it.startsWith("https://")) "" else null },
            onSave = { url ->
              ScreensaverConfig.setWebUrl(this, url)
              finish()
            },
            onCancel = { finish() },
        )
      }
    }
  }
}
