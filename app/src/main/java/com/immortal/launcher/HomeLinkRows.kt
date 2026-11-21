/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/**
 * Sizes for the household screens, from the mock's tokens: 1 rem = 10 dp on a Portal
 * (1920 × 1280), and the Mini (800 × 1280, `html.mini`) a step down but never below 64 dp for
 * anything people touch. Pure, so the floor is tested.
 */
@Immutable
internal data class HomeLinkDims(
    val pad: Dp, val rowMin: Dp, val rowPadH: Dp, val navMin: Dp, val sideWidth: Dp, val avatar: Dp, val bigAvatar: Dp,
    val switchW: Dp, val switchH: Dp, val label: TextUnit, val sub: TextUnit, val section: TextUnit, val title: TextUnit,
    val gap: Dp, val roomColumns: Int,
) {
  /** Everything people touch, for the ≥ 64 dp check. */
  val targets: List<Dp> get() = listOf(rowMin, navMin)

  companion object {
    val PORTAL = HomeLinkDims(pad = 56.dp, rowMin = 110.dp, rowPadH = 32.dp, navMin = 96.dp, sideWidth = 600.dp, avatar = 56.dp,
        bigAvatar = 140.dp, switchW = 96.dp, switchH = 58.dp, label = 29.sp, sub = 22.sp, section = 22.sp, title = 36.sp,
        gap = 32.dp, roomColumns = 2)
    val MINI = HomeLinkDims(pad = 32.dp, rowMin = 96.dp, rowPadH = 24.dp, navMin = 88.dp, sideWidth = 0.dp, avatar = 56.dp,
        bigAvatar = 96.dp, switchW = 80.dp, switchH = 48.dp, label = 25.sp, sub = 19.sp, section = 19.sp, title = 30.sp,
        gap = 24.dp, roomColumns = 1)
    const val MIN_TARGET_DP = 64f

    fun of(compact: Boolean): HomeLinkDims = if (compact) MINI else PORTAL
  }
}

@Composable
@ReadOnlyComposable
internal fun homeLinkDims(): HomeLinkDims = HomeLinkDims.of(currentLayoutClass().compact)

/** `.card`: surface, 32 dp corners, hairline outline. */
@Composable
internal fun HomeLinkCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val p = LocalApprovedPalette.current
  val shape = RoundedCornerShape(32.dp)
  Column(modifier.fillMaxWidth().background(p.surface, shape).border(1.5.dp, p.outline, shape)) { content() }
}

/** `.shd`: the small heading over a card. */
@Composable
internal fun HomeLinkSection(text: String) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  Text(text, color = p.secondary, fontSize = d.section, fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(start = d.rowPadH, bottom = 12.dp))
}

/** `.row`: optional lead, title over sub, a tail. A divider above every row but the first. */
@Composable
internal fun HomeLinkRow(title: String, sub: String?, first: Boolean, modifier: Modifier = Modifier,
                         lead: (@Composable () -> Unit)? = null, tail: @Composable RowScope.() -> Unit = {}) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  if (!first) HorizontalDivider(color = p.divider, modifier = Modifier.padding(horizontal = d.rowPadH))
  Row(modifier.fillMaxWidth().heightIn(min = d.rowMin).padding(horizontal = d.rowPadH, vertical = 16.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
    lead?.invoke()
    Column(Modifier.weight(1f)) {
      Text(title, color = p.text, fontSize = d.label, fontWeight = FontWeight.SemiBold)
      sub?.let { Text(it, color = p.secondary, fontSize = d.sub, lineHeight = d.sub * LINE) }
    }
    tail()
  }
}

/** A row that is one big switch (the whole row toggles, as the mock's `sw` rows do). [locked] can't change. */
@Composable
internal fun HomeLinkSwitchRow(title: String, sub: String?, checked: Boolean, first: Boolean, onChange: ((Boolean) -> Unit)?,
                               lead: (@Composable () -> Unit)? = null) {
  val toggle = if (onChange == null) Modifier.semantics { contentDescription = "$title, always off"; stateDescription = "Off" }
  else Modifier.toggleable(checked, remember { MutableInteractionSource() }, null, role = Role.Switch, onValueChange = onChange)
      .semantics { stateDescription = if (checked) "On" else "Off" }
  HomeLinkRow(title, sub, first, toggle, lead) { HomeLinkSwitch(checked, locked = onChange == null) }
}

/** `.sw`: 96 × 58 dp on a Portal, green when on, faded when locked. Draws only; the row handles touch. */
@Composable
internal fun HomeLinkSwitch(checked: Boolean, locked: Boolean = false) {
  val p = LocalApprovedPalette.current
  val d = homeLinkDims()
  val inset = d.switchH * THUMB_INSET
  val thumb = d.switchH - inset * 2
  Box(Modifier.size(d.switchW, d.switchH).then(if (locked) Modifier.alpha(LOCKED_ALPHA) else Modifier)
      .background(if (checked) CallColors.Ok else p.outline, RoundedCornerShape(50)), contentAlignment = Alignment.CenterStart) {
    Box(Modifier.offset(x = if (checked) d.switchW - thumb - inset else inset).size(thumb).background(Color.White, CircleShape))
  }
}

private const val LINE = 1.3f
private const val THUMB_INSET = 0.0862f
private const val LOCKED_ALPHA = 0.5f
