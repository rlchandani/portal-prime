/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

internal data class MusicRoomPresentation(
    val selected: Boolean,
    val status: String,
    val selectionLabel: String,
    val groupLabel: String?,
    val groupUnavailableReason: String?,
)

/** Describes the confirmed observation; command eligibility remains in MusicRoomPolicy. */
internal fun musicRoomPresentation(
    snapshot: MusicPlayerSnapshot,
    room: MusicRoom,
): MusicRoomPresentation {
  val actions = musicRoomActions(snapshot, room)
  // sourceId retains the selected player even when controlPlayerId is its group leader.
  val selected = room.id.isNotBlank() && room.id == snapshot.sourceId
  val anchorId = snapshot.controlPlayerId ?: snapshot.sourceId
  val anchor = snapshot.rooms.firstOrNull { it.id == anchorId }
  val status = when {
    !room.available -> "Offline"
    snapshot.connection == MusicConnection.OFFLINE -> "Last known state"
    snapshot.connection != MusicConnection.READY -> "Waiting for music"
    actions.inCurrentGroup && room.id != anchorId -> "In current group"
    !room.groupId.isNullOrBlank() -> "In a group"
    else -> "Available"
  }
  val groupLabel = when {
    actions.canRemove -> "Remove"
    actions.canJoin -> "Join group"
    else -> null
  }
  val reason = when {
    groupLabel != null -> null
    !room.available -> "Grouping unavailable while this player is offline."
    snapshot.connection == MusicConnection.OFFLINE -> "Reconnect to music to manage groups."
    snapshot.connection != MusicConnection.READY -> "Grouping unavailable until music is ready."
    snapshot.pending != null -> "Grouping unavailable while a change is pending."
    !snapshot.sourceId.startsWith("ma:") || snapshot.sourceId.length <= 3 ||
        !room.id.startsWith("ma:") || room.id.length <= 3 ->
      "Grouping unavailable for this player."
    anchor == null -> "Waiting for group information."
    !anchor.available -> "Grouping unavailable while the group leader is offline."
    snapshot.track != null && snapshot.track.sourceId != snapshot.sourceId ->
      "Waiting for matching player information."
    !snapshot.capabilities.rooms -> "Group controls unavailable for the current player."
    room.id == anchorId -> "Group controls apply to other players."
    actions.inCurrentGroup && !room.removableFromGroup ->
      "This player cannot be removed from the group."
    !actions.inCurrentGroup && room.id !in anchor.canGroupWith ->
      "Cannot group with the current player."
    snapshot.audioOwnedByOther -> "Joining is unavailable during a playback interruption."
    else -> "Group controls unavailable."
  }
  return MusicRoomPresentation(
      selected = selected,
      status = if (!selected) status else if (status == "Available") "Selected player"
          else "Selected player · $status",
      selectionLabel = if (selected) "Selected" else "Use player",
      groupLabel = groupLabel,
      groupUnavailableReason = reason,
  )
}
