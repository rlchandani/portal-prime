/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Bind native controls to their immutable rendered observation, as the remote protocol does. */
internal fun musicRenderedActionIsCurrent(
    rendered: MusicPlayerSnapshot,
    current: MusicPlayerSnapshot,
    revision: Long,
    action: MusicPlayerUiAction,
): Boolean {
  if (action == MusicPlayerUiAction.OpenSettings) return true
  if (rendered.presentationRevision != revision ||
      current.presentationRevision != revision ||
      rendered.sourceId.isBlank() || rendered.sourceId != current.sourceId) return false
  if (rendered.sourceId.startsWith("local:") &&
      rendered.localSessionToken != current.localSessionToken) return false
  val trackBound = when (action) {
    MusicPlayerUiAction.TogglePlayback, MusicPlayerUiAction.Next, MusicPlayerUiAction.Previous,
    is MusicPlayerUiAction.Seek, is MusicPlayerUiAction.SelectQueueItem -> true
    else -> false
  }
  if (trackBound && (rendered.track?.id.isNullOrBlank() ||
      rendered.track?.id != current.track?.id)) return false
  // A displayed Pause must never become Play after an external playback-state update.
  if (action == MusicPlayerUiAction.TogglePlayback && rendered.playback != current.playback)
    return false
  if (action is MusicPlayerUiAction.ToggleRoom) {
    if (rendered.controlPlayerId != current.controlPlayerId) return false
    val before = rendered.rooms.firstOrNull { it.id == action.roomId } ?: return false
    val after = current.rooms.firstOrNull { it.id == action.roomId } ?: return false
    val oldPolicy = musicRoomActions(rendered, before)
    val newPolicy = musicRoomActions(current, after)
    if (oldPolicy.canJoin != newPolicy.canJoin || oldPolicy.canRemove != newPolicy.canRemove)
      return false
  }
  return true
}
