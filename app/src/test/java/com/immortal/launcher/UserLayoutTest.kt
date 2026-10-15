/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folder-naming and layout-serialization logic (no device needed). */
class UserLayoutTest {

  @Test
  fun nextFolderName_firstIsPlainFolder() {
    assertEquals("Folder", UserLayout.nextFolderName(emptySet()))
  }

  @Test
  fun nextFolderName_incrementsWhenTaken() {
    assertEquals("Folder 2", UserLayout.nextFolderName(setOf("Folder")))
    assertEquals("Folder 3", UserLayout.nextFolderName(setOf("Folder", "Folder 2")))
  }

  @Test
  fun nextFolderName_picksLowestFreeNumber() {
    // "Folder 2" is free even though "Folder 3" exists.
    assertEquals("Folder 2", UserLayout.nextFolderName(setOf("Folder", "Folder 3")))
  }

  @Test
  fun serialize_deserialize_roundTrips() {
    val map = mapOf("com.a" to "Folder", "com.b" to "Media & Entertainment")
    assertEquals(map, UserLayout.deserialize(UserLayout.serialize(map)))
  }

  @Test
  fun serialize_deserialize_emptyMap() {
    assertEquals(emptyMap<String, String>(), UserLayout.deserialize(UserLayout.serialize(emptyMap())))
  }

  @Test
  fun deserialize_isEmptyOnGarbage() {
    assertTrue(UserLayout.deserialize("not json at all").isEmpty())
    assertTrue(UserLayout.deserialize("").isEmpty())
  }

  @Test
  fun order_serialize_deserialize_roundTripsDistinctIds() {
    val order = listOf("com.a", "com.b", "com.a", "com.c")
    assertEquals(listOf("com.a", "com.b", "com.c"), UserLayout.deserializeOrder(UserLayout.serializeOrder(order)))
  }

  @Test
  fun applyOrder_keepsUnknownAndNewItemsStable() {
    val apps = listOf("a", "b", "c")
    assertEquals(listOf("c", "a", "b"), UserLayout.applyOrder(apps, listOf("c", "missing", "a")) { it })
  }

  @Test
  fun moveOrder_movesSourceBeforeTarget() {
    assertEquals(listOf("a", "c", "b"), UserLayout.moveOrder(listOf("a", "b", "c"), "c", "b"))
    assertEquals(listOf("b", "a", "c"), UserLayout.moveOrder(listOf("a", "b", "c"), "a", "b"))
  }

  @Test
  fun moveOrder_movesSourceToTargetPosition() {
    assertEquals(listOf("b", "c", "a", "d"), UserLayout.moveOrder(listOf("a", "b", "c", "d"), "a", "c"))
  }

  @Test
  fun moveOrder_noopWhenSameOrMissing() {
    assertEquals(listOf("a", "b"), UserLayout.moveOrder(listOf("a", "b"), "a", "a"))
    assertEquals(listOf("a", "b"), UserLayout.moveOrder(listOf("a", "b"), "x", "b"))
  }

  @Test
  fun applyOrder_knownThenRest() {
    assertEquals(listOf("c", "a", "b"), UserLayout.applyOrder(listOf("a", "b", "c"), listOf("c", "a")) { it })
  }
  @org.junit.Test
  fun folderRenameRejectsExistingNameWithoutMergingMembership() {
    val saved = mapOf("one" to "Alpha", "two" to "Beta")
    org.junit.Assert.assertNotNull(UserLayout.folderRenameError(saved, "Alpha", " Beta ", listOf("Alpha", "Beta")))
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      UserLayout.renamedFolderAssignments(saved, "Alpha", "Beta", listOf("one"), listOf("Alpha", "Beta"))
    }
    org.junit.Assert.assertEquals(mapOf("one" to "Alpha", "two" to "Beta"), saved)
  }

  @org.junit.Test
  fun folderRenameRejectsBlankNameInsteadOfUngroupingApps() {
    val saved = mapOf("one" to "Alpha")
    org.junit.Assert.assertNotNull(UserLayout.folderRenameError(saved, "Alpha", "   ", listOf("Alpha")))
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
      UserLayout.renamedFolderAssignments(saved, "Alpha", "", listOf("one"), listOf("Alpha"))
    }
  }

  @org.junit.Test
  fun folderRenameKeepsOfflineMembersAndUnrelatedOverrides() {
    val saved = mapOf("one" to "Alpha", "offline" to "Alpha", "other" to "Beta", "ungrouped" to "")
    val changed = UserLayout.renamedFolderAssignments(saved, "Alpha", "Renamed", listOf("one", "curated"), listOf("Alpha", "Beta"))
    org.junit.Assert.assertEquals(mapOf("one" to "Renamed", "offline" to "Renamed", "curated" to "Renamed", "other" to "Beta", "ungrouped" to ""), changed)
    org.junit.Assert.assertEquals("Alpha", saved["offline"])
  }

  @org.junit.Test
  fun unchangedFolderNameDoesNotMaterializeCuratedOverrides() {
    val saved = mapOf("other" to "Beta")
    org.junit.Assert.assertSame(saved, UserLayout.renamedFolderAssignments(saved, "Settings", " Settings ", listOf("curated"), listOf("Settings", "Beta")))
  }

  @org.junit.Test
  fun folderRenameReservesSavedNamesEvenWhenFolderIsNotInstalled() {
    val saved = mapOf("offline" to "Offline")
    org.junit.Assert.assertNotNull(UserLayout.folderRenameError(saved, "Alpha", "Offline", listOf("Alpha")))
  }

  @org.junit.Test
  fun folderRenameTrimsDestinationAndPreservesExactCaseIdentity() {
    val saved = mapOf("one" to "Alpha", "differentCase" to "alpha")
    org.junit.Assert.assertEquals(mapOf("one" to "Renamed", "differentCase" to "alpha"),
        UserLayout.renamedFolderAssignments(saved, "Alpha", "  Renamed  ", listOf("one"), listOf("Alpha", "alpha")))
    org.junit.Assert.assertNull(UserLayout.folderRenameError(saved, "Alpha", "ALPHA", listOf("Alpha", "alpha")))
  }

  @org.junit.Test
  fun folderRenameTreatsNoFolderAsARealNamedDestination() {
    org.junit.Assert.assertNull(UserLayout.folderRenameError(emptyMap(), "Alpha", "No folder", listOf("Alpha")))
    org.junit.Assert.assertEquals(mapOf("one" to "No folder"),
        UserLayout.renamedFolderAssignments(emptyMap(), "Alpha", "No folder", listOf("one"), listOf("Alpha")))
  }

}
