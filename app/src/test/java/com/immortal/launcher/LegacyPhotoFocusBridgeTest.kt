/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class LegacyPhotoFocusBridgeTest {
  @Test fun `only frozen photo indices can read metadata`() {
    val reads = mutableListOf<String>()
    val bridge = LegacyPhotoFocusBridge(listOf("allowed"), { true }) { reads.add(it); "0.2,0.5,0.8" }
    assertEquals("", bridge.read(-1))
    assertEquals("", bridge.read(1))
    assertEquals("", bridge.read(Int.MAX_VALUE))
    assertEquals("0.2,0.5,0.8", bridge.read(0))
    assertEquals(listOf("allowed"), reads)
  }
  @Test fun `superseded owners do not read the cache`() {
    val bridge = LegacyPhotoFocusBridge(listOf("allowed"), { false }) {
      fail("A retired bridge must not read metadata"); ""
    }
    assertEquals("", bridge.read(0))
  }
  @Test fun `retirement during a cache read rejects its result`() {
    var current = true
    val bridge = LegacyPhotoFocusBridge(listOf("allowed"), { current }) {
      current = false; "0.2,0.5,0.8"
    }
    assertEquals("", bridge.read(0))
  }
  @Test fun `large cache entries are not handed to WebView`() {
    val bridge = LegacyPhotoFocusBridge(listOf("allowed"), { true }) { "x".repeat(257) }
    assertEquals("", bridge.read(0))
  }
  @Test fun `metadata is read afresh without rewriting source identity`() {
    var entry = "0.2,0.5,0.8"
    val bridge = LegacyPhotoFocusBridge(listOf("allowed"), { true }) { entry }
    assertEquals(entry, bridge.read(0))
    entry += ",0.15,0.2,0.75,0.7"
    assertEquals(entry, bridge.read(0))
  }
}
