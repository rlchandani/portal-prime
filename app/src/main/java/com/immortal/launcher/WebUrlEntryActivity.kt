/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * Enter a web page URL to use as the screensaver — Immortal renders it fullscreen and the page
 * supplies its own clock/widgets. The headline use case is Immich Kiosk; it also covers Home
 * Assistant dashboards and any web-based frame. A "bring your own frame" power-user option.
 */
class WebUrlEntryActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      PortalPrimeTheme(darkTheme = false) {
        WebUrlEntryScreen(
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

@Composable
private fun WebUrlEntryScreen(onSave: (String) -> Unit, onCancel: () -> Unit) {
  val context = LocalContext.current
  var url by remember { mutableStateOf(ScreensaverConfig.load(context).webUrl.orEmpty()) }
  val (_, initialFocus) = rememberInitialFocus()

  val trimmed = url.trim()
  val valid = trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)

  BackHandler { onCancel() }

  SettingsScaffold(
      title = "Web Page",
      subtitle = "Show any web page as your screensaver — Immortal displays it fullscreen and the " +
          "page provides its own clock and layout. Great for Immich Kiosk or a Home Assistant " +
          "dashboard. Tap the screen to exit.",
      onBack = onCancel,
  ) {
    Column {
      OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          placeholder = { Text("http://192.168.1.50:3000") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
          shape = RoundedCornerShape(14.dp),
      )
      Text(
          when {
            trimmed.isEmpty() -> "Enter the full address, starting with http:// or https://."
            valid -> "Looks good."
            else -> "Start the address with http:// or https://."
          },
          color = if (valid || trimmed.isEmpty()) IosSecondary else Color(0xFFFF3B30),
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp),
      )

      Spacer(Modifier.heightIn(min = 26.dp))
      Surface(
          color = if (valid) IosBlue else IosDivider,
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().then(initialFocus).tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                if (valid) onSave(trimmed)
              },
      ) {
        Text(
            "Use this page",
            color = if (valid) Color.White else IosSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 16.dp).fillMaxWidth(),
        )
      }
      Spacer(Modifier.heightIn(min = 12.dp))
      Surface(
          color = IosCard,
          shape = RoundedCornerShape(16.dp),
          modifier =
              Modifier.fillMaxWidth().tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f) {
                onCancel()
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
