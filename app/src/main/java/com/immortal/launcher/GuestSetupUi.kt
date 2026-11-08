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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette

/** Minimum touch target for the guest setup (Portal is used at arm's length). */
internal val GuestTouch = 64.dp

/** A selectable choice: filled when [selected], with an optional catalog [icon]. */
@Composable
internal fun GuestChoiceChip(label: String, selected: Boolean, icon: String? = null, onClick: () -> Unit) {
  val shape = RoundedCornerShape(32.dp)
  Row(Modifier.heightIn(min = GuestTouch).clip(shape)
      .background(if (selected) ApprovedHomePalette.Text else Color.Transparent).approvedOutline(shape)
      .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .semantics { role = Role.Checkbox; this.selected = selected; contentDescription = label }
      .padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    val tint = if (selected) ApprovedHomePalette.Card else ApprovedHomePalette.Secondary
    icon?.let { ApprovedIcon(it, Modifier.size(26.dp), tint = tint) }
    Text(label, color = if (selected) ApprovedHomePalette.Card else ApprovedHomePalette.Text, fontSize = 22.sp,
        lineHeight = 30.sp, fontWeight = FontWeight.Medium)
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuestChipRow(content: @Composable () -> Unit) {
  FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

/** A labelled group inside a step. */
@Composable
internal fun GuestSetupGroup(title: String, help: String? = null, content: @Composable () -> Unit) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    Text(title, color = ApprovedHomePalette.Text, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
    help?.let { GuestHint(it) }
    content()
  }
}

@Composable
internal fun GuestHint(text: String, warning: Boolean = false, modifier: Modifier = Modifier) {
  Text(text, color = if (warning) LocalApprovedPalette.current.error else ApprovedHomePalette.Secondary,
      fontSize = 22.sp, lineHeight = 30.sp, modifier = modifier)
}

/** A full-width on/off row. */
@Composable
internal fun GuestToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  val shape = RoundedCornerShape(20.dp)
  Row(Modifier.fillMaxWidth().heightIn(min = GuestTouch).clip(shape)
      .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent) { onChange(!checked) }
      .semantics(mergeDescendants = true) { role = Role.Switch; selected = checked }.padding(vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically) {
    Text(label, color = ApprovedHomePalette.Text, fontSize = 24.sp, lineHeight = 32.sp, modifier = Modifier.weight(1f))
    ApprovedSettingsSwitch(checked, onChange)
  }
}

/** The step pills along the top: done, current, or still ahead. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GuestStepPills(draft: GuestSetupDraft, onGo: (GuestSetupStep) -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    draft.steps.forEachIndexed { index, step ->
      val current = step == draft.step
      val shape = RoundedCornerShape(28.dp)
      Box(Modifier.heightIn(min = GuestTouch).clip(shape)
          .background(if (current) ApprovedHomePalette.Selected else Color.Transparent)
          .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent) { onGo(step) }
          .semantics { contentDescription = "Step ${index + 1}, ${step.title}${if (current) ", current" else ""}" }
          .padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        Text("${index + 1}  ${step.title}", color = if (current) ApprovedHomePalette.Text else ApprovedHomePalette.Secondary,
            fontSize = 22.sp, fontWeight = if (current) FontWeight.Medium else FontWeight.Normal)
      }
    }
  }
}
