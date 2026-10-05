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

/** Native web-frame form, preserving the existing literal http/https validation. */
class WebUrlEntryActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        UrlEntryScreen(
            title = "Web page",
            fieldLabel = "Web page address",
            advancedOnly = true,
            placeholder = "https://…",
            initial = ScreensaverConfig.load(this).webUrl.orEmpty(),
            emptyHint = "Enter an address starting with http:// or https://.",
            invalidHint = "Start the address with http:// or https://.",
            primaryLabel = "Use this page",
            validate = { if (it.startsWith("http://") || it.startsWith("https://")) "" else null },
            onSave = { url ->
              ScreensaverConfig.setWebUrl(this, url)
              approvedSourcesReturn(this)
            },
            onCancel = { finish() },
        )
      }
    }
  }
}
