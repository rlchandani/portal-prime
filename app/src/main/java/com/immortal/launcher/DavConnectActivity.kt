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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme
import kotlin.concurrent.thread

/**
 * Connect a WebDAV folder (a NAS WebDAV share or Nextcloud) as the screensaver source: the
 * full folder URL plus optional credentials. "Test & connect" lists the images before saving.
 * Modelled on [SmbConnectActivity].
 */
class DavConnectActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { DavConnectScreen(onDone = { finish() }) } }
  }
}

@Composable
private fun DavConnectScreen(onDone: () -> Unit) {
  val context = LocalContext.current
  val ui = remember { Handler(Looper.getMainLooper()) }
  val existing = remember { ScreensaverConfig.load(context) }

  var url by remember { mutableStateOf(existing.davUrl ?: "") }
  var user by remember { mutableStateOf(existing.davUser ?: "") }
  var pass by remember { mutableStateOf(existing.davPass ?: "") }
  var testing by remember { mutableStateOf(false) }
  var status by remember { mutableStateOf<String?>(null) }
  var connectedCount by remember { mutableStateOf<Int?>(null) }
  val (_, initialFocus) = rememberInitialFocus()

  BackHandler { onDone() }

  fun connect() {
    if (url.isBlank()) {
      status = "Enter the WebDAV folder address."
      return
    }
    testing = true
    status = null
    thread {
      val images = DavSource.listImageUrls(url.trim(), user.trim(), pass).orEmpty()
      ui.post {
        testing = false
        if (images.isNotEmpty()) {
          ScreensaverConfig.setDav(context, url, user, pass)
          connectedCount = images.size
        } else {
          status =
              "Couldn't read images from that address. Check the URL and (if needed) the username " +
                  "and password."
        }
      }
    }
  }

  SettingsScaffold(title = "WebDAV Folder", onBack = onDone) {
    Column {
      val count = connectedCount
      if (count != null) {
        Text(
            "Connected — found $count photo${if (count == 1) "" else "s"}. Showing them on the " +
                "screensaver now.",
            color = IosSecondary,
            fontSize = Sizes.rowSub,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.heightIn(min = 26.dp))
        PrimaryButton("Done") { onDone() }
        return@Column
      }

      Text(
          "Show photos from a WebDAV folder — a NAS WebDAV share or Nextcloud. Paste the full " +
              "folder URL, and a username and password if the server needs them.",
          color = IosSecondary,
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(top = 6.dp),
      )
      Spacer(Modifier.heightIn(min = 22.dp))

      Field(url, { url = it }, "http://nas:30035/share/photos/", modifier = initialFocus)
      Spacer(Modifier.heightIn(min = 12.dp))
      Field(user, { user = it }, "Username (optional)")
      Spacer(Modifier.heightIn(min = 12.dp))
      Field(pass, { pass = it }, "Password (optional)", password = true)

      status?.let {
        Text(
            it,
            color = Color(0xFFFF3B30),
            fontSize = Sizes.rowSub,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        )
      }

      Spacer(Modifier.heightIn(min = 26.dp))
      PrimaryButton(if (testing) "Connecting…" else "Test & connect", enabled = !testing) {
        if (!testing) connect()
      }
      Spacer(Modifier.heightIn(min = 12.dp))
      Surface(
          color = IosCard,
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                onDone()
              },
      ) {
        Text(
            "Cancel",
            color = IosBlue,
            fontSize = Sizes.rowTitle,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    modifier: Modifier = Modifier,
) {
  OutlinedTextField(
      colors = iosTextFieldColors(),
      value = value,
      onValueChange = onChange,
      placeholder = { Text(placeholder) },
      singleLine = true,
      visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
      modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
      shape = RoundedCornerShape(14.dp),
  )
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
  Surface(
      color = if (enabled) IosBlue else IosDivider,
      shape = RoundedCornerShape(16.dp),
      modifier =
          Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
            onClick()
          },
  ) {
    Text(
        label,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
    )
  }
}
