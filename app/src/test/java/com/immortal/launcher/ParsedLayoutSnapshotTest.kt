/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class ParsedLayoutSnapshotTest {
  @Test fun fourLayoutCodecs_parseOnceAcrossTwentyWarmVisits() {
    val parses = AtomicInteger()
    val assignments = ParsedLayoutSnapshot<Map<String, String>>(emptyMap(), {
      parses.incrementAndGet(); UserLayout.deserialize(it)
    })
    val order = ParsedLayoutSnapshot<List<String>>(emptyList(), {
      parses.incrementAndGet(); UserLayout.deserializeOrder(it)
    })
    val grid = ParsedLayoutSnapshot<List<String>>(emptyList(), {
      parses.incrementAndGet(); UserLayout.deserializeOrder(it)
    })
    val slots = ParsedLayoutSnapshot<List<String?>>(emptyList(), {
      parses.incrementAndGet(); UserLayout.deserializeSlots(it)
    })
    repeat(20) {
      assertEquals(mapOf("com.a" to "Media"), assignments.read("{\"com.a\":\"Media\"}"))
      assertEquals(listOf("com.a", "com.b"), order.read("[\"com.a\",\"com.b\",\"com.a\"]"))
      assertEquals(listOf("widget:clock", "app:a"), grid.read("[\"widget:clock\",\"app:a\"]"))
      assertEquals(listOf("app:a", null, "widget:clock"), slots.read("[\"app:a\",\"\",\"widget:clock\"]"))
    }
    println("layout_codec_parses=" + parses.get())
    assertEquals(4, parses.get())
  }

  @Test fun concurrentSameRaw_hasOneParseAndOneImmutableResult() {
    val parses = AtomicInteger()
    val snapshot = ParsedLayoutSnapshot<List<String>>(emptyList(), {
      parses.incrementAndGet(); UserLayout.deserializeOrder(it)
    })
    val executor = Executors.newFixedThreadPool(8)
    val gate = CountDownLatch(1)
    try {
      val futures = (1..8).map { executor.submit<List<String>> {
        gate.await(); snapshot.read("[\"concurrent-layout\"]")
      } }
      gate.countDown()
      val results = futures.map { it.get(5, TimeUnit.SECONDS) }
      assertEquals(1, parses.get())
      results.forEach { assertSame(results.first(), it) }
    } finally { gate.countDown(); executor.shutdownNow() }
  }

  @Test fun changedRaw_invalidatesExactlyOneRetainedValue() {
    var parses = 0
    val snapshot = ParsedLayoutSnapshot("", { parses++; it.uppercase() })
    assertEquals("A", snapshot.read("a"))
    assertEquals("B", snapshot.read("b"))
    assertEquals("B", snapshot.read("b"))
    assertEquals("A", snapshot.read("a"))
    assertEquals(3, parses)
  }

  @Test fun removedPreference_clearsSnapshotAndReparsesRestoredValue() {
    var parses = 0
    val snapshot = ParsedLayoutSnapshot("empty", { parses++; it })
    snapshot.read("a")
    assertEquals("empty", snapshot.read(null))
    assertEquals("a", snapshot.read("a"))
    assertEquals(2, parses)
  }

  @Test fun equalStringsWithDifferentIdentities_reuseDecodedValue() {
    var parses = 0
    val snapshot = ParsedLayoutSnapshot<List<String>>(emptyList(), {
      parses++; UserLayout.deserializeOrder(it)
    })
    val first = "[\"same-layout\"]"
    val second = String(first.toCharArray())
    assertNotSame(first, second)
    assertSame(snapshot.read(first), snapshot.read(second))
    assertEquals(1, parses)
  }

  @Test fun oversizedInput_remainsSupportedWithoutRetention() {
    var parses = 0
    val snapshot = ParsedLayoutSnapshot("", { parses++; it }, maxRawChars = 4)
    assertEquals("large", snapshot.read("large"))
    assertEquals("large", snapshot.read("large"))
    assertEquals(2, parses)
  }

  @Test fun retentionBoundary_isInclusiveAndBoundedByOneValue() {
    var parses = 0
    val snapshot = ParsedLayoutSnapshot("", { parses++; it }, maxRawChars = 4)
    snapshot.read("four"); snapshot.read("four")
    assertEquals(1, parses)
    snapshot.read("other"); snapshot.read("other")
    assertEquals(3, parses)
    snapshot.read("four")
    assertEquals(3, parses)
    snapshot.read("new"); snapshot.read("four")
    assertEquals(5, parses)
  }

  @Test fun decoderFailure_isNotCached() {
    var attempts = 0
    val snapshot = ParsedLayoutSnapshot("", {
      attempts++; if (attempts == 1) error("temporary decode failure") else it
    })
    try { snapshot.read("a"); fail("Expected decoder failure") }
    catch (_: IllegalStateException) { }
    assertEquals("a", snapshot.read("a"))
    assertEquals("a", snapshot.read("a"))
    assertEquals(2, attempts)
  }

  @Test fun cachedLayoutCollections_areImmutable() {
    val maps = ParsedLayoutSnapshot<Map<String, String>>(emptyMap(), UserLayout::deserialize)
    val lists = ParsedLayoutSnapshot<List<String>>(emptyList(), UserLayout::deserializeOrder)
    val slots = ParsedLayoutSnapshot<List<String?>>(emptyList(), UserLayout::deserializeSlots)
    assertThrows(UnsupportedOperationException::class.java) {
      (maps.read("{\"a\":\"Folder\"}") as MutableMap)["a"] = "Changed"
    }
    assertThrows(UnsupportedOperationException::class.java) {
      (lists.read("[\"a\"]") as MutableList).add("b")
    }
    assertThrows(UnsupportedOperationException::class.java) {
      (slots.read("[\"a\",\"\"]") as MutableList).clear()
    }
    assertEquals(mapOf("a" to "Folder"), maps.read("{\"a\":\"Folder\"}"))
  }

  @Test fun tolerantCodecs_keepExistingMalformedDuplicateAndBlankBehavior() {
    val maps = ParsedLayoutSnapshot<Map<String, String>>(emptyMap(), UserLayout::deserialize)
    val order = ParsedLayoutSnapshot<List<String>>(emptyList(), UserLayout::deserializeOrder)
    val slots = ParsedLayoutSnapshot<List<String?>>(emptyList(), UserLayout::deserializeSlots)
    for (raw in listOf("not-json", "", "[]", "{\"a\":\"Folder\"}")) {
      assertEquals(UserLayout.deserialize(raw), maps.read(raw))
      assertEquals(UserLayout.deserializeOrder(raw), order.read(raw))
      assertEquals(UserLayout.deserializeSlots(raw), slots.read(raw))
    }
    assertEquals(listOf("a", "b"), order.read("[\"\",\"a\",\"a\",\"b\"]"))
    assertEquals(listOf(null, "a", null), slots.read("[\"\",\"a\",\" \"]"))
  }

  @Test fun actualGetters_readCurrentPreferenceAndKeepFourFieldsIndependent() {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    val raw = mutableMapOf<String, String>()
    Mockito.`when`(context.getSharedPreferences("immortal_layout", Context.MODE_PRIVATE)).thenReturn(prefs)
    for (key in listOf("assignments", "home_order", "grid_order", "grid_slots")) {
      Mockito.`when`(prefs.getString(key, null)).thenAnswer { raw[key] }
    }
    UserLayout.load(context); UserLayout.loadOrder(context)
    UserLayout.loadGridOrder(context); UserLayout.loadGridSlots(context)
    raw["assignments"] = "{\"actual-app\":\"Room\"}"
    raw["home_order"] = "[\"actual-app\"]"
    raw["grid_order"] = "[\"widget:actual\"]"
    raw["grid_slots"] = "[\"app:actual\",\"\"]"
    assertSame(UserLayout.load(context), UserLayout.load(context))
    assertSame(UserLayout.loadOrder(context), UserLayout.loadOrder(context))
    assertSame(UserLayout.loadGridOrder(context), UserLayout.loadGridOrder(context))
    assertSame(UserLayout.loadGridSlots(context), UserLayout.loadGridSlots(context))
    raw["home_order"] = "[\"changed-app\"]"
    assertEquals(listOf("changed-app"), UserLayout.loadOrder(context))
    assertEquals(listOf("widget:actual"), UserLayout.loadGridOrder(context))
    assertEquals(listOf("app:actual", null), UserLayout.loadGridSlots(context))
    raw.clear()
    assertTrue(UserLayout.load(context).isEmpty())
    assertTrue(UserLayout.loadOrder(context).isEmpty())
    assertTrue(UserLayout.loadGridOrder(context).isEmpty())
    assertTrue(UserLayout.loadGridSlots(context).isEmpty())
  }

  @Test fun nonpositiveRetentionLimit_isRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      ParsedLayoutSnapshot("", { it }, maxRawChars = 0)
    }
  }
}
