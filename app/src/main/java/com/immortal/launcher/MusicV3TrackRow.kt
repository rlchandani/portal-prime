/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One action in a row's inline ⋯ menu. */
internal data class MusicV3RowAction(val label: String, val run: () -> Unit)

/** What a track row shows; immutable. */
internal data class MusicV3RowModel(
    val key: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val current: Boolean = false,
    /** Why the player refused it (queue rows only); the row is dimmed. */
    val unplayableReason: String? = null,
)

/**
 * A song row: art, title/artist, duration and a ⋯ menu that opens inline under the row (never a
 * sheet, so nothing can cover the player). [leading] holds the queue's drag handle.
 */
@Composable
internal fun MusicV3TrackRow(
    row: MusicV3RowModel,
    onPlay: (() -> Unit)?,
    actions: List<MusicV3RowAction>,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
  var menuOpen by remember(row.key) { mutableStateOf(false) }
  Column(modifier.fillMaxWidth()) {
    val shape = RoundedCornerShape(20.dp)
    val tap = if (onPlay != null && !row.current) {
      Modifier.tvFocusableRow(focusFill = ApprovedHomePalette.Selected, onClick = onPlay)
          .semantics { contentDescription = "Play ${row.title}" }
    } else Modifier
    Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(shape)
        .background(if (row.current) ApprovedHomePalette.Selected else Color.Transparent)
        .then(tap).padding(start = if (leading == null) 12.dp else 0.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      leading?.invoke()
      MusicV3Thumb(row.thumbnailUrl, Modifier.size(72.dp))
      RowText(row, Modifier.weight(1f))
      if (row.current) MusicV3GlyphIcon(MusicV3Glyph.EQ, ApprovedHomePalette.Accent, Modifier.size(28.dp))
      row.durationMs?.takeIf { it > 0 }?.let {
        Text(MusicV3Rules.formatPosition(it), color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
      }
      if (actions.isNotEmpty()) {
        MusicV3RoundButton("More for ${row.title}", onClick = { menuOpen = !menuOpen }) {
          MusicV3GlyphIcon(MusicV3Glyph.MORE, ApprovedHomePalette.Secondary, Modifier.size(28.dp))
        }
      } else Spacer(Modifier.width(MusicV3Dims.Touch))
    }
    if (menuOpen) RowMenu(actions) { menuOpen = false }
  }
}

@Composable
private fun RowText(row: MusicV3RowModel, modifier: Modifier) {
  val alpha = if (row.unplayableReason == null) 1f else 0.5f
  Column(modifier) {
    Text(row.title, color = ApprovedHomePalette.Text.copy(alpha = alpha), fontSize = 26.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(row.unplayableReason ?: row.artist, color = ApprovedHomePalette.Secondary, fontSize = 22.sp,
        lineHeight = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

@Composable
private fun RowMenu(actions: List<MusicV3RowAction>, close: () -> Unit) {
  Row(Modifier.fillMaxWidth().padding(start = 100.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    actions.forEach { action ->
      MusicV3Button(action.label) { close(); action.run() }
    }
    MusicV3Button("Cancel", style = MusicV3ButtonStyle.GHOST, onClick = close)
  }
}
