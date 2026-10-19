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
  val command = when (action) {
    MusicPlayerUiAction.TogglePlayback ->
      if (current.playback == PlaybackState.PLAYING) MusicCommand.PAUSE else MusicCommand.PLAY
    MusicPlayerUiAction.Next -> MusicCommand.NEXT
    MusicPlayerUiAction.Previous -> MusicCommand.PREVIOUS
    is MusicPlayerUiAction.Seek -> MusicCommand.SEEK
    is MusicPlayerUiAction.SelectQueueItem -> MusicCommand.SELECT_QUEUE_ITEM
    else -> null
  }
  if (current.audioOwnedByOther && command?.requiresAudioStartOrReposition() == true) return false
  if (rendered.presentationRevision != revision ||
      current.presentationRevision != revision ||
      rendered.sourceId.isBlank() || rendered.sourceId != current.sourceId) return false
  if (rendered.sourceId.startsWith("local:") &&
      rendered.localSessionToken != current.localSessionToken) return false
  val trackBound = when (action) {
    MusicPlayerUiAction.TogglePlayback, MusicPlayerUiAction.Next, MusicPlayerUiAction.Previous,
    is MusicPlayerUiAction.Seek -> true
    else -> false
  }
  if (trackBound && (rendered.track?.id.isNullOrBlank() ||
      rendered.track?.id != current.track?.id)) return false
  if (action is MusicPlayerUiAction.SelectQueueItem &&
      (rendered.queueId.isNullOrBlank() || rendered.queueId != current.queueId ||
        rendered.queue.none { it.id == action.id && it.available } ||
        current.queue.none { it.id == action.id && it.available })) return false
  // A displayed Pause must never become Play after an external playback-state update.
  if (action == MusicPlayerUiAction.TogglePlayback && rendered.playback != current.playback)
    return false
  if (action is MusicPlayerUiAction.ToggleRoom) {
    if (rendered.controlPlayerId != current.controlPlayerId) return false
    val before = rendered.rooms.firstOrNull { it.id == action.roomId } ?: return false
    val after = current.rooms.firstOrNull { it.id == action.roomId } ?: return false
    val oldPolicy = musicRoomActions(rendered, before)
    val newPolicy = musicRoomActions(current, after)
    if (current.audioOwnedByOther && !newPolicy.canRemove) return false
    if (oldPolicy.canJoin != newPolicy.canJoin || oldPolicy.canRemove != newPolicy.canRemove)
      return false
  }
  return true
}
