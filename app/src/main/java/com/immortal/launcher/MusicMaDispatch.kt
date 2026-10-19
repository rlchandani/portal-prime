/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

internal data class MusicMaDispatch(
    val command: MusicCommand, val confirmation: MusicConfirmation,
    val action: MaMusicClient.Action,
)

/** Matching playback on a replacement leader or queue cannot confirm the original command. */
private data class MaPlaybackConfirmation(
    val sourceId: String, val target: MaMusicClient.PlaybackTarget,
    val observedChange: MusicConfirmation,
) : MusicConfirmation {
  override fun matches(snapshot: MusicPlayerSnapshot): Boolean =
      snapshot.sourceId == sourceId && snapshot.controlPlayerId == target.controlPlayerId &&
          snapshot.queueId == target.queueId && observedChange.matches(snapshot)
}

/** Validate the UI's current selection before queueing work; the client validates again on its worker. */
internal fun musicMaDispatch(s: MusicPlayerSnapshot, action: MusicPlayerUiAction): MusicMaDispatch? {
  if (!s.sourceId.startsWith("ma:") || s.pending != null) return null
  fun dispatch(
      command: MusicCommand, confirmation: MusicConfirmation, request: MaMusicClient.Action,
  ): MusicMaDispatch? {
    if (!s.can(command)) return null
    val playback = request is MaMusicClient.Action.Transport || request is MaMusicClient.Action.Seek ||
        request is MaMusicClient.Action.QueueItem
    val target = if (playback) MaMusicClient.PlaybackTarget(
        s.controlPlayerId ?: return null, s.queueId) else null
    val ownedRequest = when (request) {
      is MaMusicClient.Action.Transport -> request.copy(expectedTarget = target)
      is MaMusicClient.Action.Seek -> request.copy(expectedTarget = target)
      is MaMusicClient.Action.QueueItem -> request.copy(expectedTarget = target)
      else -> request
    }
    return MusicMaDispatch(command,
        if (target != null) MaPlaybackConfirmation(s.sourceId, target, confirmation) else confirmation,
        ownedRequest)
  }
  return when (action) {
    MusicPlayerUiAction.TogglePlayback -> {
      val playing = s.playback == PlaybackState.PLAYING
      val command = if (playing) MusicCommand.PAUSE else MusicCommand.PLAY
      dispatch(command, MusicConfirmation.Playback(if (playing) PlaybackState.PAUSED else PlaybackState.PLAYING),
          MaMusicClient.Action.Transport(command))
    }
    MusicPlayerUiAction.Next -> {
      val track = s.track ?: return null
      dispatch(MusicCommand.NEXT, MusicConfirmation.TrackChanged(track.id),
          MaMusicClient.Action.Transport(MusicCommand.NEXT))
    }
    MusicPlayerUiAction.Previous -> {
      val track = s.track ?: return null
      dispatch(MusicCommand.PREVIOUS, MusicConfirmation.TrackOrRestart(track.id, s.positionMs),
          MaMusicClient.Action.Transport(MusicCommand.PREVIOUS))
    }
    is MusicPlayerUiAction.Seek -> {
      val track = s.track ?: return null
      val duration = track.durationMs ?: return null
      if (action.positionMs !in 0..duration) return null
      dispatch(MusicCommand.SEEK, MusicConfirmation.Seek(track.id, action.positionMs),
          MaMusicClient.Action.Seek(action.positionMs))
    }
    is MusicPlayerUiAction.SelectQueueItem -> {
      val queueId = s.queueId?.takeIf { it.isNotBlank() } ?: return null
      if (s.queue.none { it.id == action.id && it.available }) return null
      dispatch(MusicCommand.SELECT_QUEUE_ITEM, MusicConfirmation.TrackSelected(action.id, queueId),
          MaMusicClient.Action.QueueItem(action.id, expectedQueueId = queueId))
    }
    is MusicPlayerUiAction.SetRoomVolume -> {
      if (action.percent !in 0..100 || s.rooms.none {
        it.id == action.roomId && it.available && it.volumeControllable
      }) return null
      dispatch(MusicCommand.SET_VOLUME, MusicConfirmation.Volume(action.roomId, action.percent),
          MaMusicClient.Action.Volume(action.roomId, action.percent))
    }
    is MusicPlayerUiAction.ToggleRoom -> {
      val room = s.rooms.firstOrNull { it.id == action.roomId } ?: return null
      val permissions = musicRoomActions(s, room)
      val leader = s.controlPlayerId ?: return null
      val members = s.rooms.filter { it.id == leader || it.groupId == leader }.map { it.id }.toSet()
      when {
        permissions.canRemove -> dispatch(MusicCommand.CHANGE_ROOMS,
            MusicConfirmation.Group(leader, members - room.id),
            MaMusicClient.Action.Group(remove = setOf(room.id), expectedLeaderId = leader))
        permissions.canJoin -> dispatch(MusicCommand.CHANGE_ROOMS,
            MusicConfirmation.Group(leader, members + room.id),
            MaMusicClient.Action.Group(add = setOf(room.id), expectedLeaderId = leader))
        else -> null
      }
    }
    else -> null
  }
}
