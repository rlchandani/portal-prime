/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The floor-switch caches: identity keys and the evicting LRU that releases recorded layers. */
class ControlCenterPlanLayerCacheTest {
  @Test fun sameMeansTheSameObject() {
    val a = listOf(1, 2)
    val b = listOf(1, 2)
    assertEquals(CcSame(a), CcSame(a))
    assertNotEquals("equal content, different object: rebuilt, so not the same", CcSame(a), CcSame(b))
    assertEquals(CcPassKey(CcPlanPass.FLOORS, CcSame(a)), CcPassKey(CcPlanPass.FLOORS, CcSame(a)))
    assertNotEquals(CcPassKey(CcPlanPass.FLOORS, CcSame(a)), CcPassKey(CcPlanPass.WALLS, CcSame(a)))
  }

  @Test fun evictingLruReleasesWhatItDrops() {
    val released = ArrayList<String>()
    val lru = CcEvictingLru<String, String>(2) { released += it }
    lru.put("ground", "G"); lru.put("upper", "U")
    lru["ground"] // ground is now the most recent
    lru.put("outdoor", "O")
    assertEquals(listOf("U"), released)
    assertNull(lru["upper"])
    assertEquals("G", lru.getOrPut("ground") { error("must hit") })
    lru.put("ground", "G2")
    assertEquals("a replaced value is released too", listOf("U", "G"), released)
    lru.clear()
    assertTrue(released.containsAll(listOf("G2", "O")))
    assertEquals(0, lru.size)
  }

  @Test fun aFloorsCanvasKeepsItsPassesAndOneReplacedSet() {
    // One canvas per floor: its five passes, then an HA update replaces the model-keyed three.
    var made = 0
    val released = ArrayList<Int>()
    val lru = CcEvictingLru<CcPassKey, Int>(CcPlanLayers.MAX_LAYERS) { released += it }
    val base = Any()
    val passes = listOf(CcPlanPass.UNDER, CcPlanPass.OVER, CcPlanPass.TOP)
    repeat(3) {
      listOf(CcPlanPass.FLOORS, CcPlanPass.WALLS).forEach { p -> lru.getOrPut(CcPassKey(p, CcSame(base))) { ++made } }
    }
    val first = Any(); val second = Any()
    repeat(2) { passes.forEach { p -> lru.getOrPut(CcPassKey(p, CcSame(first))) { ++made } } }
    passes.forEach { p -> lru.getOrPut(CcPassKey(p, CcSame(second))) { ++made } }
    assertEquals(8, made)
    assertTrue("within budget nothing is dropped", released.isEmpty())
  }

  @Test fun drawKeysCompareInputsByIdentityAndSettingsByValue() {
    val model = Any(); val px = Any(); val paint = Any(); val labels = Any(); val chips = Any()
    val c = CcDark
    fun key(lens: CcLens = CcLens.LIVE, focus: String? = null, m: Any = model) =
        CcDrawKey(CcSame(m), CcSame(px), CcSame(paint), c, CcSame(labels), CcSame(chips), lens, focus, false, true, 1.5f)
    assertEquals(key(), key())
    assertNotEquals(key(), key(lens = CcLens.CLIMATE))
    assertNotEquals(key(), key(focus = "kitchen"))
    assertNotEquals(key(), key(m = Any()))
    assertEquals(CcBaseKey(CcSame(px), c, 1.5f), CcBaseKey(CcSame(px), c, 1.5f))
  }
}

class ControlCenterFloorParkingTest {
  @Test fun visitedFloorsStayComposedInVisitOrder() {
    val p = CcFloorParking<String>(4)
    assertEquals(listOf("ground"), p.show("ground", "g1"))
    p.show("upper", "u1"); p.show("outdoor", "o1")
    val before = p.show("ground", "g2")
    assertEquals("a revisit moves nothing", listOf("ground", "upper", "outdoor"), before)
    assertTrue("same list instance while unchanged (no recomposition of the slots)", before === p.show("upper", "u2"))
    assertEquals("g2", p.frame("ground"))
    assertEquals("u2", p.frame("upper"))
  }

  @Test fun theLeastRecentlyShownFloorLeavesFirst() {
    val p = CcFloorParking<String>(3)
    p.show("a", "1"); p.show("b", "1"); p.show("c", "1")
    p.show("a", "2") // b is now the least recently shown
    assertEquals(listOf("a", "c", "d"), p.show("d", "1"))
    assertNull(p.frame("b"))
    assertEquals("2", p.frame("a"))
  }
}
