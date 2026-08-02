/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlin.concurrent.thread

/**
 * Connect an Immich server as the screensaver source: enter the server URL + an API key, test
 * the connection, then pick an album (or the whole library). Modelled on [AlbumUrlEntryActivity].
 */
class ImmichConnectActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { ImmichConnectScreen(onDone = { finish() }) } }
  }
}

@Composable
private fun ImmichConnectScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val ui = remember { Handler(Looper.getMainLooper()) }
  val existing = remember { ScreensaverConfig.load(context) }

  var url by remember { mutableStateOf(existing.immichUrl ?: "") }
  var key by remember { mutableStateOf(existing.immichKey ?: "") }
  var testing by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf<String?>(null) }
  // null = not connected yet (show the form); non-null = connected, show the album picker.
  var albums by remember { mutableStateOf<List<ImmichSource.Album>?>(null) }
  var selectedAlbumId by remember { mutableStateOf(existing.immichAlbumId) }
  val (_, initialFocus) = rememberInitialFocus()

  BackHandler { onDone() }

  fun connect() {
    val u = url.trim()
    val k = key.trim()
    if (u.isEmpty() || k.isEmpty()) {
      status = "Enter both the server address and an API key."
      return
    }
    testing = true
    status = null
    thread {
      val ok = ImmichSource.testConnection(u, k)
      val list = if (ok) ImmichSource.listAlbums(u, k) else null
      ui.post {
        testing = false
        if (ok) {
          ScreensaverConfig.setImmich(context, u, k)
          albums = list ?: emptyList()
        } else {
          status = "Couldn't reach Immich or the key was rejected. Check the address and key."
        }
      }
    }
  }

  fun choose(albumId: String?, albumName: String?) {
    ScreensaverConfig.setImmichAlbum(context, albumId, albumName)
    onDone()
  }

  SettingsScaffold(title = "Immich Server", onBack = onDone) {
    Column {
      val loaded = albums
      if (loaded == null) {
        // --- Step 1: connection details ---
        Text(
            "Pull photos straight from your self-hosted Immich server. Enter its address and an " +
                "API key (Immich → Account Settings → API Keys).",
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.heightIn(min = 22.dp))

        IosTextField(url, { url = it }, "http://192.168.1.50:2283", modifier = initialFocus)
        Spacer(Modifier.heightIn(min = 12.dp))
        IosTextField(key, { key = it }, "API key")

        status?.let {
          Text(
              it,
              color = IosRed,
              fontSize = Sizes.rowSub,
              modifier = Modifier.padding(top = 10.dp, start = 4.dp),
          )
        }

        Spacer(Modifier.heightIn(min = 26.dp))
        PrimaryPillButton(if (testing) "Connecting…" else "Test & connect", enabled = !testing) { connect() }
        Spacer(Modifier.heightIn(min = 12.dp))
        CardPillButton("Cancel") { onDone() }
      } else {
        // --- Step 2: pick an album (or the whole library) ---
        Text(
            "Connected. Choose what to show:",
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.heightIn(min = 22.dp))

        Surface(color = IosCard, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
          Column {
            AlbumRow("Whole library", "All your photos", selectedAlbumId == null) {
              selectedAlbumId = null
              choose(null, null)
            }
            loaded.forEach { a ->
              Divider()
              AlbumRow(a.name, "${a.count} photo${if (a.count == 1) "" else "s"}", selectedAlbumId == a.id) {
                selectedAlbumId = a.id
                choose(a.id, a.name)
              }
            }
          }
        }
        Spacer(Modifier.heightIn(min = 16.dp))
        CardPillButton("Cancel") { onDone() }
      }
    }
  }
}

@Composable
private fun AlbumRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
  Row(
      modifier =
          Modifier.fillMaxWidth().tvFocusableRow { onClick() }
              .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
      Text(title, color = IosLabel, fontSize = Sizes.rowTitle)
      Text(subtitle, color = IosSecondary, fontSize = Sizes.rowSub, modifier = Modifier.padding(top = 2.dp))
    }
    RadioButton(selected = selected, onClick = null)
  }
}

