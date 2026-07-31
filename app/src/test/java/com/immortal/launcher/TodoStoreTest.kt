/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure list transforms + JSON round-trip for [TodoStore] (no Context/device needed). */
class TodoStoreTest {

  @Test
  fun `withAdded appends a trimmed unchecked item`() {
    val items = TodoStore.withAdded(emptyList(), "  buy milk  ", nowMs = 100L)
    assertEquals(1, items.size)
    assertEquals("buy milk", items[0].text)
    assertFalse(items[0].done)
    assertEquals(100L, items[0].createdMs)
  }

  @Test
  fun `withToggled flips only the matching item`() {
    val a = TodoStore.withAdded(emptyList(), "a", 1L)
    val both = TodoStore.withAdded(a, "b", 2L)
    val targetId = both[0].id
    val toggled = TodoStore.withToggled(both, targetId)
    assertTrue(toggled.first { it.id == targetId }.done)
    assertFalse(toggled.first { it.id != targetId }.done)
  }

  @Test
  fun `without removes by id`() {
    val items = TodoStore.withAdded(TodoStore.withAdded(emptyList(), "a", 1L), "b", 2L)
    val removed = TodoStore.without(items, items[0].id)
    assertEquals(1, removed.size)
    assertEquals("b", removed[0].text)
  }

  @Test
  fun `serialize then deserialize preserves items and order`() {
    // ids are random, so build up and toggle by the actual id rather than hardcoding.
    val a = TodoStore.withAdded(emptyList(), "first", 1L)
    val b = TodoStore.withAdded(a, "second", 2L)
    val toggled = TodoStore.withToggled(b, b[1].id)

    val round = TodoStore.deserialize(TodoStore.serialize(toggled))
    assertEquals(listOf("first", "second"), round.map { it.text })
    assertFalse(round[0].done)
    assertTrue(round[1].done)
    assertEquals(toggled[0].id, round[0].id)
  }

  @Test
  fun `deserialize drops blank-text entries and tolerates garbage`() {
    assertTrue(TodoStore.deserialize("not json").isEmpty())
    assertTrue(TodoStore.deserialize("""[{"text":""},{"done":true}]""").isEmpty())
    val ok = TodoStore.deserialize("""[{"text":"real","done":false,"id":"x","createdMs":5}]""")
    assertEquals(1, ok.size)
    assertEquals("real", ok[0].text)
  }
}
