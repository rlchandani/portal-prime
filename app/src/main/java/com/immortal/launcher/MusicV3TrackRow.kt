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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One action in a row's ⋯ menu; [danger] draws it in the error colour (Remove…). */
internal data class MusicV3RowAction(val label: String, val danger: Boolean = false, val run: () -> Unit)

/** What a track row shows; immutable (and so stable: equal models skip recomposition). */
internal data class MusicV3RowModel(
    val key: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val current: Boolean = false,
    /** Why the player refused it (queue rows only); the row is dimmed. */
    val unplayableReason: String? = null,
    /** Shown where the duration goes, e.g. "Living Area · 2 h ago" on Recently played. */
    val detail: String? = null,
)

/** Lazy-list keys and content types for the music lists (keys never carry a list index). */
internal object MusicV3RowKeys {
  const val TRACK = "track"
  const val HEADER = "header"
  const val NOTE = "note"
  const val SHELF = "shelf"

  /** One stable key per id: the id itself, with "#2", "#3"… only on repeats of the same id. */
  fun unique(ids: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return ids.map { id ->
      val n = (seen[id] ?: 0) + 1
      seen[id] = n
      if (n == 1) id else "$id#$n"
    }
  }
}

private const val MENU_MIN_WIDTH = 380
private const val MENU_ITEM_HEIGHT = 80

/**
 * A song row: art, title/artist, duration and a ⋯ menu that opens inline under the row as an opaque
 * card inside the sheet's list (so it can never reach the player). [leading] holds the queue's grip.
 */
@Composable
internal fun MusicV3TrackRow(
    row: MusicV3RowModel,
    onPlay: (() -> Unit)?,
    actions: List<MusicV3RowAction>,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
  val dims = LocalMusicStageDims.current
  var menuOpen by remember(row.key) { mutableStateOf(false) }
  Column(modifier.fillMaxWidth()) {
    val shape = RoundedCornerShape(16.dp)
    val tap = if (onPlay != null && !row.current) {
      Modifier.tvFocusableRow(focusFill = ApprovedHomePalette.Selected, onClick = onPlay)
          .semantics { role = Role.Button; contentDescription = "Play ${row.title}" }
    } else Modifier
    Row(Modifier.fillMaxWidth().heightIn(min = dims.row.dp).clip(shape)
        .background(if (row.current) ApprovedHomePalette.Selected else Color.Transparent)
        .then(tap).padding(start = if (leading == null) 8.dp else 0.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
      leading?.invoke()
      MusicV3Thumb(row.thumbnailUrl, Modifier.size(dims.thumb.dp), displaySize = dims.thumb.dp)
      RowText(row, dims, Modifier.weight(1f))
      if (row.current) MusicV3GlyphIcon(MusicV3Glyph.EQ, ApprovedHomePalette.Accent, Modifier.size(28.dp))
      val trailing = row.detail ?: row.durationMs?.takeIf { it > 0 }?.let(MusicV3Rules::formatPosition)
      trailing?.let { Text(it, color = ApprovedHomePalette.Secondary, fontSize = dims.small.sp, maxLines = 1) }
      if (actions.isNotEmpty()) {
        MusicV3RoundButton("More for ${row.title}", size = dims.target.dp, onClick = { menuOpen = !menuOpen }) {
          MusicV3GlyphIcon(MusicV3Glyph.MORE, ApprovedHomePalette.Secondary, Modifier.size(30.dp))
        }
      } else Spacer(Modifier.width(dims.target.dp))
    }
    if (menuOpen) RowMenu(actions) { menuOpen = false }
    Box(Modifier.padding(start = (dims.thumb + 30).dp).fillMaxWidth().height(1.dp).background(ApprovedHomePalette.Border))
  }
}

@Composable
private fun RowText(row: MusicV3RowModel, dims: MusicStageDims, modifier: Modifier) {
  val alpha = if (row.unplayableReason == null) 1f else 0.5f
  Column(modifier) {
    Text(row.title, color = ApprovedHomePalette.Text.copy(alpha = alpha), fontSize = dims.body.sp,
        lineHeight = (dims.body * 1.25f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(row.unplayableReason ?: row.artist, color = ApprovedHomePalette.Secondary, fontSize = dims.small.sp,
        lineHeight = (dims.small * 1.3f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
  }
}

/** The ⋯ menu: an opaque card right-aligned under the row, one 80 dp item per action plus Cancel. */
@Composable
private fun RowMenu(actions: List<MusicV3RowAction>, close: () -> Unit) {
  val shape = RoundedCornerShape(24.dp)
  Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp), contentAlignment = Alignment.CenterEnd) {
    Column(Modifier.widthIn(min = MENU_MIN_WIDTH.dp).clip(shape).background(ApprovedHomePalette.Card)
        .approvedOutline(shape).padding(vertical = 8.dp)) {
      (actions + MusicV3RowAction("Cancel", run = {})).forEach { action ->
        val ink = if (action.danger) MusicV3MenuInk.danger else ApprovedHomePalette.Text
        Box(Modifier.widthIn(min = MENU_MIN_WIDTH.dp).heightIn(min = MENU_ITEM_HEIGHT.dp)
            .tvFocusableRow(focusFill = ApprovedHomePalette.Selected) { close(); action.run() }
            .semantics { role = Role.Button }.padding(horizontal = 30.dp), contentAlignment = Alignment.CenterStart) {
          Text(action.label, color = ink, fontSize = LocalMusicStageDims.current.body.sp, fontWeight = FontWeight.Medium)
        }
      }
    }
  }
}

/** The mock's `.menu button.danger` red. */
internal object MusicV3MenuInk {
  val danger = Color(0xFFD0473B)
}
