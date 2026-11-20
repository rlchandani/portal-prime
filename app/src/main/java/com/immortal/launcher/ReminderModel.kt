/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Personal reminders: the household-wide replica every Portal holds (synced like chores, see
 * [ReminderSync]). Each reminder belongs to one enrolled Who's there? person or to the household.
 *
 * Who's there? people are enrolled per Portal, so the same person can have a different id on
 * another Portal. A reminder therefore carries both the id it was made with ([person]) and the
 * name at that time ([personName]); [ReminderVisibility.belongsTo] matches by id first, then by
 * name when this Portal doesn't know the id.
 *
 * Last writer wins per reminder by ([seq], deleted, [by]); deleting keeps a tombstone so an older
 * copy can't bring it back. Private ones never leave the Portal except sealed for other Portals.
 */
internal data class Reminder(
    val id: String,
    val title: String,
    /** The Who's there? person id, or null for the household. */
    val person: String?,
    /** That person's name when it was set ("" for the household). */
    val personName: String,
    /** Epoch ms it is due, or null for "whenever". */
    val dueMs: Long?,
    val done: Boolean,
    /** Shown only on that person's own Home while they're alone. Never true for the household. */
    val private: Boolean,
    /** Who made it, for display ("Kitchen", "Phone", "Jarvis", a person's name). */
    val createdBy: String,
    val createdMs: Long,
    val seq: Long,
    /** The device that wrote this version. */
    val by: String,
    val deleted: Boolean = false,
    /** When it was ticked off or deleted (0 while open); drives pruning. */
    val doneMs: Long = 0L,
) {
  val household: Boolean get() = person == null
  val open: Boolean get() = !deleted && !done
}

internal data class ReminderState(val reminders: Map<String, Reminder> = emptyMap()) {
  val live: List<Reminder> get() = reminders.values.filterNot { it.deleted }
}

internal object ReminderLimits {
  const val MAX_LIVE = 200
  const val TITLE_MAX = 60
  const val NAME_MAX = WhosThereLimits.MAX_NAME
  const val CREATOR_MAX = 24
  /** Done reminders stay visible (and undoable) this long, then become tombstones. */
  const val DONE_KEEP_MS = 3L * 24 * 60 * 60_000
  /** Tombstones are forgotten after this long, or beyond [MAX_TOMBSTONES]. */
  const val TOMBSTONE_KEEP_MS = 45L * 24 * 60 * 60_000
  const val MAX_TOMBSTONES = 300
  /** Due times further than this from now are junk (≈ 2 years). */
  const val MAX_DUE_AHEAD_MS = 2L * 366 * 24 * 60 * 60_000
  const val PHONE = "Phone"
  const val JARVIS = "Jarvis"
}

/** A refusal carries the route error code; an Ok carries the NEW state and the reminder it touched. */
internal sealed interface ReminderResult {
  data class Ok(val state: ReminderState, val reminder: Reminder) : ReminderResult
  data class Refused(val code: String) : ReminderResult
}

/** What a new or edited reminder should say; validated by [ReminderOps]. */
internal data class ReminderDraft(
    val title: String,
    val person: String?,
    val personName: String,
    val dueMs: Long?,
    val private: Boolean,
)
