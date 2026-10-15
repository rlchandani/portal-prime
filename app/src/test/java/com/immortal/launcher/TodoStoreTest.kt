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

  @Test
  fun `deserialize repairs invalid ids to stable nonblank unique identities`() {
    val raw = """[
      {"text":"same legacy","createdMs":1},
      {"text":"same legacy","createdMs":1},
      {"id":"","text":"empty id"},
      {"id":" \t ","text":"whitespace id"},
      {"id":null,"text":"null id"},
      {"id":42,"text":"numeric id"},
      {"id":true,"text":"boolean id"},
      {"id":["x"],"text":"array id"},
      {"id":{"key":"x"},"text":"object id"},
      {"id":"42","text":"saved numeric-looking id"},
      {"id":"true","text":"saved boolean-looking id"},
      {"id":"[\"x\"]","text":"saved array-looking id"},
      {"id":"{\"key\":\"x\"}","text":"saved object-looking id"}
    ]"""
    val first = TodoStore.deserialize(raw)
    assertEquals(13, first.size)
    assertTrue(first.all { it.id.isNotBlank() })
    assertEquals(first.size, first.map { it.id }.toSet().size)
    assertEquals(listOf("42", "true", "[\"x\"]", "{\"key\":\"x\"}"),
        first.takeLast(4).map { it.id })
    repeat(3) { assertEquals(first, TodoStore.deserialize(raw)) }
  }

  @Test
  fun `deserialize preserves first duplicate id and stably repairs later occurrences`() {
    val raw = """[
      {"id":"shared","text":"same","createdMs":7},
      {"id":"other","text":"between","done":true,"createdMs":2},
      {"id":"shared","text":"same","createdMs":7},
      {"id":"shared","text":"same","createdMs":7}
    ]"""
    val first = TodoStore.deserialize(raw)
    assertEquals(4, first.size)
    assertEquals("shared", first[0].id)
    assertEquals("other", first[1].id)
    assertEquals(4, first.map { it.id }.toSet().size)
    assertTrue(first.all { it.id.isNotBlank() })
    assertEquals(listOf("same", "between", "same", "same"), first.map { it.text })
    repeat(3) { assertEquals(first, TodoStore.deserialize(raw)) }
  }

  @Test
  fun `deserialize preserves opaque saved ids fields and array order`() {
    val raw = """[
      {"id":"opaque:/v1?key=alpha","text":"  keep spaces  ","done":true,"createdMs":9000000000000},
      {"id":" padded id ","text":"second","done":false,"createdMs":-7},
      {"id":"legacy-identity","text":"third","done":true,"createdMs":0}
    ]"""
    val expected = listOf(
        TodoStore.TodoItem("opaque:/v1?key=alpha", "  keep spaces  ", true, 9000000000000L),
        TodoStore.TodoItem(" padded id ", "second", false, -7L),
        TodoStore.TodoItem("legacy-identity", "third", true, 0L))
    repeat(3) { assertEquals(expected, TodoStore.deserialize(raw)) }
  }

  @Test
  fun `first load ids toggle and remove exactly the intended row after reload`() {
    val fixtures = listOf(
        """[{"text":"same","createdMs":1},{"text":"same","createdMs":1},
          {"id":"untouched","text":"tail","done":true,"createdMs":3}]""",
        """[{"id":"duplicate","text":"same","createdMs":1},
          {"id":"duplicate","text":"same","createdMs":1},
          {"id":"untouched","text":"tail","done":true,"createdMs":3}]""")
    fixtures.forEach { raw ->
      val first = TodoStore.deserialize(raw)
      assertEquals(3, first.size)
      // Identical row content still represents two separately actionable checklist entries.
      for (target in 0..1) {
        val uiId = first[target].id
        val toggled = TodoStore.withToggled(TodoStore.deserialize(raw), uiId)
        assertEquals(first.mapIndexed { index, item ->
          if (index == target) item.copy(done = !item.done) else item
        }, toggled)
        val removed = TodoStore.without(TodoStore.deserialize(raw), uiId)
        assertEquals(first.filterIndexed { index, _ -> index != target }, removed)
      }
    }
  }

  @Test
  fun `repaired identities persist through serialize and deserialize`() {
    val raw = """[
      {"text":"legacy","done":true,"createdMs":9},
      {"id":"","text":"blank","createdMs":2},
      {"id":"saved","text":"original","createdMs":5},
      {"id":"saved","text":"duplicate","done":true,"createdMs":1},
      {"id":null,"text":"null","createdMs":4}
    ]"""
    val repaired = TodoStore.deserialize(raw)
    assertEquals(5, repaired.size)
    assertTrue(repaired.all { it.id.isNotBlank() })
    assertEquals(5, repaired.map { it.id }.toSet().size)
    assertEquals("saved", repaired[2].id)
    var round = repaired
    repeat(3) {
      round = TodoStore.deserialize(TodoStore.serialize(round))
      assertEquals(repaired, round)
    }
  }

  @Test
  fun `generated repair reserves a later valid saved id`() {
    val legacyRow = """{"text":"legacy","done":true,"createdMs":17}"""
    val candidate = TodoStore.deserialize("[$legacyRow]").single().id
    assertTrue(candidate.isNotBlank())
    // Discover a collision through the public behavior, without assuming any ID format or hash.
    val laterRow = org.json.JSONObject()
        .put("id", candidate)
        .put("text", "saved later")
        .put("done", false)
        .put("createdMs", 23L)
    val raw = "[$legacyRow,$laterRow]"
    val repaired = TodoStore.deserialize(raw)
    assertEquals(2, repaired.size)
    assertEquals(TodoStore.TodoItem(candidate, "saved later", false, 23L), repaired[1])
    assertTrue(repaired[0].id.isNotBlank())
    assertFalse(candidate == repaired[0].id)
    assertEquals("legacy", repaired[0].text)
    assertTrue(repaired[0].done)
    assertEquals(17L, repaired[0].createdMs)
    repeat(3) { assertEquals(repaired, TodoStore.deserialize(raw)) }
  }

  @Test
  fun `deserialize skips invalid nonobject and blank rows preserving survivors`() {
    listOf("", "   ", "not json", "null", "{}", "[{").forEach { raw ->
      assertTrue(TodoStore.deserialize(raw).isEmpty())
    }
    val raw = """[
      null, 42, true, "not an object", [], {}, {"id":"unused","done":true},
      {"id":"blank","text":""}, {"id":"whitespace","text":" \t\n "},
      {"id":"keep-a","text":"first survivor","done":true,"createdMs":9},
      null, {"text":""},
      {"id":"keep-b","text":"second survivor","done":false,"createdMs":2}
    ]"""
    val expected = listOf(
        TodoStore.TodoItem("keep-a", "first survivor", true, 9L),
        TodoStore.TodoItem("keep-b", "second survivor", false, 2L))
    assertEquals(expected, TodoStore.deserialize(raw))
  }
}
