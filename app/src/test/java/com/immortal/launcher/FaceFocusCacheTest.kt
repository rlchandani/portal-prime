/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FaceFocusCacheTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test fun `earlier hydrated cache cannot overwrite newer results for the same URL`() {
    val file = temporary.root.resolve("face_focus.json")
    file.writeText("""{"old":"0.2,0.3","no-face":""}""")
    val earlier = FaceFocusCache(file)
    val later = FaceFocusCache(file)
    assertEquals("0.2,0.3", earlier.entry("old"))
    assertTrue(later.record("old", "0.4,0.6,0.8"))
    later.persist()
    assertFalse(earlier.record("old", "0.1,0.1,0.2"))
    assertFalse(earlier.record("no-face", "0.1,0.1,0.2"))
    earlier.persist()
    assertEquals("0.4,0.6,0.8", earlier.entry("old"))
    val disk = JSONObject(file.readText())
    assertEquals("0.4,0.6,0.8", disk.getString("old"))
    assertEquals("", disk.getString("no-face"))
  }

  @Test fun `separate cache handles persist the latest merged snapshot`() {
    val file = temporary.root.resolve("face_focus.json")
    val first = FaceFocusCache(file)
    val second = FaceFocusCache(file)
    assertNull(first.entry("a"))
    assertNull(second.entry("b"))
    first.record("a", "0.2,0.3,0.8")
    second.record("b", "")
    second.persist()
    first.persist()
    val disk = JSONObject(file.readText())
    assertEquals("0.2,0.3,0.8", disk.getString("a"))
    assertEquals("", disk.getString("b"))
    assertEquals("0.2,0.3,0.8", second.entry("a"))
  }

  @Test fun `concurrent cache writers preserve updates from both handles`() {
    val file = temporary.root.resolve("face_focus.json")
    val first = FaceFocusCache(file)
    val second = FaceFocusCache(file)
    assertNull(first.entry("a0"))
    assertNull(second.entry("b0"))
    val start = java.util.concurrent.CountDownLatch(1)
    val writers = java.util.concurrent.Executors.newFixedThreadPool(2)
    try {
      val jobs = listOf(first to "a", second to "b").map { (cache, prefix) ->
        writers.submit {
          start.await()
          for (i in 0 until 16) {
            cache.record("$prefix$i", "0.2,0.3,0.8")
            cache.persist()
          }
        }
      }
      start.countDown()
      jobs.forEach { it.get(3, java.util.concurrent.TimeUnit.SECONDS) }
      val disk = JSONObject(file.readText())
      assertEquals(32, disk.length())
      for (prefix in listOf("a", "b")) {
        for (i in 0 until 16) assertEquals("0.2,0.3,0.8", disk.getString("$prefix$i"))
      }
    } finally { start.countDown(); writers.shutdownNow() }
  }

}
