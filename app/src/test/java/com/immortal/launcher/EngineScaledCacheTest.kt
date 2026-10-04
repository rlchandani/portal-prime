/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure sizing / keying / eviction math behind the engine's decode-once photo cache (no Bitmap). */
class EngineScaledCacheTest {

  @Test
  fun `key is stable for the same file identity`() {
    assertEquals(
        EngineScaledCache.keyFor("/a/b.jpg", 100L, 5L), EngineScaledCache.keyFor("/a/b.jpg", 100L, 5L))
  }

  @Test
  fun `key changes when the original is edited`() {
    val base = EngineScaledCache.keyFor("/a/b.jpg", 100L, 5L)
    assertNotEquals(base, EngineScaledCache.keyFor("/a/b.jpg", 101L, 5L))
    assertNotEquals(base, EngineScaledCache.keyFor("/a/b.jpg", 100L, 6L))
    assertNotEquals(base, EngineScaledCache.keyFor("/a/c.jpg", 100L, 5L))
  }

  @Test
  fun `key is a safe file name`() {
    assertTrue(EngineScaledCache.keyFor("/storage/emulated/0/x y.JPG", 1L, 2L).matches(Regex("[0-9a-f]+\\.jpg")))
  }

  @Test
  fun `sample size is the largest power of two that keeps the long edge above target`() {
    assertEquals(1, EngineScaledCache.sampleSizeFor(4000, 2400))
    assertEquals(2, EngineScaledCache.sampleSizeFor(7000, 2400))
    assertEquals(4, EngineScaledCache.sampleSizeFor(12000, 2400))
    assertEquals(1, EngineScaledCache.sampleSizeFor(1000, 2400))
  }

  @Test
  fun `only clearly oversized photos are worth caching`() {
    assertTrue(EngineScaledCache.needsScaling(4000, 3000, 2400))
    assertTrue(EngineScaledCache.needsScaling(3000, 4000, 2400))
    assertFalse(EngineScaledCache.needsScaling(2500, 1600, 2400)) // within tolerance: not worth a copy
    assertFalse(EngineScaledCache.needsScaling(1920, 1280, 2400))
  }

  @Test
  fun `target size keeps aspect and caps the long edge`() {
    assertEquals(2400 to 1800, EngineScaledCache.targetSize(4000, 3000, 2400))
    assertEquals(1800 to 2400, EngineScaledCache.targetSize(3000, 4000, 2400))
  }

  @Test
  fun `eviction drops the oldest files until under budget`() {
    val files =
        listOf(
            EngineScaledCache.Entry("old", 40L, 1L),
            EngineScaledCache.Entry("mid", 40L, 2L),
            EngineScaledCache.Entry("new", 40L, 3L))
    assertEquals(listOf("old", "mid"), EngineScaledCache.evictionPlan(files, budgetBytes = 50L))
    assertEquals(emptyList<String>(), EngineScaledCache.evictionPlan(files, budgetBytes = 200L))
  }

  @Test
  fun `resolve returns the scaled copy only when present and non-empty`() {
    val tmp = File.createTempFile("ssc-orig", ".jpg").apply { writeBytes(ByteArray(10)) }
    val dir = File.createTempFile("ssc-dir", "").apply { delete(); mkdirs() }
    val cache = EngineScaledCache(dir)
    assertNull(cache.resolve(tmp.path))
    val key = EngineScaledCache.keyFor(tmp.path, tmp.length(), tmp.lastModified())
    val scaled = File(dir, key).apply { createNewFile() }
    assertNull(cache.resolve(tmp.path)) // zero-byte = half-written → not served
    scaled.writeBytes(ByteArray(3))
    assertEquals(scaled, cache.resolve(tmp.path))
    assertNull(cache.resolve("/nope/missing.jpg"))
  }
}
