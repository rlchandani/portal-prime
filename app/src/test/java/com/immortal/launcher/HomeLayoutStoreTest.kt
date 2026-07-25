/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.HomeLayoutStore.HomeLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutStoreTest {

  // ── moveWithinGrid ──────────────────────────────────────────────────────────

  @Test
  fun moveWithinGrid_reordersCorrectly() {
    val layout = HomeLayout(
        grid = listOf("a", "b", "c", "d"),
        dock = emptyList()
    )
    val result = HomeLayoutStore.moveWithinGrid(layout, from = 1, to = 3)
    assertEquals(listOf("a", "c", "d", "b"), result.grid)
    assertEquals(emptyList<String>(), result.dock)
  }

  @Test
  fun moveWithinGrid_clampsOutOfRangeIndices() {
    val layout = HomeLayout(
        grid = listOf("a", "b", "c"),
        dock = emptyList()
    )
    // From index out of range
    val result1 = HomeLayoutStore.moveWithinGrid(layout, from = 5, to = 1)
    assertEquals(layout, result1)

    // To index out of range
    val result2 = HomeLayoutStore.moveWithinGrid(layout, from = 1, to = 10)
    assertEquals(layout, result2)

    // Negative indices
    val result3 = HomeLayoutStore.moveWithinGrid(layout, from = -1, to = 1)
    assertEquals(layout, result3)
  }

  @Test
  fun moveWithinGrid_sameIndexNoOp() {
    val layout = HomeLayout(
        grid = listOf("a", "b", "c"),
        dock = emptyList()
    )
    val result = HomeLayoutStore.moveWithinGrid(layout, from = 1, to = 1)
    assertEquals(layout, result)
  }

  // ── moveWithinDock ──────────────────────────────────────────────────────────

  @Test
  fun moveWithinDock_reordersCorrectly() {
    val layout = HomeLayout(
        grid = emptyList(),
        dock = listOf("x", "y", "z")
    )
    val result = HomeLayoutStore.moveWithinDock(layout, from = 0, to = 2)
    assertEquals(listOf("y", "z", "x"), result.dock)
    assertEquals(emptyList<String>(), result.grid)
  }

  @Test
  fun moveWithinDock_clampsOutOfRangeIndices() {
    val layout = HomeLayout(
        grid = emptyList(),
        dock = listOf("x", "y")
    )
    // From out of range
    val result1 = HomeLayoutStore.moveWithinDock(layout, from = 5, to = 0)
    assertEquals(layout, result1)

    // Negative
    val result2 = HomeLayoutStore.moveWithinDock(layout, from = 0, to = -1)
    assertEquals(layout, result2)
  }

  @Test
  fun moveWithinDock_sameIndexNoOp() {
    val layout = HomeLayout(
        grid = emptyList(),
        dock = listOf("x", "y")
    )
    val result = HomeLayoutStore.moveWithinDock(layout, from = 1, to = 1)
    assertEquals(layout, result)
  }

  // ── moveToDock ──────────────────────────────────────────────────────────────

  @Test
  fun moveToDock_removesFromGridAndAdds() {
    val layout = HomeLayout(
        grid = listOf("a", "b", "c"),
        dock = listOf("x", "y")
    )
    val result = HomeLayoutStore.moveToDock(layout, pkg = "b", dockIndex = 1, maxDock = 6)
    // "b" removed from grid, inserted at dock[1]
    assertEquals(listOf("a", "c"), result.grid)
    assertEquals(listOf("x", "b", "y"), result.dock)
  }

  @Test
  fun moveToDock_respectsMaxCapacity() {
    val layout = HomeLayout(
        grid = listOf("a", "b"),
        dock = listOf("x", "y", "z") // size = 3
    )
    val result = HomeLayoutStore.moveToDock(layout, pkg = "a", dockIndex = 0, maxDock = 3)
    // Dock full → unchanged
    assertEquals(layout, result)
  }

  @Test
  fun moveToDock_alreadyInDock_reorders() {
    val layout = HomeLayout(
        grid = listOf("a", "b"),
        dock = listOf("x", "y", "z")
    )
    val result = HomeLayoutStore.moveToDock(layout, pkg = "y", dockIndex = 0, maxDock = 6)
    // "y" already in dock → reorder to index 0
    assertEquals(listOf("a", "b"), result.grid)
    assertEquals(listOf("y", "x", "z"), result.dock)
  }

  @Test
  fun moveToDock_notPresent_insertsAtClampedIndex() {
    val layout = HomeLayout(
        grid = listOf("a", "b"),
        dock = listOf("x")
    )
    val result = HomeLayoutStore.moveToDock(layout, pkg = "new", dockIndex = 10, maxDock = 6)
    // dockIndex clamped to dock.size = 1 (insert at end)
    assertEquals(listOf("a", "b"), result.grid)
    assertEquals(listOf("x", "new"), result.dock)
  }

  @Test
  fun moveToDock_onePlaceInvariant_pkgNotInGrid() {
    val layout = HomeLayout(
        grid = listOf("a", "b", "c"),
        dock = listOf("x")
    )
    val result = HomeLayoutStore.moveToDock(layout, pkg = "b", dockIndex = 0, maxDock = 6)
    // After move, "b" must not be in grid
    assertEquals(false, "b" in result.grid)
    assertEquals(true, "b" in result.dock)
  }

  // ── moveToGrid ──────────────────────────────────────────────────────────────

  @Test
  fun moveToGrid_removesFromDockAndAdds() {
    val layout = HomeLayout(
        grid = listOf("a", "b"),
        dock = listOf("x", "y", "z")
    )
    val result = HomeLayoutStore.moveToGrid(layout, pkg = "y", gridIndex = 1)
    // "y" removed from dock, inserted at grid[1]
    assertEquals(listOf("a", "y", "b"), result.grid)
    assertEquals(listOf("x", "z"), result.dock)
  }

  @Test
  fun moveToGrid_alreadyInGrid_reorders() {
    val layout = HomeLayout(
        grid = listOf("a", "b", "c"),
        dock = listOf("x")
    )
    val result = HomeLayoutStore.moveToGrid(layout, pkg = "c", gridIndex = 0)
    // "c" already in grid → reorder to index 0
    assertEquals(listOf("c", "a", "b"), result.grid)
    assertEquals(listOf("x"), result.dock)
  }

  @Test
  fun moveToGrid_notPresent_insertsAtClampedIndex() {
    val layout = HomeLayout(
        grid = listOf("a"),
        dock = listOf("x")
    )
    val result = HomeLayoutStore.moveToGrid(layout, pkg = "new", gridIndex = 100)
    // gridIndex clamped to grid.size = 1 (insert at end)
    assertEquals(listOf("a", "new"), result.grid)
    assertEquals(listOf("x"), result.dock)
  }

  @Test
  fun moveToGrid_onePlaceInvariant_pkgNotInDock() {
    val layout = HomeLayout(
        grid = listOf("a"),
        dock = listOf("x", "y", "z")
    )
    val result = HomeLayoutStore.moveToGrid(layout, pkg = "y", gridIndex = 0)
    // After move, "y" must not be in dock
    assertEquals(true, "y" in result.grid)
    assertEquals(false, "y" in result.dock)
  }

  // ── normalize ───────────────────────────────────────────────────────────────

  @Test
  fun normalize_dedupesDockWins() {
    val layout = HomeLayout(
        grid = listOf("a", "b", "c"),
        dock = listOf("b", "x") // "b" in both → dock wins
    )
    val allInstalled = listOf("a", "b", "c", "x")
    val result = HomeLayoutStore.normalize(layout, allInstalled)
    assertEquals(listOf("a", "c"), result.grid) // "b" dropped from grid
    assertEquals(listOf("b", "x"), result.dock)
  }

  @Test
  fun normalize_dropsUninstalled() {
    val layout = HomeLayout(
        grid = listOf("a", "b", "c"),
        dock = listOf("x", "y")
    )
    val allInstalled = listOf("a", "c", "x") // "b" and "y" uninstalled
    val result = HomeLayoutStore.normalize(layout, allInstalled)
    assertEquals(listOf("a", "c"), result.grid)
    assertEquals(listOf("x"), result.dock)
  }

  @Test
  fun normalize_appendsNewlyInstalled() {
    val layout = HomeLayout(
        grid = listOf("a", "b"),
        dock = listOf("x")
    )
    val allInstalled = listOf("a", "b", "x", "new1", "new2") // new1, new2 just installed
    val result = HomeLayoutStore.normalize(layout, allInstalled)
    assertEquals(listOf("a", "b", "new1", "new2"), result.grid) // appended to grid
    assertEquals(listOf("x"), result.dock)
  }

  @Test
  fun normalize_preservesOrder() {
    val layout = HomeLayout(
        grid = listOf("c", "a", "b"),
        dock = listOf("z", "y")
    )
    val allInstalled = listOf("a", "b", "c", "y", "z")
    val result = HomeLayoutStore.normalize(layout, allInstalled)
    // Relative order preserved
    assertEquals(listOf("c", "a", "b"), result.grid)
    assertEquals(listOf("z", "y"), result.dock)
  }

  @Test
  fun normalize_handlesDuplicatesInInput() {
    val layout = HomeLayout(
        grid = listOf("a", "a", "b"), // duplicate "a"
        dock = listOf("x", "x") // duplicate "x"
    )
    val allInstalled = listOf("a", "b", "x")
    val result = HomeLayoutStore.normalize(layout, allInstalled)
    // Duplicates removed
    assertEquals(listOf("a", "b"), result.grid)
    assertEquals(listOf("x"), result.dock)
  }

  // ── serialize/deserialize ───────────────────────────────────────────────────

  @Test
  fun serializeDeserialize_roundTrip() {
    val layout = HomeLayout(
        grid = listOf("com.example.app1", "com.example.app2"),
        dock = listOf("com.example.dock1", "com.example.dock2", "com.example.dock3")
    )
    val serialized = HomeLayoutStore.serialize(layout)
    val deserialized = HomeLayoutStore.deserialize(serialized)
    assertEquals(layout, deserialized)
  }

  @Test
  fun deserialize_garbageInput_returnsEmptyLayout() {
    val garbage = "not-json-at-all"
    val result = HomeLayoutStore.deserialize(garbage)
    assertEquals(HomeLayout(emptyList(), emptyList()), result)
  }

  @Test
  fun deserialize_partialJSON_tolerant() {
    // Missing "dock" key
    val partial = """{"grid":["a","b"]}"""
    val result = HomeLayoutStore.deserialize(partial)
    assertEquals(listOf("a", "b"), result.grid)
    assertEquals(emptyList<String>(), result.dock)
  }

  @Test
  fun deserialize_blankPackagesSkipped() {
    val json = """{"grid":["a","","b"],"dock":["x","  ","y"]}"""
    val result = HomeLayoutStore.deserialize(json)
    assertEquals(listOf("a", "b"), result.grid)
    assertEquals(listOf("x", "y"), result.dock)
  }

  @Test
  fun deserialize_emptyArrays() {
    val json = """{"grid":[],"dock":[]}"""
    val result = HomeLayoutStore.deserialize(json)
    assertEquals(HomeLayout(emptyList(), emptyList()), result)
  }
}
