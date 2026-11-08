/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** The source Portal's "Move to…" state. Immutable; transitions live in [MusicMoveMachine]. */
internal sealed interface MusicMoveState {
  data object Idle : MusicMoveState

  /** Offer [id] is out to [targetId]; [fetched] once it has taken the queue. Music keeps playing here. */
  data class Offering(
      val id: String,
      val targetId: String,
      val targetName: String,
      val startedMs: Long,
      val fetched: Boolean = false,
  ) : MusicMoveState

  /** The music plays in [targetName]; it's paused here. */
  data class Handed(val targetId: String, val targetName: String) : MusicMoveState

  /**
   * "Bring it back" ([id]) is out to [targetId]. [follow]: follow-me pulled music this room never
   * handed away, so giving up returns to [Idle] rather than "Playing in …".
   */
  data class Recalling(
      val id: String,
      val targetId: String,
      val targetName: String,
      val startedMs: Long,
      val follow: Boolean = false,
  ) : MusicMoveState
}

/** One transition: the next state, whether to pause local playback, and a toast to show. */
internal data class MusicMoveStep(val state: MusicMoveState, val pauseLocal: Boolean = false, val toast: String? = null)

/** Result of a queue fetch arriving at the source: [allowed] serves the queue exactly once. */
internal data class MusicMoveClaim(val state: MusicMoveState, val allowed: Boolean)

/**
 * Pure source-side rules. Music only pauses here after the target acks [MusicHandoffStatus.PLAYING];
 * every decline, failure or timeout leaves it playing and says why.
 */
internal object MusicMoveMachine {
  /** The target must fetch the queue within this window (it's the offer's lifetime). */
  const val FETCH_MS = 15_000L
  /** Overall wait for "playing": wake + open + player load on the target. */
  const val ANSWER_MS = 45_000L

  fun start(s: MusicMoveState, id: String, targetId: String, targetName: String, nowMs: Long): MusicMoveStep =
      if (s is MusicMoveState.Idle) MusicMoveStep(MusicMoveState.Offering(id, targetId, targetName, nowMs))
      else MusicMoveStep(s)

  /** The target ([caller]) asks for offer [offerId]'s queue: once, from the named room, while fresh. */
  fun claim(s: MusicMoveState, offerId: String, caller: String, nowMs: Long): MusicMoveClaim {
    val ok = s is MusicMoveState.Offering && s.id == offerId && s.targetId == caller && !s.fetched &&
        nowMs - s.startedMs < FETCH_MS
    return if (ok) MusicMoveClaim((s as MusicMoveState.Offering).copy(fetched = true), true) else MusicMoveClaim(s, false)
  }

  fun ack(s: MusicMoveState, from: String, ack: MusicHandoffWire.Ack): MusicMoveStep = when {
    s is MusicMoveState.Offering && s.id == ack.id && s.targetId == from -> offerAnswered(s, ack.status)
    s is MusicMoveState.Recalling && s.id == ack.id && s.targetId == from -> recallAnswered(s, ack.status)
    else -> MusicMoveStep(s)
  }

  fun tick(s: MusicMoveState, nowMs: Long): MusicMoveStep = when {
    s is MusicMoveState.Offering && expired(s, nowMs) -> MusicMoveStep(MusicMoveState.Idle,
        toast = "${s.targetName} didn't answer. The music is still playing here.")
    s is MusicMoveState.Recalling && nowMs - s.startedMs >= ANSWER_MS ->
      MusicMoveStep(gaveUp(s), toast = "${s.targetName} didn't answer.")
    else -> MusicMoveStep(s)
  }

  fun recall(s: MusicMoveState, id: String, nowMs: Long): MusicMoveStep =
      if (s is MusicMoveState.Handed) MusicMoveStep(MusicMoveState.Recalling(id, s.targetId, s.targetName, nowMs))
      else MusicMoveStep(s)

  /**
   * Follow-me: pull the music playing in [sourceId] here through the "Bring it back" path. From
   * [MusicMoveState.Idle] it's a fresh pull; if our own music was handed to that room it's a plain recall.
   */
  fun follow(s: MusicMoveState, id: String, sourceId: String, sourceName: String, nowMs: Long): MusicMoveStep = when {
    s is MusicMoveState.Idle -> MusicMoveStep(MusicMoveState.Recalling(id, sourceId, sourceName, nowMs, follow = true))
    s is MusicMoveState.Handed && s.targetId == sourceId -> recall(s, id, nowMs)
    else -> MusicMoveStep(s)
  }

  /** An incoming offer that answers our "Bring it back" (quiet hours don't block it). */
  fun isRecallOffer(s: MusicMoveState, from: String, offerId: String): Boolean =
      s is MusicMoveState.Recalling && s.targetId == from && s.id == offerId

  /** Music started playing on this Portal again: whatever was handed away is back. */
  fun playingHere(s: MusicMoveState): MusicMoveState =
      if (s is MusicMoveState.Handed || s is MusicMoveState.Recalling) MusicMoveState.Idle else s

  /** The Now Playing line for this state, or null when nothing is moving. */
  fun banner(s: MusicMoveState): String? = when (s) {
    MusicMoveState.Idle -> null
    is MusicMoveState.Offering -> if (s.fetched) "Starting in ${s.targetName}…" else "Moving to ${s.targetName}…"
    is MusicMoveState.Handed -> "Playing in ${s.targetName}"
    is MusicMoveState.Recalling ->
      if (s.follow) "Bringing the music from ${s.targetName}…" else "Bringing it back from ${s.targetName}…"
  }

  private fun expired(s: MusicMoveState.Offering, nowMs: Long): Boolean {
    val age = nowMs - s.startedMs
    return age >= ANSWER_MS || (!s.fetched && age >= FETCH_MS)
  }

  private fun offerAnswered(s: MusicMoveState.Offering, status: MusicHandoffStatus): MusicMoveStep {
    val room = s.targetName
    return when (status) {
      MusicHandoffStatus.PLAYING ->
        MusicMoveStep(MusicMoveState.Handed(s.targetId, room), pauseLocal = true, toast = "Now playing in $room")
      else -> MusicMoveStep(MusicMoveState.Idle, toast = declineCopy(room, status))
    }
  }

  private fun recallAnswered(s: MusicMoveState.Recalling, status: MusicHandoffStatus): MusicMoveStep = when (status) {
    MusicHandoffStatus.NOTHING -> MusicMoveStep(MusicMoveState.Idle, toast = "Nothing is playing in ${s.targetName}.")
    // PLAYING never answers a recall (the music comes back as an offer); anything else is a refusal.
    else -> MusicMoveStep(gaveUp(s),
        toast = if (s.follow) "${s.targetName} couldn't send the music." else "${s.targetName} couldn't send the music back.")
  }

  /** A recall that didn't work: our music is still in that room, or (follow-me) nothing changed here. */
  private fun gaveUp(s: MusicMoveState.Recalling): MusicMoveState =
      if (s.follow) MusicMoveState.Idle else MusicMoveState.Handed(s.targetId, s.targetName)

  fun declineCopy(room: String, status: MusicHandoffStatus): String = when (status) {
    MusicHandoffStatus.DECLINED_QUIET -> "$room is in quiet hours, so the music stayed here."
    MusicHandoffStatus.DECLINED_ROUTINE -> "A routine is on screen in $room, so the music stayed here."
    MusicHandoffStatus.DECLINED_CALL -> "$room is on a call, so the music stayed here."
    MusicHandoffStatus.NEEDS_TAP -> "$room needs a tap on Play to start. The music is still playing here."
    else -> "$room couldn't start the music. It's still playing here."
  }
}

/** The receiving Portal's rules. */
internal object MusicTargetPolicy {
  /** How long the target waits for its player to really play before reporting failure. */
  const val START_MS = 25_000L

  /** Null accepts; otherwise the decline. A "Bring it back" was asked for in person, so quiet hours don't apply. */
  fun decide(quiet: Boolean, routine: Boolean, inCall: Boolean, recall: Boolean): MusicHandoffStatus? = when {
    inCall -> MusicHandoffStatus.DECLINED_CALL
    routine -> MusicHandoffStatus.DECLINED_ROUTINE
    quiet && !recall -> MusicHandoffStatus.DECLINED_QUIET
    else -> null
  }

  /**
   * The outcome to report once known, else null. Playing counts only after the player has reported
   * progress for a new item ([YtEngineState.playerVideoId] is cleared when a queue is adopted) — an
   * ad counts, a late report from [previousVideoId] (what played here before) doesn't.
   */
  fun progress(s: YtEngineState, elapsedMs: Long, previousVideoId: String? = null): MusicHandoffStatus? = when {
    s.status == YtPlayerStatus.PLAYING && reportsNewItem(s, previousVideoId) -> MusicHandoffStatus.PLAYING
    s.autoplayBlocked -> MusicHandoffStatus.NEEDS_TAP
    s.halt != null -> MusicHandoffStatus.FAILED
    elapsedMs >= START_MS -> MusicHandoffStatus.FAILED
    else -> null
  }

  private fun reportsNewItem(s: YtEngineState, previousVideoId: String?): Boolean {
    val reported = s.playerVideoId ?: return false
    return reported != previousVideoId || reported == s.queue.current?.track?.videoId
  }
}

/** One row in the "Move to…" list. */
internal data class MusicMoveRoom(val id: String, val name: String, val enabled: Boolean, val note: String?)

/** The other Portals, from the dinner feature's signed `dinner_hello` roster. */
internal object MusicMoveRooms {
  /** A room that hasn't said hello this recently counts as offline. */
  const val ONLINE_MS = 3 * 60_000L

  fun from(peers: List<DinnerPeer>, selfId: String?, nowMs: Long): List<MusicMoveRoom> =
      peers.filter { it.id != selfId }.sortedBy { it.name.lowercase() }.map { p ->
        val online = nowMs - p.seenMs <= ONLINE_MS
        val note = when {
          !online -> "Not answering"
          p.quiet == true -> "Quiet hours"
          else -> null
        }
        MusicMoveRoom(p.id, p.name, enabled = note == null, note = note)
      }
}
