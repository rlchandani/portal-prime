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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * Screensaver render-mode picker (reached from a "Style" row in Screensaver settings). Lists
 * Classic + Shuffle + the 12 engine modes; selecting one persists it. A "Start screensaver now"
 * button launches the frame immediately so the choice is visible at once.
 */
class ScreensaverModeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { ScreensaverModeScreen() } }
  }
}

/** One-line description shown under each mode name in the picker. */
private fun modeDesc(m: String): String = when (m) {
  ScreensaverConfig.MODE_CLASSIC -> "The original simple crossfade + gentle zoom."
  ScreensaverConfig.MODE_SHUFFLE -> "Cycles through all the styles below, matched to each photo's shape."
  "kenBurns" -> "One photo at a time with a slow cinematic pan & zoom; portraits get a blurred fill."
  "diptych" -> "Two portraits side-by-side, landscapes solo, panoramas full-width."
  "framed" -> "Each photo hung as a matted print on a gallery wall."
  "galleryWall" -> "A living collage of many photos that drift and swap."
  "push" -> "The camera pushes through each photo into the next."
  "memories" -> "Single photos, with occasional titled 'memory' collages."
  "panorama" -> "A slow horizontal glide across wide and panoramic shots."
  "polaroid" -> "Photos drop onto a table as tilted polaroid prints."
  "mosaic" -> "A dense mosaic wall of big and small tiles."
  "filmstrip" -> "Photos glide to center-stage; the room glows with their color."
  "cinema" -> "Widescreen letterbox framing with film grain."
  "heroSidebar" -> "A big hero photo beside two stacked portraits."
  else -> ""
}

@Composable
private fun ScreensaverModeScreen() {
  val context = LocalContext.current
  val activity = context as? Activity
  var mode by remember { mutableStateOf(ScreensaverConfig.load(context).mode) }

  SettingsScaffold(
      title = "Screensaver Style",
      subtitle = "Pick how your photos animate. Shuffle mixes them all. Tap Start to preview now.",
      onBack = { activity?.finish() },
  ) {
    // Start-now button up top so it's always reachable.
    Surface(
        color = IosBlue,
        shape = RoundedCornerShape(Sizes.cardRadius),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            .tvFocusable(RoundedCornerShape(Sizes.cardRadius), focusScale = 1f) {
              runCatching {
                context.startActivity(
                    Intent(context, PhotoFramePreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
              }
            },
    ) {
      Text(
          "▶  Start screensaver now",
          color = Color.White,
          fontSize = 18.sp,
          fontWeight = FontWeight.SemiBold,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(vertical = 15.dp).fillMaxWidth(),
      )
    }

    Card {
      Column(modifier = Modifier.padding(vertical = 4.dp)) {
        ScreensaverConfig.MODES.forEachIndexed { i, m ->
          if (i > 0) Divider()
          val selected = m == mode
          Row(
              modifier = Modifier.fillMaxWidth()
                  .tvFocusableRow {
                    ScreensaverConfig.setMode(context, m)
                    mode = m
                  }
                  .padding(horizontal = 18.dp, vertical = 14.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                  ScreensaverConfig.modeLabel(m),
                  color = IosLabel,
                  fontSize = Sizes.rowTitle,
                  fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
              )
              Text(modeDesc(m), color = IosSecondary, fontSize = Sizes.rowSub, modifier = Modifier.padding(top = 2.dp))
            }
            if (selected) {
              Text("✓", color = IosBlue, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
            }
          }
        }
      }
    }
    Spacer(Modifier.size(8.dp))
  }
}
