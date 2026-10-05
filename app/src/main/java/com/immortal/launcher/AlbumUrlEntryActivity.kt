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

/** Native shared-link form; provider recognition stays with [RemoteAlbum]. */
class AlbumUrlEntryActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        UrlEntryScreen(
            title = "Shared album link",
            fieldLabel = "Shared album link",
            placeholder = "https://…",
            initial = ScreensaverConfig.load(this).albumUrl.orEmpty(),
            emptyHint = "Examples: iCloud Shared Album, Google Photos shared album.",
            invalidHint = "That doesn’t look like a supported share link yet.",
            primaryLabel = "Use this album",
            validate = { if (RemoteAlbum.isSupported(it)) RemoteAlbum.providerName(it) else null },
            onSave = { url ->
              ScreensaverConfig.setAlbumUrl(this, url)
              approvedSourcesReturn(this)
            },
            onCancel = { finish() },
        )
      }
    }
  }
}
