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

/** Native web-frame form using the same format validator as the source write boundaries. */
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
            emptyHint = "Enter a complete http:// or https:// web page address.",
            invalidHint = "Enter a complete http:// or https:// address with a host.",
            fieldHelp = "Use a web page or dashboard that this Portal can reach.",
            validHint = { "Address format is valid. The Portal still needs to be able to load this page." },
            primaryLabel = "Use this page",
            validate = { if (WebPageUrl.normalize(it) != null) "" else null },
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
