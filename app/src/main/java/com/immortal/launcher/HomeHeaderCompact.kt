/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.immortal.launcher.ui.theme.LocalApprovedPalette

private val MenuWidth = 340.dp
private val MenuRowHeight = 64.dp

/**
 * The compact header's "⋯": the three buttons the Mini's 800 dp header can't fit (connect your
 * phone, sleep screen, day/night theme) plus the connection status the Wi-Fi glyph would show.
 * Same actions as the regular header's buttons; the menu closes before an action runs.
 */
@Composable
internal fun HomeHeaderOverflowButton(size: Dp, status: String, onRemote: () -> Unit, onSleep: () -> Unit) {
  val context = LocalContext.current
  val palette = LocalApprovedPalette.current
  var open by remember { mutableStateOf(false) }
  Box {
    Box(Modifier.size(size).clip(CircleShape).background(if (open) ApprovedHomePalette.Text else ApprovedHomePalette.Chrome)
        .approvedOutline(CircleShape)
        .semantics { contentDescription = "More: phone, sleep and theme" }
        .tvFocusable(CircleShape, ringColor = ApprovedHomePalette.Accent) { open = !open },
        contentAlignment = Alignment.Center) {
      ApprovedHomeIcon("more", tint = if (open) ApprovedHomePalette.Background else ApprovedHomePalette.Text)
    }
    if (open) {
      val gap = with(LocalDensity.current) { (size + 10.dp).roundToPx() }
      Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, gap), onDismissRequest = { open = false },
          properties = PopupProperties(focusable = true)) {
        ApprovedHomeSurface(Modifier.width(MenuWidth), radius = 22.dp) {
          Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth().height(MenuRowHeight).padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              ApprovedHomeIcon("wifi", Modifier.size(26.dp))
              Text(status, color = ApprovedHomePalette.Secondary, fontSize = 20.sp, maxLines = 1,
                  overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(ApprovedHomePalette.Border))
            OverflowRow("remote", "Connect your phone") { open = false; onRemote() }
            OverflowRow("sleep", "Sleep screen") { open = false; onSleep() }
            OverflowRow(if (palette.dark) "theme_day" else "theme_night",
                if (palette.dark) "Switch to day theme" else "Switch to night theme") {
              open = false
              toggleApprovedTheme(context, palette.dark)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun OverflowRow(icon: String, label: String, onClick: () -> Unit) {
  Row(Modifier.fillMaxWidth().height(MenuRowHeight).padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp))
      .semantics { contentDescription = label }
      .tvFocusable(RoundedCornerShape(14.dp), focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    ApprovedHomeIcon(icon, Modifier.size(28.dp), tint = ApprovedHomePalette.Text)
    Text(label, color = ApprovedHomePalette.Text, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        overflow = TextOverflow.Ellipsis)
  }
}
