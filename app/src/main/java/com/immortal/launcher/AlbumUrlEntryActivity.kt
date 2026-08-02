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

/** Paste-a-link screen for the URL screensaver source — a [UrlEntryScreen] configuration. */
class AlbumUrlEntryActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        UrlEntryScreen(
            title = "Shared Album Link",
            subtitle =
                "Paste a public link from iCloud Shared Albums or Google Photos. " +
                    "Make sure it's shared as \"anyone with the link\" — Immortal can't sign in.",
            placeholder = "https://photos.icloud.com/shared/album/…",
            initial = ScreensaverConfig.load(this).albumUrl.orEmpty(),
            emptyHint = "Examples: iCloud Shared Album, Google Photos shared album.",
            invalidHint = "That doesn't look like a supported share link yet.",
            primaryLabel = "Use this album",
            validate = { if (RemoteAlbum.isSupported(it)) RemoteAlbum.providerName(it) else null },
            onSave = { url ->
              ScreensaverConfig.setAlbumUrl(this, url)
              finish()
            },
            onCancel = { finish() },
            helpTitle = "How to get a link:",
            helpBody =
                "• iPhone Photos → a Shared Album you own → people/share button → " +
                    "\"Anyone can view\" → copy the icloud.com link.\n" +
                    "• Google Photos → album → Share → \"Get link\" (anyone with the link can view).",
        )
      }
    }
  }
}
