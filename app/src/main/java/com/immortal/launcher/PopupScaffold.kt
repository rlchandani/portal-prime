/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The pop-up counterpart to [SettingsScaffold]: instead of a full-screen page, it renders the same
 * light iOS content (‹ Back pill, large title, optional subtitle, scrolling body) inside a centered
 * floating card over a dim scrim. Used by the tool activities that should feel like a focused
 * overlay rather than a whole screen — Intercom, Countdowns, Alarm, Bedtime story.
 *
 * The hosting Activity must use `@style/Theme.SampleApp.Translucent` so the scrim shows the home
 * (or whatever launched it) behind the card. Both the hardware/gesture Back key and the on-screen
 * Back pill call [onBack]; tapping the scrim outside the card dismisses too.
 *
 * The card is sized generously for the Portal's wide panel — ~66% width (capped) and ~82% height —
 * so it's a substantial window, not a cramped box, matching the enlarged tool overlays.
 */
@Composable
internal fun PopupScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String = "",
    maxWidthDp: Int = 720,
    body: @Composable () -> Unit,
) {
  val cfg = LocalConfiguration.current
  val cardWidth = (cfg.screenWidthDp * 0.66f).dp.coerceAtMost(maxWidthDp.dp)
  val cardMaxHeight = (cfg.screenHeightDp * 0.82f).dp
  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back) {
                  if (e.type == KeyEventType.KeyUp) onBack()
                  true
                } else false
              }
              // Tap the scrim (outside the card) to dismiss.
              .background(Color(0xCC000000))
              .tvFocusable(RoundedCornerShape(0.dp), focusScale = 1f) { onBack() },
  ) {
    Surface(
        color = IosBackground,
        shape = RoundedCornerShape(28.dp),
        // Swallow taps on the card so they don't fall through to the scrim's dismiss.
        modifier = Modifier.width(cardWidth).heightIn(max = cardMaxHeight)
            .tvFocusable(RoundedCornerShape(28.dp), focusScale = 1f) { /* consume */ },
    ) {
      Column(
          modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(30.dp),
      ) {
        Row {
          SettingsBackButton(onClick = onBack)
        }
        Spacer(Modifier.size(18.dp))
        Text(
            text = title,
            color = IosLabel,
            fontSize = Sizes.titlePage,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.6).sp,
        )
        if (subtitle.isNotEmpty()) {
          Text(
              text = subtitle,
              color = IosSecondary,
              fontSize = Sizes.pageSubtitle,
              modifier = Modifier.padding(top = 8.dp),
          )
        }
        Spacer(Modifier.size(20.dp))
        body()
      }
    }
  }
}
