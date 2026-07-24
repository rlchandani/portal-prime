/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class DockStoreTest {
  @Test fun add_appendsWhenRoomAndAbsent() {
    assertEquals(listOf("a", "b"), DockStore.addToDock(listOf("a"), "b", max = 6))
    assertEquals(listOf("a"), DockStore.addToDock(listOf("a"), "a", max = 6)) // dup ignored
  }
  @Test fun add_respectsMax() {
    assertEquals(listOf("a", "b"), DockStore.addToDock(listOf("a", "b"), "c", max = 2)) // full
  }
  @Test fun remove_dropsPkg() {
    assertEquals(listOf("a"), DockStore.removeFromDock(listOf("a", "b"), "b"))
  }
  @Test fun serializeRoundTrip() {
    val list = listOf("x", "y")
    assertEquals(list, DockStore.deserialize(DockStore.serialize(list)))
  }
}
