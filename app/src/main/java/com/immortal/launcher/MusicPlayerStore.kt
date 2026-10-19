/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** A command reply is an acknowledgement; these predicates confirm observed state. */
sealed interface MusicConfirmation {
  fun matches(snapshot: MusicPlayerSnapshot): Boolean

  data class Playback(val expected: PlaybackState) : MusicConfirmation {
    override fun matches(snapshot: MusicPlayerSnapshot) = snapshot.playback == expected
  }

  data class TrackChanged(val previousId: String) : MusicConfirmation {
    override fun matches(snapshot: MusicPlayerSnapshot) =
        snapshot.track?.id?.let { it.isNotBlank() && it != previousId } == true
  }

  /** Previous commonly restarts the same track after five seconds, without changing its ID. */
  data class TrackOrRestart(val previousId: String, val priorPositionMs: Long) : MusicConfirmation {
    override fun matches(snapshot: MusicPlayerSnapshot): Boolean {
      val track = snapshot.track ?: return false
      return track.id.isNotBlank() && (track.id != previousId ||
          (priorPositionMs >= 5_000 && snapshot.positionMs in 0..2_000))
    }
  }

  data class TrackSelected(val id: String) : MusicConfirmation {
    override fun matches(snapshot: MusicPlayerSnapshot) = snapshot.track?.id == id
  }

  data class Seek(val trackId: String, val positionMs: Long) : MusicConfirmation {
    init { require(positionMs >= 0) }
    override fun matches(snapshot: MusicPlayerSnapshot) =
        snapshot.track?.id == trackId && snapshot.positionMs >= 0 &&
            abs(snapshot.positionMs - positionMs) <= 2_000
  }

  data class Group(val groupId: String, val memberIds: Set<String>) : MusicConfirmation {
    init { require(groupId.isNotBlank() && memberIds.isNotEmpty()) }
    override fun matches(snapshot: MusicPlayerSnapshot): Boolean {
      val leader = snapshot.rooms.firstOrNull { it.id == groupId } ?: return false
      if (leader.groupId != null && leader.groupId != groupId) return false
      return snapshot.rooms.filter { it.id == groupId || it.groupId == groupId }
          .map { it.id }.toSet() == memberIds
    }
  }

  data class Volume(val roomId: String, val percent: Int) : MusicConfirmation {
    init { require(roomId.isNotBlank() && percent in 0..100) }
    override fun matches(snapshot: MusicPlayerSnapshot) =
        snapshot.rooms.any { it.id == roomId && it.volume == percent }
  }
}

/**
 * One state owner for all presentations. Source generations also reject late results
 * when a user switches away and back to the same source while a request is in flight.
 */
class MusicPlayerStore {
  private val mutableState = MutableStateFlow(MusicPlayerSnapshot())
  val state: StateFlow<MusicPlayerSnapshot> = mutableState.asStateFlow()
  val current: MusicPlayerSnapshot get() = mutableState.value
  private var generation = 0L
  private var confirmation: MusicConfirmation? = null

  @Synchronized fun selectSource(sourceId: String, presentationRevision: Long = 0): Long {
    generation += 1
    confirmation = null
    mutableState.value = MusicPlayerSnapshot(
        connection = if (sourceId.isBlank()) MusicConnection.NOT_CONFIGURED else MusicConnection.CONNECTING,
        sourceId = sourceId, presentationRevision = presentationRevision)
    return generation
  }

  @Synchronized fun publish(sourceGeneration: Long, next: MusicPlayerSnapshot): Boolean {
    if (sourceGeneration != generation || next.sourceId != current.sourceId) return false
    if (next.track != null && next.track.sourceId != next.sourceId) return false
    val normalized = next.copy(
        queue = next.queue.asSequence().take(500).filter { it.id.isNotBlank() }
            .distinctBy { it.id }.toList(),
        lyrics = next.lyrics.take(1_000).filter { it.timeMs >= 0 && it.text.isNotBlank() }
            .sortedBy { it.timeMs }.distinctBy { it.timeMs }.toList(),
        rooms = next.rooms.asSequence().take(64).filter { it.id.isNotBlank() }
            .distinctBy { it.id }.map { it.copy(canGroupWith = it.canGroupWith.take(64).toSet()) }.toList(),
        pending = null)
    val pending = current.pending
    val observed = normalized.connection == MusicConnection.READY && confirmation?.matches(normalized) == true
    val disconnected = normalized.connection != MusicConnection.READY
    if (observed || disconnected) confirmation = null
    mutableState.value = normalized.copy(
        pending = pending.takeUnless { observed || disconnected },
        problem = normalized.problem ?: if (disconnected && pending != null) "Player disconnected before confirming the change." else null)
    return true
  }

  @Synchronized fun beginCommand(
      requestId: Long, command: MusicCommand, startedAtElapsedMs: Long,
      confirmation: MusicConfirmation,
  ): Boolean {
    if (current.pending != null || !current.can(command)) return false
    this.confirmation = confirmation
    mutableState.value = current.copy(
        pending = PendingMusicCommand(requestId, current.sourceId, command, startedAtElapsedMs),
        problem = null)
    return true
  }

  @Synchronized fun acknowledge(sourceGeneration: Long, requestId: Long): Boolean =
      sourceGeneration == generation && current.pending?.confirmedBy(requestId, current.sourceId) == true

  @Synchronized fun reject(sourceGeneration: Long, requestId: Long, problem: String): Boolean {
    if (!acknowledge(sourceGeneration, requestId)) return false
    confirmation = null
    mutableState.value = current.copy(pending = null, problem = problem)
    return true
  }

  @Synchronized fun expirePending(nowElapsedMs: Long): Boolean {
    val pending = current.pending ?: return false
    if (nowElapsedMs < pending.startedAtElapsedMs ||
        nowElapsedMs - pending.startedAtElapsedMs < 5_000) return false
    confirmation = null
    mutableState.value = current.copy(
        pending = null, problem = "The player did not confirm the change. Please try again.")
    return true
  }
}
