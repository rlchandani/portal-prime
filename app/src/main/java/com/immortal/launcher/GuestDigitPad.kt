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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val KEY = 76.dp
private val ROWS = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"))

/** A large on-screen number pad for the door code; digits only, masked unless shown. */
@Composable
internal fun GuestDigitPad(value: String, onChange: (String) -> Unit) {
  var shown by remember { mutableStateOf(false) }
  Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      ROWS.forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { d -> DigitKey(d) { onChange(value + d) } } } }
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Spacer(Modifier.width(KEY))
        DigitKey("0") { onChange(value + "0") }
        DigitKey("⌫", "Delete last digit") { onChange(value.dropLast(1)) }
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
      val masked = if (value.isEmpty()) "No code yet" else if (shown) value else "•".repeat(value.length)
      Text(masked, color = ApprovedHomePalette.Text, fontSize = 40.sp, lineHeight = 50.sp,
          fontFamily = if (value.isEmpty()) null else FontFamily.Monospace,
          modifier = Modifier.semantics { contentDescription = if (shown || value.isEmpty()) masked else "${value.length} digits entered" })
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolsButton(if (shown) "Hide" else "Show", enabled = value.isNotEmpty()) { shown = !shown }
        ToolsButton("Clear", enabled = value.isNotEmpty()) { onChange("") }
      }
    }
  }
}

@Composable
private fun DigitKey(label: String, description: String = label, onClick: () -> Unit) {
  val shape = RoundedCornerShape(20.dp)
  Box(Modifier.size(KEY).heightIn(min = KEY).clip(shape).background(ApprovedHomePalette.Card).approvedOutline(shape)
      .tvFocusable(shape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .semantics { contentDescription = description }.padding(4.dp), contentAlignment = Alignment.Center) {
    Text(label, color = ApprovedHomePalette.Text, fontSize = 30.sp, fontWeight = FontWeight.Medium)
  }
}
