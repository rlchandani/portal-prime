/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Deterministic replica merge for [ReminderState]: per reminder, the highest (seq, deleted, by,
 * canonical text) wins. Commutative, associative and idempotent, so Portals converge however
 * exchanges interleave.
 */
internal object ReminderMerge {
  private val order = compareBy<Reminder>({ it.seq }, { it.deleted }, { it.by }, { ReminderCodec.canonical(it) })

  fun merge(a: ReminderState, b: ReminderState): ReminderState {
    if (a === b || a == b) return a
    val ids = a.reminders.keys + b.reminders.keys
    return ReminderState(ids.associateWith { id ->
      val x = a.reminders[id]
      val y = b.reminders[id]
      when {
        x == null -> y!!
        y == null -> x
        else -> maxOf(x, y, order)
      }
    })
  }
}
