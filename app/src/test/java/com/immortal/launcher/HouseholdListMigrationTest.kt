/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.TodoStore.TodoItem
import org.junit.Assert.*
import org.junit.Test

class HouseholdListMigrationTest {
  private val now = 1_800_000_000_000L
  private val fa = "Family Area"
  private val la = "Living Area"

  private fun todos(vararg t: Pair<String, Boolean>) = t.mapIndexed { i, (text, done) -> TodoItem("id$i", text, done, now - 10_000 + i) }

  @Test fun bothPortalsToDos_becomeOneList_withSharedItemsOnce() {
    val family = HouseholdListMigration.migrate(HouseholdListState(), todos("Eggs" to false, "Dish soap" to false, "Stamps" to true), fa, now)
    val living = HouseholdListMigration.migrate(HouseholdListState(), todos("Eggs" to false, "Light bulb" to false), la, now)
    val merged = HouseholdListMerge.merge(family, living)
    assertEquals(4, merged.live().size)
    assertEquals(setOf(fa, la), merged.liveByText("Eggs")!!.migratedFrom)
    assertTrue(merged.liveByText("Stamps")!!.done.value)
    val summary = HouseholdListMigration.summary(merged)!!
    assertEquals("Your two To-do lists are now one", summary.title)
    assertEquals("Family Area had 3 items and Living Area had 2. They’re all on the household list now. " +
        "Eggs was on both, so it’s there once. It syncs with Calder Pocket on your phones too.", summary.body)
  }

  @Test fun onePortal_getsItsOwnWording_andEmptyToDoNeedsNoDialog() {
    val family = HouseholdListMigration.migrate(HouseholdListState(), todos("Eggs" to false), fa, now)
    assertEquals("Your To-do list is now the household list", HouseholdListMigration.summary(family)!!.title)
    assertNull(HouseholdListMigration.summary(HouseholdListMigration.migrate(HouseholdListState(), emptyList(), fa, now)))
  }

  @Test fun migration_shortensOverlongText_andMergesDuplicatesWithinOnePortal() {
    val s = HouseholdListMigration.migrate(HouseholdListState(), todos("Milk" to false, "milk" to false, "x".repeat(80) to false, "  " to false), fa, now)
    assertEquals(setOf("Milk", "x".repeat(60)), s.open().map { it.text.value }.toSet())
  }

  @Test fun migrationIsIdempotent() {
    val list = todos("Eggs" to false, "Bread" to true)
    val once = HouseholdListMigration.migrate(HouseholdListState(), list, fa, now)
    assertEquals(once, HouseholdListMigration.migrate(once, list, fa, now))
  }
}
