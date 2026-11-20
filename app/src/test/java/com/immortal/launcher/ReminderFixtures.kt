/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.TimeZone

/** Shared reminders test data: Maya (adult), Arjun (adult), Leela (kid), a household reminder. */
internal object ReminderFixtures {
  val zone: TimeZone = TimeZone.getTimeZone("Europe/London")
  /** 2027-01-15 10:00 London (winter, UTC). */
  const val NOW = 1_800_007_200_000L
  val maya = WhosTherePerson("mayaid000001", "Maya", WhosThereRole.ADULT, 0, 1L)
  val arjun = WhosTherePerson("arjunid00001", "Arjun", WhosThereRole.ADULT, 1, 1L)
  val leela = WhosTherePerson("leelaid00001", "Leela", WhosThereRole.KID, 3, 1L)
  val people = listOf(maya, arjun, leela)

  fun reminder(id: String, title: String, person: WhosTherePerson? = null, private: Boolean = false, due: Long? = null,
               done: Boolean = false, seq: Long = 1L, by: String = "kitchen", deleted: Boolean = false,
               created: Long = NOW - 60_000): Reminder =
      Reminder(id, title, person?.id, person?.name.orEmpty(), due, done, private, "Kitchen", created, seq, by, deleted,
          if (done) NOW - 1_000 else 0L)

  val clinic = reminder("rclinic01", "Call Dr. Rao's clinic", maya, private = true, due = NOW + 3_600_000)
  val gift = reminder("rgift0001", "Order Arjun's gift", maya, private = true)
  val milk = reminder("rmilk0001", "Pick up milk", maya)
  val bill = reminder("rbill0001", "Pay electricity bill", arjun, private = true)
  val bins = reminder("rbins0001", "Bins out", due = NOW + 7_200_000)
  val swim = reminder("rswim0001", "Swim bag", leela)

  val state = ReminderState(listOf(clinic, gift, milk, bill, bins, swim).associateBy { it.id })
}
