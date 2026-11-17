/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Shared test data: two kids, a few chores, and Monday 16 Nov 2026 as epoch day [MONDAY]. */
internal object ChoreFixtures {
  const val MONDAY = 20773
  const val LIVING = "living-room"
  const val KITCHEN = "kitchen"

  val mia = ChoreKid("kidmia", "Mia", "🦊", 0, goal = 6, reward = "Movie night", seq = 1, by = LIVING)
  val leo = ChoreKid("kidleo", "Leo", "🐻", 3, goal = 0, reward = "", seq = 1, by = LIVING)

  val bed = Chore("chbed", "Make bed", "bed", "kidmia", 2, ChoreSchedule.Daily, needsOk = false, seq = 1, by = LIVING)
  val toys = Chore("chtoys", "Tidy toys", "toys", null, 3, ChoreSchedule.Weekdays(setOf(1, 3, 5)), needsOk = false, seq = 1, by = LIVING)
  val bath = Chore("chbath", "Bath", "bath", "kidleo", 1, ChoreSchedule.Once(MONDAY + 1), needsOk = true, seq = 1, by = LIVING)

  val state = ChoreState(
      kids = listOf(mia, leo).associateBy { it.id },
      chores = listOf(bed, toys, bath).associateBy { it.id },
  )

  fun ok(r: ChoreResult): ChoreState = (r as? ChoreResult.Ok)?.state ?: error("expected Ok, got $r")

  fun refused(r: ChoreResult): String = (r as? ChoreResult.Refused)?.code ?: error("expected Refused, got $r")

  /** Ticks [chore] for [kid] on [day] with a fixed id. */
  fun tick(s: ChoreState, chore: String, kid: String, day: Int, id: String, by: String = LIVING): ChoreState =
      ok(ChoreOps.tick(s, chore, kid, day, id, by))
}
