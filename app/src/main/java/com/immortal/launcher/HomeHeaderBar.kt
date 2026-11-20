/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun ApprovedHeaderButton(icon: String, label: String, size: Dp = 68.dp, onClick: () -> Unit) =
    ApprovedHeaderButton(icon, label, lit = false, size = size, onClick = onClick)

/** [lit]: inverted fill (the music button while a song is loaded). [size]: 56 dp in the compact tier. */
@Composable
internal fun ApprovedHeaderButton(icon: String, label: String, lit: Boolean, size: Dp = 68.dp, onClick: () -> Unit) {
  Box(Modifier.size(size).clip(CircleShape)
      .background(if (lit) ApprovedHomePalette.Text else ApprovedHomePalette.Chrome).approvedOutline(CircleShape)
      .semantics { contentDescription = label }
      .tvFocusable(CircleShape, ringColor = ApprovedHomePalette.Accent, onClick = onClick),
      contentAlignment = Alignment.Center) {
    ApprovedHomeIcon(icon, tint = if (lit) ApprovedHomePalette.Background else ApprovedHomePalette.Text)
  }
}
