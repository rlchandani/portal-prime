/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Pure edits of a [ReminderState]. Every change bumps the reminder's seq past this replica's copy
 * and [baseSeq] (the newest copy the caller saw), so it wins the household merge. Drafts are
 * validated here, whoever sends them (Portal sheet, phone, Jarvis).
 */
internal object ReminderOps {
  fun add(s: ReminderState, id: String, draft: ReminderDraft, createdBy: String, by: String, nowMs: Long): ReminderResult {
    if (s.reminders.containsKey(id)) return ReminderResult.Refused("duplicate_reminder")
    if (s.live.size >= ReminderLimits.MAX_LIVE) return ReminderResult.Refused("too_many_reminders")
    val clean = validate(draft, nowMs) ?: return ReminderResult.Refused("invalid_reminder")
    val creator = ChoreText.clean(createdBy, ReminderLimits.CREATOR_MAX).orEmpty()
    val r = Reminder(id, clean.title, clean.person, clean.personName, clean.dueMs, done = false, private = clean.private,
        createdBy = creator, createdMs = nowMs, seq = 1L, by = by)
    return ReminderResult.Ok(s.copy(reminders = s.reminders + (id to r)), r)
  }

  /** Rewrites what it says (title, owner, due, private); done and history stay. */
  fun edit(s: ReminderState, id: String, draft: ReminderDraft, by: String, nowMs: Long, baseSeq: Long = 0L): ReminderResult {
    val old = s.reminders[id]?.takeUnless { it.deleted } ?: return ReminderResult.Refused("unknown_reminder")
    val clean = validate(draft, nowMs, old.dueMs) ?: return ReminderResult.Refused("invalid_reminder")
    val next = old.copy(title = clean.title, person = clean.person, personName = clean.personName, dueMs = clean.dueMs,
        private = clean.private, seq = nextSeq(old.seq, baseSeq), by = by)
    return ReminderResult.Ok(s.copy(reminders = s.reminders + (id to next)), next)
  }

  fun setDone(s: ReminderState, id: String, done: Boolean, by: String, nowMs: Long, baseSeq: Long = 0L): ReminderResult {
    val old = s.reminders[id]?.takeUnless { it.deleted } ?: return ReminderResult.Refused("unknown_reminder")
    if (old.done == done) return ReminderResult.Ok(s, old)
    val next = old.copy(done = done, doneMs = if (done) nowMs else 0L, seq = nextSeq(old.seq, baseSeq), by = by)
    return ReminderResult.Ok(s.copy(reminders = s.reminders + (id to next)), next)
  }

  /** Tombstones it; [Reminder.doneMs] then records when, so the tombstone outlives stale copies. */
  fun delete(s: ReminderState, id: String, by: String, nowMs: Long, baseSeq: Long = 0L): ReminderResult {
    val old = s.reminders[id]?.takeUnless { it.deleted } ?: return ReminderResult.Refused("unknown_reminder")
    val next = old.copy(deleted = true, doneMs = nowMs, seq = nextSeq(old.seq, baseSeq), by = by)
    return ReminderResult.Ok(s.copy(reminders = s.reminders + (id to next)), next)
  }

  /**
   * Done reminders older than [ReminderLimits.DONE_KEEP_MS] become tombstones (so every replica
   * forgets them the same way); old tombstones are dropped, newest [ReminderLimits.MAX_TOMBSTONES] kept.
   */
  fun prune(s: ReminderState, nowMs: Long): ReminderState {
    val aged = s.reminders.mapValues { (_, r) ->
      if (!r.deleted && r.done && r.doneMs > 0 && nowMs - r.doneMs > ReminderLimits.DONE_KEEP_MS)
        r.copy(deleted = true, seq = r.seq + 1) else r
    }
    val (dead, live) = aged.values.partition { it.deleted }
    val keptDead = dead.filter { nowMs - maxOf(it.doneMs, it.createdMs) <= ReminderLimits.TOMBSTONE_KEEP_MS }
        .sortedWith(compareByDescending<Reminder> { it.seq }.thenBy { it.id }).take(ReminderLimits.MAX_TOMBSTONES)
    val next = ReminderState((live + keptDead).associateBy { it.id })
    return if (next == s) s else next
  }

  /** A cleaned draft, or null: empty title, bad person, private household, or junk due time. */
  fun validate(d: ReminderDraft, nowMs: Long, previousDue: Long? = null): ReminderDraft? {
    val title = ChoreText.clean(d.title, ReminderLimits.TITLE_MAX) ?: return null
    if (d.person != null && !ReminderCodec.isPersonId(d.person)) return null
    val name = if (d.person == null) "" else ChoreText.clean(d.personName, ReminderLimits.NAME_MAX) ?: return null
    val due = d.dueMs
    if (due != null && due != previousDue && (!ReminderCodec.isTime(due) || due - nowMs > ReminderLimits.MAX_DUE_AHEAD_MS)) return null
    return ReminderDraft(title, d.person, name, due, d.private && d.person != null)
  }

  private fun nextSeq(local: Long, base: Long): Long = maxOf(local, base) + 1
}
