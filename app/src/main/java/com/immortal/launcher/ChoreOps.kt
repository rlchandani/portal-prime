/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** What an edit did: a NEW state, or a refusal with a route error code. */
internal sealed interface ChoreResult {
  data class Ok(val state: ChoreState, val tick: ChoreTick? = null) : ChoreResult
  data class Refused(val code: String) : ChoreResult
}

/**
 * Pure edits of a [ChoreState]. Every change bumps the entry's seq past both this replica's copy
 * and [baseSeq] (the newest copy the phone saw on any Portal), so it wins the household merge.
 * Inputs are already validated by [ChoreRequests] (phone) or come from the widget.
 */
internal object ChoreOps {
  fun saveKid(s: ChoreState, kid: ChoreKid, by: String, baseSeq: Long = 0L): ChoreResult {
    val old = s.kids[kid.id]
    if ((old == null || old.deleted) && s.liveKids.size >= ChoreLimits.MAX_KIDS) return ChoreResult.Refused("too_many_kids")
    val next = kid.copy(seq = nextSeq(old?.seq, baseSeq), by = by, deleted = false)
    return ChoreResult.Ok(s.copy(kids = s.kids + (kid.id to next)))
  }

  /** Removes a kid and the chores that were only theirs ("anyone" chores stay). */
  fun deleteKid(s: ChoreState, id: String, by: String): ChoreResult {
    val old = s.kids[id]?.takeUnless { it.deleted } ?: return ChoreResult.Refused("unknown_kid")
    val chores = s.chores.mapValues { (_, c) ->
      if (c.kid == id && !c.deleted) c.copy(seq = c.seq + 1, by = by, deleted = true) else c
    }
    return ChoreResult.Ok(s.copy(kids = s.kids + (id to old.copy(seq = old.seq + 1, by = by, deleted = true)), chores = chores))
  }

  fun saveChore(s: ChoreState, chore: Chore, by: String, baseSeq: Long = 0L): ChoreResult {
    val old = s.chores[chore.id]
    if ((old == null || old.deleted) && s.liveChores.size >= ChoreLimits.MAX_CHORES) return ChoreResult.Refused("too_many_chores")
    if (chore.kid != null && s.kids[chore.kid]?.deleted != false) return ChoreResult.Refused("unknown_kid")
    val next = chore.copy(seq = nextSeq(old?.seq, baseSeq), by = by, deleted = false)
    return ChoreResult.Ok(s.copy(chores = s.chores + (chore.id to next)))
  }

  fun deleteChore(s: ChoreState, id: String, by: String): ChoreResult {
    val old = s.chores[id]?.takeUnless { it.deleted } ?: return ChoreResult.Refused("unknown_chore")
    return ChoreResult.Ok(s.copy(chores = s.chores + (id to old.copy(seq = old.seq + 1, by = by, deleted = true))))
  }

  /**
   * [kid] did [choreId] on [day]. Awards the chore's stars now, or holds them for a parent's OK.
   * Refused when the chore isn't due, isn't theirs, or is already done that day.
   */
  fun tick(s: ChoreState, choreId: String, kid: String, day: Int, tickId: String, by: String): ChoreResult {
    val chore = s.chores[choreId]?.takeUnless { it.deleted } ?: return ChoreResult.Refused("unknown_chore")
    if (s.kids[kid]?.deleted != false) return ChoreResult.Refused("unknown_kid")
    if (chore.kid != null && chore.kid != kid) return ChoreResult.Refused("not_their_chore")
    if (!ChoreDays.isDue(chore, day)) return ChoreResult.Refused("not_today")
    if (ChoreBoard.holders(s).containsKey(ChoreBoard.slot(choreId, day))) return ChoreResult.Refused("already_done")
    if (s.ticks.containsKey(tickId)) return ChoreResult.Refused("duplicate_tick")
    val status = if (chore.needsOk) TickStatus.PENDING else TickStatus.AWARDED
    val tick = ChoreTick(tickId, choreId, kid, day, chore.stars, status, seq = 1L, by = by)
    return ChoreResult.Ok(s.copy(ticks = s.ticks + (tickId to tick)), tick)
  }

  /** Untick (a mis-tap on the Portal, or "Take back" on the phone). A parent-approved tick stays unless [parent]. */
  fun undo(s: ChoreState, tickId: String, by: String, parent: Boolean = false): ChoreResult {
    val t = s.ticks[tickId] ?: return ChoreResult.Refused("unknown_tick")
    val allowed = t.status == TickStatus.AWARDED || t.status == TickStatus.PENDING || (parent && t.status == TickStatus.APPROVED)
    return if (!allowed) ChoreResult.Refused("invalid_state") else move(s, t, TickStatus.UNDONE, by)
  }

  fun approve(s: ChoreState, tickId: String, by: String): ChoreResult = decide(s, tickId, TickStatus.APPROVED, by)

  fun decline(s: ChoreState, tickId: String, by: String): ChoreResult = decide(s, tickId, TickStatus.DECLINED, by)

  /**
   * Bounds the replica: ticks before [ChoreDays.keepFrom] go (the weekly history needs no more),
   * then the oldest ticks beyond [ChoreLimits.MAX_TICKS], and the oldest tombstones beyond
   * [ChoreLimits.MAX_TOMBSTONES]. Deterministic, so replicas with the same clock day agree.
   */
  fun prune(s: ChoreState, today: Int): ChoreState {
    val from = ChoreDays.keepFrom(today)
    val ticks = s.ticks.values.filter { it.day >= from }
        .sortedWith(compareByDescending<ChoreTick> { it.day }.thenBy { it.id }).take(ChoreLimits.MAX_TICKS)
    val kids = keepTombstones(s.kids.values, { it.deleted }, { it.seq }, { it.id })
    val chores = keepTombstones(s.chores.values, { it.deleted }, { it.seq }, { it.id })
    val next = ChoreState(kids.associateBy { it.id }, chores.associateBy { it.id }, ticks.associateBy { it.id })
    return if (next == s) s else next
  }

  private fun decide(s: ChoreState, tickId: String, to: TickStatus, by: String): ChoreResult {
    val t = s.ticks[tickId] ?: return ChoreResult.Refused("unknown_tick")
    return if (t.status != TickStatus.PENDING) ChoreResult.Refused("invalid_state") else move(s, t, to, by)
  }

  private fun move(s: ChoreState, t: ChoreTick, to: TickStatus, by: String): ChoreResult {
    val next = t.copy(status = to, seq = t.seq + 1, by = by)
    return ChoreResult.Ok(s.copy(ticks = s.ticks + (t.id to next)), next)
  }

  private fun nextSeq(local: Long?, base: Long): Long = maxOf(local ?: 0L, base) + 1

  private fun <T> keepTombstones(all: Collection<T>, deleted: (T) -> Boolean, seq: (T) -> Long, id: (T) -> String): List<T> {
    val (dead, live) = all.partition(deleted)
    return live + dead.sortedWith(compareByDescending(seq).thenBy(id)).take(ChoreLimits.MAX_TOMBSTONES)
  }
}
