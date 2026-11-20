/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Settings UI probe: every rejection in the target allow-list, nav scope parsing, and bounded draw evidence. */
class SettingsUiProbePolicyCoverageTest {
  private val overview = SettingsUiProbeRoute("appearance", null, null)
  private val display = SettingsUiProbeRoute("appearance", "display", null)
  private val brightness = SettingsUiProbeRoute("appearance", "display", "display.brightness")
  private fun store() = SettingsUiProbeStore(
      setOf("appearance", "photos"), setOf("display", "clock"), setOf("display.brightness"))
  private fun bounds() = SettingsUiProbeBounds(10, 20, 100, 80)
  private fun ready(s: SettingsUiProbeStore, route: SettingsUiProbeRoute = overview, token: Any = Any(), length: Int = 0,
                    results: List<SettingsUiProbeRoute> = emptyList()) {
    assertTrue(s.publish(route, token, length, results))
    s.markDrawn(route, Any())
  }

  @Test fun routeScopesNameTheOverviewAndControl() {
    assertEquals("appearance/overview", overview.scope)
    assertEquals("appearance/overview", overview.geometryScope)
    assertEquals("appearance/display", brightness.scope)
    assertEquals("appearance/display#display.brightness", brightness.geometryScope)
  }

  @Test fun eachKindRejectsAScopeOrTokenItMustNotCarry() {
    val s = store()
    val token = Any()
    assertFalse(s.target("category", "photos", "appearance/overview", null, bounds()))
    assertFalse(s.target("category", "photos", null, token, bounds()))
    assertFalse(s.target("nav", "display", overview.scope, token, bounds()))
    assertFalse(s.target("home", "nothome", null, null, bounds()))
    assertFalse(s.target("home", "home", "appearance/overview", null, bounds()))
    assertFalse(s.target("home", "home", null, token, bounds()))
    assertFalse(s.target("search", "input", "appearance/overview", null, bounds()))
    assertFalse(s.target("search", "input", null, token, bounds()))
    assertFalse("a result needs its query token", s.target("result", "0", null, null, bounds()))
    assertFalse(s.target("result", "0", "appearance/overview", token, bounds()))
    assertFalse("only canonical indices", s.target("result", "01", null, token, bounds()))
    assertFalse(s.target("result", "-1", null, token, bounds()))
    assertFalse(s.target("result", "x", null, token, bounds()))
    assertTrue(s.target("result", "11", null, token, bounds()))
  }

  @Test fun navScopesMustBeAKnownCategoryPageAndOptionalControl() {
    val s = store()
    assertFalse(s.target("nav", "display", null, null, bounds()))
    assertFalse(s.target("nav", "display", "appearance", null, bounds()))
    assertFalse(s.target("nav", "display", "appearance/display/extra", null, bounds()))
    assertFalse(s.target("nav", "display", "garden/overview", null, bounds()))
    assertFalse(s.target("nav", "display", "appearance/garden", null, bounds()))
    assertFalse(s.target("nav", "display", "appearance/display#unknown", null, bounds()))
    assertFalse(s.target("nav", "display", "appearance/display#display.brightness#x", null, bounds()))
    assertTrue(s.target("nav", "display", "appearance/display", null, bounds()))
    assertTrue(s.target("nav", "clock", "appearance/display#display.brightness", null, bounds()))
  }

  @Test fun navTargetsShowOnlyOnTheirOwnControlPane() {
    val s = store()
    ready(s, brightness)
    s.target("nav", "clock", brightness.geometryScope, null, bounds())
    s.target("nav", "display", display.geometryScope, null, bounds())
    assertEquals(listOf(brightness.geometryScope), s.snapshot(500, 500, brightness)!!.targets.map { it.scope })
  }

  @Test fun anEmptyRectangleOnlyRetiresTheSameOwnersOldTarget() {
    val s = store()
    ready(s)
    val owner = Any()
    assertTrue(s.target("home", "home", null, null, bounds(), owner))
    assertFalse(s.target("home", "home", null, null, SettingsUiProbeBounds(10, 30, 20, 30), Any()))
    assertEquals(1, s.storedTargetCount)
    assertFalse(s.target("home", "home", null, null, SettingsUiProbeBounds(10, 30, 20, 30), owner))
    assertEquals(0, s.storedTargetCount)
    assertFalse("nothing to retire", s.target("home", "home", null, null, SettingsUiProbeBounds(10, 30, 20, 30), owner))
  }

  @Test fun aRepeatedTargetReplacesItsRectangle() {
    val s = store()
    ready(s)
    s.target("home", "home", null, null, bounds())
    s.target("home", "home", null, null, SettingsUiProbeBounds(1, 2, 3, 4))
    assertEquals(SettingsUiProbeBounds(1, 2, 3, 4), s.snapshot(500, 500, overview)!!.targets.single().bounds)
  }

  @Test fun removeTargetNeedsEveryIdentifyingFieldToMatch() {
    val s = store()
    ready(s)
    val owner = Any()
    s.target("home", "home", null, null, bounds(), owner)
    s.removeTarget("search", "home", null, null, owner)
    s.removeTarget("home", "other", null, null, owner)
    s.removeTarget("home", "home", "x", null, owner)
    s.removeTarget("home", "home", null, Any(), owner)
    assertEquals(1, s.storedTargetCount)
  }

  @Test fun drawEvidenceIsBoundedOldestFirst() {
    val s = SettingsUiProbeStore(setOf("c"), (0 until 200).map { "p$it" }.toSet(), emptySet())
    val first = SettingsUiProbeRoute("c", "p0", null)
    assertTrue(s.publish(first, Any(), 0, emptyList()))
    s.markDrawn(first, Any())
    for (i in 1..SettingsUiProbeStore.MAX_TARGETS) {
      val r = SettingsUiProbeRoute("c", "p$i", null)
      assertTrue(s.publish(r, Any(), 0, emptyList()))
      s.markDrawn(r, Any())
      s.markDrawn(r, Any()) // redraw of the same route doesn't evict anything
    }
    assertTrue(s.publish(first, Any(), 0, emptyList()))
    assertNull("evicted", s.snapshot(10, 10, first))
    val recent = SettingsUiProbeRoute("c", "p${SettingsUiProbeStore.MAX_TARGETS}", null)
    assertTrue(s.publish(recent, Any(), 0, emptyList()))
    assertNotNull(s.snapshot(10, 10, recent))
  }

  @Test fun markDrawnIgnoresARouteThatIsNotCurrent() {
    val s = store()
    assertTrue(s.publish(overview, Any(), 0, emptyList()))
    s.markDrawn(display, Any())
    assertTrue(s.publish(display, Any(), 0, emptyList()))
    assertNull(s.snapshot(10, 10, display))
  }

  @Test fun snapshotNeedsPositiveHeightAndClipsAwayOffscreenTargets() {
    val s = store()
    ready(s)
    s.target("home", "home", null, null, SettingsUiProbeBounds(10, 300, 20, 400))
    assertNull(s.snapshot(200, 0, overview))
    assertTrue(s.snapshot(200, 200, overview)!!.targets.isEmpty())
  }

  @Test fun clippedRequiresAreaInsideTheScreen() {
    assertNull(SettingsUiProbeBounds(300, 10, 400, 20).clipped(200, 200))
    assertNull(SettingsUiProbeBounds(10, -40, 20, -1).clipped(200, 200))
    assertEquals(SettingsUiProbeBounds(190, 190, 200, 200), SettingsUiProbeBounds(190, 190, 250, 260).clipped(200, 200))
  }

  @Test fun fromWindowRejectsInvertedOrNonFiniteRectangles() {
    assertNull(SettingsUiProbeBounds.fromWindow(0f, 10f, 10f, 10f, 0f, 0f))
    assertNull(SettingsUiProbeBounds.fromWindow(Float.NaN, 0f, 10f, 10f, 0f, 0f))
    assertNull(SettingsUiProbeBounds.fromWindow(0f, Float.NEGATIVE_INFINITY, 10f, 10f, 0f, 0f))
    assertNull(SettingsUiProbeBounds.fromWindow(0f, 0f, 10f, 10f, 0f, Float.POSITIVE_INFINITY))
    assertEquals(SettingsUiProbeBounds(-1, 0, 11, 11), SettingsUiProbeBounds.fromWindow(-0.5f, 0f, 10.2f, 10.9f, 0f, 0f))
  }

  @Test fun resultsBeyondTheQueryResultsAreHiddenAndSnapshotCopiesResults() {
    val s = store()
    val token = Any()
    ready(s, token = token, length = 3, results = listOf(display, brightness))
    s.target("result", "1", null, token, bounds())
    s.target("result", "2", null, token, bounds())
    val snap = s.snapshot(500, 500, overview)!!
    assertEquals(listOf("1"), snap.targets.map { it.id })
    assertEquals(listOf(display, brightness), snap.results)
    assertEquals(3, snap.queryLength)
    assertEquals("appearance/overview", snap.drawnScope)
  }
}
