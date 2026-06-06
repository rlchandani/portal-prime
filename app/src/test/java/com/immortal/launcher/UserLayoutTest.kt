/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
}
