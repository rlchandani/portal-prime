/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Poster sizing and its in-memory cache (the frame itself is decoded on device only). */
class CallMessagePosterTest {
  @Test fun postersAreTinyAndNeverUpscaled() {
    assertEquals(384 to 216, CallMessagePosters.target(1280, 720))
    assertEquals(216 to 384, CallMessagePosters.target(720, 1280))
    assertEquals(200 to 100, CallMessagePosters.target(200, 100))
    assertNull(CallMessagePosters.target(0, 720))
    val (w, h) = CallMessagePosters.target(1280, 720)!!
    assertEquals("a poster is about a third of a megabyte", 331_776, w * h * 4)
  }

  @Test fun posterCacheKeepsWithinItsBudgetNewestFirst() {
    val cache = PosterCache<Long>(budgetBytes = 1_000) { it }
    cache.put("a", 400); cache.put("b", 400)
    cache.get("a") // a is now the most recent
    cache.put("c", 400)
    assertNull("least recently used goes first", cache.get("b"))
    assertEquals(400L, cache.get("a")); assertEquals(400L, cache.get("c"))
    assertEquals(800L, cache.bytes())
    cache.put("huge", 5_000)
    assertNull("never holds something over budget", cache.get("huge"))
    cache.put("a", 100)
    assertEquals(500L, cache.bytes())
    cache.remove("c"); assertEquals(100L, cache.bytes())
    cache.clear(); assertEquals(0, cache.size()); assertEquals(0L, cache.bytes())
  }
}
