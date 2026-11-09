/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Up next tab: Now playing, then Up next (drag grip to reorder, ⋯ for Play next / Remove). */
@Composable
internal fun MusicV3QueuePane(queue: YtQueue, onMove: (() -> Unit)?, onOpenLibrary: () -> Unit) {
  if (queue.isEmpty) {
    Column {
      MusicStageHeading("Up next", Modifier.padding(top = 12.dp))
      MusicStageEmpty("Nothing is queued. Pick a playlist in Library, or search for a song.",
          action = MusicV3StageCopy.OPEN_LIBRARY to onOpenLibrary)
    }
    return
  }
  val view = remember(queue) { MusicV3QueueView.from(queue) }
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
    item(key = "up-next", contentType = MusicV3RowKeys.HEADER) { UpNextHeader(view) }
    if (view.upNext.isEmpty()) {
      item(key = "nothing-next", contentType = MusicV3RowKeys.NOTE) {
        MusicStageEmpty("Nothing after this song. Add more from Library or Search.")
      }
    }
    items(view.upNext, key = { it.second.id }, contentType = { MusicV3RowKeys.TRACK }) { (index, entry) ->
      ReorderableItem(reorder, key = entry.id) { _ ->
        val row = remember(entry) { entry.toRow() }
        val menu = remember(entry.id, index, queue.currentIndex) { queueActions(queue.currentIndex, index, entry) }
        val tap = remember(entry.id) { { YouTubeMusicEngine.select(entry.id) } }
        MusicV3TrackRow(row, onPlay = tap, actions = menu, leading = { DragHandle() })
      }
    }
    item(key = "bottom", contentType = MusicV3RowKeys.NOTE) { Spacer(Modifier.height(40.dp)) }
  }
}

private fun LazyListScope.nowPlayingItems(view: MusicV3QueueView, onMove: (() -> Unit)?) {
  val current = view.current ?: return
  val actions = listOfNotNull(onMove?.let { MusicV3RowAction(MusicV3MoveControls.MOVE_LABEL, run = it) })
  item(key = "now-label", contentType = MusicV3RowKeys.HEADER) { MusicStageHeading("Now playing", Modifier.padding(top = 8.dp)) }
  item(key = "now-row", contentType = MusicV3RowKeys.TRACK) {
    MusicV3TrackRow(remember(current) { current.toRow(current = true) }, onPlay = null, actions = actions)
  }
}

@Composable
private fun UpNextHeader(view: MusicV3QueueView) {
  MusicStageHeading("Up next", Modifier.padding(top = 28.dp),
      detail = listOfNotNull(view.sourceLabel.takeIf { it.isNotBlank() }?.let { "From $it" },
          MusicV3Rules.upNextCount(view.upNext.size)).joinToString(" · "))
}

@Composable
private fun ReorderableCollectionItemScope.DragHandle() {
  Box(Modifier.size(64.dp, 72.dp).draggableHandle().semantics { contentDescription = "Drag to reorder" },
      contentAlignment = Alignment.Center) {
    MusicV3GlyphIcon(MusicV3Glyph.GRIP, ApprovedHomePalette.Secondary, Modifier.size(30.dp))
  }
}

private fun queueActions(currentIndex: Int, index: Int, entry: YtQueueEntry): List<MusicV3RowAction> {
  val nextSlot = currentIndex + 1
  val playNext = MusicV3RowAction("Play next") { if (index != nextSlot) YouTubeMusicEngine.move(index, nextSlot) }
  return listOf(playNext, MusicV3RowAction("Remove from queue", danger = true) { YouTubeMusicEngine.remove(entry.id) })
}

private fun YtQueueEntry.toRow(current: Boolean = false) = MusicV3RowModel(
    key = "q$id", title = track.title, artist = sharedBy?.let { "${track.artist} · From $it".removePrefix(" · ") } ?: track.artist, thumbnailUrl = track.thumbnailUrl,
    durationMs = track.durationMs, current = current, unplayableReason = unplayableReason)
