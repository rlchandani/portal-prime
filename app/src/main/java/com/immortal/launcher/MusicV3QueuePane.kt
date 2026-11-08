/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Queue tab: Now playing, then Up next (drag grip to reorder, ⋯ for Play next / Remove). */
@Composable
internal fun MusicV3QueuePane(queue: YtQueue, onMove: (() -> Unit)?, onOpenLibrary: () -> Unit) {
  if (queue.isEmpty) {
    MusicV3Empty("notes", MusicV3Message("Your queue is empty",
        "Songs you play from Library or Search line up here."), action = "Open Library" to onOpenLibrary)
    return
  }
  val view = MusicV3QueueView.from(queue)
  val listState = rememberLazyListState()
  val reorder = rememberReorderableLazyListState(listState) { from, to ->
    // Read the live queue: this lambda can outlive the composition that created it.
    val live = YouTubeMusicEngine.state.value.queue
    val fromIndex = live.entries.indexOfFirst { it.id == from.key }
    val toIndex = live.entries.indexOfFirst { it.id == to.key }
    if (fromIndex > live.currentIndex && toIndex > live.currentIndex) YouTubeMusicEngine.move(fromIndex, toIndex)
  }
  LazyColumn(Modifier.fillMaxSize(), state = listState) {
    nowPlayingItems(view, onMove)
    item(key = "up-next") { UpNextHeader(view) }
    if (view.upNext.isEmpty()) {
      item(key = "nothing-next") {
        Text("Nothing after this song. Add more from Library or Search.", color = ApprovedHomePalette.Secondary,
            fontSize = 23.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp))
      }
    }
    items(view.upNext, key = { it.second.id }) { (index, entry) ->
      ReorderableItem(reorder, key = entry.id) { _ ->
        MusicV3TrackRow(entry.toRow(), onPlay = { YouTubeMusicEngine.select(entry.id) },
            actions = queueActions(queue, index, entry), leading = { DragHandle() })
      }
    }
    item(key = "bottom") { Spacer(Modifier.height(40.dp)) }
  }
}

private fun LazyListScope.nowPlayingItems(view: MusicV3QueueView, onMove: (() -> Unit)?) {
  val current = view.current ?: return
  val actions = listOfNotNull(onMove?.let { MusicV3RowAction(MusicV3MoveControls.MOVE_LABEL, it) })
  item(key = "now-label") { MusicV3SectionLabel("Now playing") }
  item(key = "now-row") { MusicV3TrackRow(current.toRow(current = true), onPlay = null, actions = actions) }
}

@Composable
private fun UpNextHeader(view: MusicV3QueueView) {
  Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Up next", color = ApprovedHomePalette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    if (view.sourceLabel.isNotBlank()) {
      Text("from ${view.sourceLabel}", color = ApprovedHomePalette.Secondary, fontSize = 22.sp, maxLines = 1,
          modifier = Modifier.weight(1f, fill = false))
    }
    Spacer(Modifier.weight(1f))
    Text(MusicV3Rules.upNextCount(view.upNext.size), color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
  }
}

@Composable
private fun ReorderableCollectionItemScope.DragHandle() {
  Box(Modifier.size(64.dp, 72.dp).draggableHandle().semantics { contentDescription = "Drag to reorder" },
      contentAlignment = Alignment.Center) {
    MusicV3GlyphIcon(MusicV3Glyph.GRIP, ApprovedHomePalette.Secondary, Modifier.size(30.dp))
  }
}

private fun queueActions(queue: YtQueue, index: Int, entry: YtQueueEntry): List<MusicV3RowAction> {
  val nextSlot = queue.currentIndex + 1
  val playNext = MusicV3RowAction("Play next") { if (index != nextSlot) YouTubeMusicEngine.move(index, nextSlot) }
  return listOf(playNext, MusicV3RowAction("Remove") { YouTubeMusicEngine.remove(entry.id) })
}

private fun YtQueueEntry.toRow(current: Boolean = false) = MusicV3RowModel(
    key = "q$id", title = track.title, artist = track.artist, thumbnailUrl = track.thumbnailUrl,
    durationMs = track.durationMs, current = current, unplayableReason = unplayableReason)
