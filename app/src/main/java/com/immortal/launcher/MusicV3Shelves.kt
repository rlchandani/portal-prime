/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What one shelf tile shows; immutable. */
internal data class MusicV3TileModel(
    val key: String,
    val title: String,
    val caption: String,
    val art: String?,
    val round: Boolean = false,
    val dimmed: Boolean = false,
    val spoken: String = "$title, $caption",
)

/** A horizontal shelf of tiles (the mock's `.shelf`), keyed and typed for reuse. */
@Composable
internal fun <T> MusicV3Shelf(
    items: List<T>,
    key: (T) -> String,
    modifier: Modifier = Modifier,
    tile: @Composable (T) -> Unit,
) {
  val compact = LocalMusicStageDims.current == MusicStageDims.COMPACT
  LazyRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 28.dp),
      contentPadding = PaddingValues(vertical = if (compact) 8.dp else 14.dp)) {
    items(items, key = key, contentType = { MusicV3RowKeys.SHELF }) { tile(it) }
  }
}

/** Square (or round, for channels) art with a title and a caption; [corner] is drawn top-right on the art. */
@Composable
internal fun MusicV3Tile(
    model: MusicV3TileModel,
    width: Dp,
    onClick: () -> Unit,
    corner: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val shape = if (model.round) CircleShape else RoundedCornerShape(if (dims == MusicStageDims.COMPACT) 12.dp else 18.dp)
  Column(Modifier.width(width)) {
    Box {
      Column(Modifier.clip(RoundedCornerShape(18.dp)).alpha(if (model.dimmed) 0.5f else 1f)
          .tvFocusable(RoundedCornerShape(18.dp), focusScale = 1f, ringColor = ink.ember, onClick = onClick)
          .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = model.spoken },
          horizontalAlignment = if (model.round) Alignment.CenterHorizontally else Alignment.Start) {
        MusicV3Thumb(model.art, Modifier.fillMaxWidth().aspectRatio(1f), shape, displaySize = width)
        val align = if (model.round) TextAlign.Center else TextAlign.Start
        Text(model.title, color = ink.ink, fontSize = (dims.body * 0.86f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = align, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Text(model.caption, color = ink.muted, fontSize = (dims.small * 0.92f).sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = align, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
      }
      corner?.let { Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { it() } }
    }
    below?.invoke()
  }
}

/** The ⋯ on a tile's corner (needs its own touch target over the art). */
@Composable
internal fun MusicV3TileMore(label: String, onClick: () -> Unit) {
  val ink = LocalMusicStageInk.current
  MusicV3RoundButton(label, size = MusicStageGeometry.MIN_TARGET.dp, fill = ink.card, onClick = onClick) {
    MusicV3GlyphIcon(MusicV3Glyph.MORE, ink.ink, Modifier.size(28.dp))
  }
}
