/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import com.immortal.launcher.ui.theme.ApprovedDarkPalette

internal val LocalApprovedHomePopup = compositionLocalOf { false }
internal val ApprovedHomeTabPalette = ApprovedDarkPalette.copy(
    background = Color(0xFF14292E), surface = Color(0xFF223B42),
    surface2 = Color(0xFF2E4851), secondary = Color(0xFFBED0D5),
    divider = Color(0xFF355059), accent = Color(0xFFB4DCD0), selected = Color(0xFF35594F))

internal object ApprovedHomePalette {
  val Background: Color @Composable get() =
      if (LocalApprovedHomePopup.current) LocalApprovedPalette.current.background else Color(0xFF14292E)
  val Card: Color @Composable get() =
      if (LocalApprovedHomePopup.current) LocalApprovedPalette.current.surface else Color(0xFF223B42)
  val Chrome: Color @Composable get() =
      if (LocalApprovedHomePopup.current) LocalApprovedPalette.current.surface2 else Color(0xFF2E4851)
  val Text: Color @Composable get() =
      if (LocalApprovedHomePopup.current) LocalApprovedPalette.current.text else Color(0xFFF4F7F9)
  val Secondary: Color @Composable get() =
      if (LocalApprovedHomePopup.current) LocalApprovedPalette.current.secondary else Color(0xFFBED0D5)
  val Accent: Color @Composable get() =
      if (LocalApprovedHomePopup.current) LocalApprovedPalette.current.accent else Color(0xFFB4DCD0)
  val Border: Color @Composable get() =
      if (LocalApprovedHomePopup.current) LocalApprovedPalette.current.divider else Color(0xFF355059)
  val Selected: Color @Composable get() =
      if (LocalApprovedHomePopup.current) LocalApprovedPalette.current.selected else Color(0xFF35594F)
}

@Composable
internal fun ApprovedHomeSurface(
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
  val shape = RoundedCornerShape(radius)
  Box(modifier.clip(shape).background(ApprovedHomePalette.Card)
      .border(1.dp, ApprovedHomePalette.Border, shape), content = content)
}

@Composable
internal fun ApprovedHomeTextButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
  Box(modifier.heightIn(min = 52.dp)
      .tvFocusable(RoundedCornerShape(16.dp)) { if (enabled) onClick() }
      .padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
    Text(label, color = ApprovedHomePalette.Accent.copy(alpha = if (enabled) 1f else .4f),
        fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
  }
}

/** Home-only empty/loading copy; shared legacy widgets retain their own presentation. */
@Composable
internal fun ApprovedHomeWidgetMessage(text: String) {
  Text(text, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 30.sp)
}

@Composable
internal fun ApprovedHomeIcon(
    name: String,
    modifier: Modifier = Modifier.size(30.dp),
    tint: Color = ApprovedHomePalette.Secondary,
) {
  ApprovedIcon(name, modifier, tint)
}

@Composable
internal fun ApprovedHomeDock(selected: String, onSelect: (String) -> Unit) {
  ApprovedHomeSurface(radius = 26.dp, modifier = Modifier.height(92.dp)) {
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("home" to "Home", "apps" to "Apps", "photo" to "Photos", "tools" to "Tools").forEach { (id, label) ->
        val active = selected == id
        Row(Modifier.width(176.dp).height(74.dp).clip(RoundedCornerShape(18.dp))
            .background(if (active) ApprovedHomePalette.Selected else Color.Transparent)
            .tvFocusable(RoundedCornerShape(18.dp)) { onSelect(id) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
          ApprovedHomeIcon(id, tint = if (active) ApprovedHomePalette.Accent else ApprovedHomePalette.Secondary)
          Text(label, color = if (active) ApprovedHomePalette.Accent else ApprovedHomePalette.Secondary,
              fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
        }
      }
    }
  }
}

@Composable
internal fun ApprovedHomeWidgetHeading(title: String, icon: String, onOpen: () -> Unit) {
  Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    ApprovedHomeIcon(icon, Modifier.size(27.dp))
    Text(title, color = ApprovedHomePalette.Secondary, fontSize = 24.sp, lineHeight = 32.sp,
        fontWeight = FontWeight.Medium, maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    // The negative vertical margin in the approved CSS gives this action a 68dp touch target.
    Box(Modifier.width(68.dp).height(32.dp).tvFocusable(RoundedCornerShape(9.dp), onClick = onOpen),
        contentAlignment = Alignment.Center) {
      Text("Open", color = ApprovedHomePalette.Accent, fontSize = 18.sp, lineHeight = 24.sp,
          fontWeight = FontWeight.Medium)
    }
  }
}
