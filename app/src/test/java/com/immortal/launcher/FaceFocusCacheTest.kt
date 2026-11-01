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

  @Test fun `malformed seven field entry can be repaired only by a valid region result`() {
    val file = temporary.root.resolve("face_focus.json")
    file.writeText("""{"bad":"0.2,0.3,0.8,NaN,0.2,0.4,0.5"}""")
    val cache = FaceFocusCache(file)
    assertTrue(cache.needsAnalysis("bad"))
    assertFalse(cache.record("bad", "0.5,0.5,0.9"))
    assertTrue("Stale point work suppressed region repair", cache.needsAnalysis("bad"))
    val repaired = "0.4,0.6,0.9,0.1,0.2,0.8,0.9"
    assertTrue(cache.record("bad", repaired))
    cache.persist()
    assertFalse(cache.needsAnalysis("bad"))
    assertFalse(cache.record("bad", "0.1,0.1,0.2,NaN,0.0,0.5,0.5"))
    assertEquals(repaired, JSONObject(file.readText()).getString("bad"))
  }

  @Test fun `legacy no-face reanalysis is bounded without replacing the old point`() {
    val file = temporary.root.resolve("face_focus.json")
    file.writeText("""{"old":"0.2,0.3,0.8"}""")
    val first = FaceFocusCache(file)
    val peer = FaceFocusCache(file)
    assertTrue(first.needsAnalysis("old"))
    assertFalse(first.record("old", ""))
    assertFalse(peer.needsAnalysis("old"))
    first.persist()
    assertEquals("0.2,0.3,0.8", JSONObject(file.readText()).getString("old"))
    assertFalse(peer.record("old", "0.5,0.5,0.9"))
    // A valid result already in flight can still upgrade the preserved legacy metadata.
    assertTrue(peer.record("old", "0.4,0.6,0.9,0.1,0.2,0.8,0.9"))
    assertFalse(first.record("old", ""))
  }

  @Test fun `weighted legacy entries need bounds while empty and seven fields are final`() {
    val file = temporary.root.resolve("face_focus.json")
    file.writeText("""{"old":"0.2,0.3,0.8","empty":"","complete":"0.2,0.3,0.8,0.1,0.2,0.4,0.5"}""")
    val cache = FaceFocusCache(file)
    assertTrue(cache.needsAnalysis("old"))
    assertFalse(cache.needsAnalysis("empty"))
    assertFalse(cache.needsAnalysis("complete"))
  }

  @Test fun `valid bounds upgrade legacy weight and late point or region cannot overwrite winner`() {
    val file = temporary.root.resolve("face_focus.json")
    file.writeText("""{"old":"0.2,0.3,0.8"}""")
    val earlier = FaceFocusCache(file)
    val peer = FaceFocusCache(file)
    assertEquals("0.2,0.3,0.8", earlier.entry("old"))
    val upgraded = "0.4,0.6,0.9,0.1,0.2,0.8,0.9"
    assertTrue(peer.record("old", upgraded))
    peer.persist()
    assertFalse(earlier.record("old", "0.1,0.1,0.2"))
    assertFalse(earlier.record("old", "0.1,0.1,0.2,0.0,0.0,0.5,0.5"))
    earlier.persist()
    assertEquals(upgraded, earlier.entry("old"))
    assertEquals(upgraded, JSONObject(file.readText()).getString("old"))
    assertFalse(peer.needsAnalysis("old"))
  }

  @Test fun `legacy weighted result survives no-face and invalid region upgrades`() {
    val file = temporary.root.resolve("face_focus.json")
    file.writeText("""{"old":"0.2,0.3,0.8"}""")
    val cache = FaceFocusCache(file)
    for (incoming in listOf("", "0.5,0.5,0.9", "0.5,0.5,0.9,NaN,0.1,0.8,0.9",
        "0.5,0.5,0.9,0.8,0.1,0.2,0.9", "0.5,0.5,0.9,-0.1,0.1,0.8,0.9")) {
      assertFalse(cache.record("old", incoming))
    }
    cache.persist()
    assertEquals("0.2,0.3,0.8", JSONObject(file.readText()).getString("old"))
  }

  @Test fun `valid region cannot upgrade legacy metadata with invalid point or weight fields`() {
    val file = temporary.root.resolve("face_focus.json")
    file.writeText("""{"old":"0.2,0.3,0.8"}""")
    val cache = FaceFocusCache(file)
    for (prefix in listOf("bad,0.5,0.9", "NaN,0.5,0.9", "0.5,Infinity,0.9",
        "0.5,0.5,NaN", "-0.1,0.5,0.9", "0.5,0.5,1.1")) {
      assertFalse(cache.record("old", "$prefix,0.1,0.1,0.8,0.9"))
    }
    assertEquals("0.2,0.3,0.8", cache.entry("old"))
  }

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
