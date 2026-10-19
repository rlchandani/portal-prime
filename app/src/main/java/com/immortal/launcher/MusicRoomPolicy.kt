/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

internal data class MusicRoomActions(
    val canSelect: Boolean, val inCurrentGroup: Boolean,
    val canJoin: Boolean, val canRemove: Boolean,
)

/** Selection needs an available player; grouping additionally needs a confirmed leader/capability. */
internal fun musicRoomActions(snapshot: MusicPlayerSnapshot, room: MusicRoom): MusicRoomActions {
  fun canonical(id: String) = id.startsWith("ma:") && id.length > 3
  val mutable = snapshot.connection == MusicConnection.READY &&
      canonical(snapshot.sourceId) && canonical(room.id) && room.available &&
      snapshot.pending == null
  val anchorId = snapshot.controlPlayerId ?: snapshot.sourceId
  val anchor = snapshot.rooms.firstOrNull { it.id == anchorId }
  val grouped = anchor != null && (room.id == anchor.id || room.groupId == anchor.id)
  val groupMutable = mutable && snapshot.can(MusicCommand.CHANGE_ROOMS) && anchor != null &&
      anchor.available && room.id != anchor.id
  return MusicRoomActions(canSelect = mutable, inCurrentGroup = grouped,
      // Joining can start a room's output; removal and source selection do not start playback.
      canJoin = groupMutable && !snapshot.audioOwnedByOther && !grouped && room.id in anchor!!.canGroupWith,
      canRemove = groupMutable && grouped && room.removableFromGroup)
}
