/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** To-do migration edge cases: junk text, re-runs, clock sanity, and multi-Portal summary wording. */
class HouseholdListMigrationCoverageTest {
  private val now = 1_800_000_000_000L

  private fun todo(text: String, done: Boolean = false, created: Long = now - 5_000) =
      TodoStore.TodoItem("id-$text", text, done, created)

  private fun migrate(s: HouseholdListState, portal: String, vararg todos: TodoStore.TodoItem) =
      HouseholdListMigration.migrate(s, todos.toList(), portal, now)

  @Test fun blankTodosAreSkippedAndLongOnesAreClipped() {
    val s = migrate(HouseholdListState(), "Den", todo("   "), todo("\t\n"), todo("  Buy   milk  "), todo("x".repeat(80)))
    assertEquals(setOf("Buy milk", "x".repeat(HouseholdListText.MAX_TEXT)), s.live().map { it.text.value }.toSet())
  }

  @Test fun rerunningForTheSamePortalChangesNothing() {
    val once = migrate(HouseholdListState(), "Den", todo("Eggs"))
    assertSame(once, migrate(once, "Den", todo("eggs")))
  }

  @Test fun createdTimeOutsideTheSaneRangeFallsBackToNow() {
    val s = migrate(HouseholdListState(), "Den", todo("Future", created = now + 1), todo("Zero", created = 0), todo("Past", created = 42))
    assertEquals(now, s.liveByText("Future")!!.added.at)
    assertEquals(now, s.liveByText("Zero")!!.added.at)
    assertEquals(42L, s.liveByText("Past")!!.added.at)
    assertEquals(now, migrate(HouseholdListState(), "Den", todo("Edge", created = now)).liveByText("Edge")!!.added.at)
  }

  @Test fun doneTodosStayDone() {
    assertTrue(migrate(HouseholdListState(), "Den", todo("Stamps", done = true)).live().single().done.value)
  }

  @Test fun onePortalWithSeveralItemsUsesPlural() {
    val s = migrate(HouseholdListState(), "Den", todo("Eggs"), todo("Milk"))
    assertTrue(HouseholdListMigration.summary(s)!!.body.startsWith("Den had 2 items."))
  }

  @Test fun twoPortalsWithoutOverlapHaveNoDuplicateLine() {
    val s = migrate(migrate(HouseholdListState(), "Den", todo("Eggs")), "Attic", todo("Milk"), todo("Jam"))
    val summary = HouseholdListMigration.summary(s)!!
    assertEquals("Your two To-do lists are now one", summary.title)
    assertEquals("Attic had 2 items and Den had 1. They’re all on the household list now. " +
        "It syncs with Portal Toolkit on your phones too.", summary.body)
  }

  @Test fun threePortalsListSeveralDuplicates() {
    var s = migrate(HouseholdListState(), "Den", todo("Eggs"), todo("Milk"))
    s = migrate(s, "Attic", todo("Eggs"), todo("Milk"))
    s = migrate(s, "Kitchen", todo("Jam"))
    val summary = HouseholdListMigration.summary(s)!!
    assertEquals("Your To-do lists are now one", summary.title)
    assertEquals("Attic had 2 items, Den had 2 and Kitchen had 1. They’re all on the household list now. " +
        "Eggs, Milk were on both, so they’re there once. It syncs with Portal Toolkit on your phones too.", summary.body)
  }

  @Test fun removedMigratedItemsDoNotCount() {
    val s = migrate(HouseholdListState(), "Den", todo("Eggs"))
    val gone = HouseholdListOps.remove(s, s.live().single().id, "Den", now + 1).state
    assertEquals(null, HouseholdListMigration.summary(gone))
  }
}
