/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class SettingsUiProbePolicyTest {
  private val overview = SettingsUiProbeRoute("appearance", null, null)
  private val display = SettingsUiProbeRoute("appearance", "display", null)
  private fun store() = SettingsUiProbeStore(
      setOf("appearance", "photos"), setOf("display", "clock"), setOf("display.brightness"))
  private fun publish(s: SettingsUiProbeStore, route: SettingsUiProbeRoute = overview,
      token: Any = Any(), length: Int = 0, results: List<SettingsUiProbeRoute> = emptyList()) {
    assertTrue(s.publish(route, token, length, results))
    s.markDrawn(route, Any())
  }
  private fun bounds() = SettingsUiProbeBounds(10, 20, 100, 80)

  @Test fun requiresCommittedAndDrawnRoute() {
    val s = store()
    assertNull(s.snapshot(2160, 1440, overview))
    assertTrue(s.publish(overview, Any(), 0, emptyList()))
    assertNull(s.snapshot(2160, 1440, overview))
    s.markDrawn(overview, Any())
    assertNotNull(s.snapshot(2160, 1440, overview))
    assertNull(s.snapshot(2160, 1440, display))
  }

  @Test fun oldDrawCannotValidateNewRoute() {
    val s = store()
    publish(s)
    assertTrue(s.publish(display, Any(), 0, emptyList()))
    s.markDrawn(overview, Any())
    assertNull(s.snapshot(2160, 1440, display))
    s.markDrawn(display, Any())
    assertNotNull(s.snapshot(2160, 1440, display))
  }

  @Test fun cachedVisitedPaneKeepsItsDrawEvidenceWhenSelectedAgain() {
    val s = store()
    publish(s)
    publish(s, display)
    assertTrue(s.publish(overview, Any(), 0, emptyList()))
    assertNotNull(s.snapshot(2160, 1440, overview))
  }

  @Test fun disappearedTargetRemovesItsPreviouslyVisibleRectangle() {
    val s = store()
    publish(s)
    s.target("home", "home", null, null, bounds())
    assertFalse(s.target("home", "home", null, null, SettingsUiProbeBounds(10, 10, 10, 10)))
    assertTrue(s.snapshot(2160, 1440, overview)!!.targets.isEmpty())
  }

  @Test fun unknownRouteAndControlCannotLeaveStaleReadyState() {
    val s = store()
    publish(s)
    for (bad in listOf(overview.copy(category = "private-value"),
        overview.copy(page = "private-value"), display.copy(control = "private-value"))) {
      assertFalse(s.publish(bad, Any(), 0, emptyList()))
      assertNull(s.snapshot(2160, 1440, overview))
      publish(s)
    }
  }

  @Test fun inactivePaneTargetsNeverAppearOnCurrentPage() {
    val s = store()
    publish(s)
    assertTrue(s.target("nav", "display", overview.scope, null, bounds()))
    assertTrue(s.target("category", "photos", null, null, bounds()))
    publish(s, display)
    val targets = s.snapshot(2160, 1440, display)!!.targets
    assertEquals(listOf("category"), targets.map { it.kind })
  }

  @Test fun equalLengthSearchReplacementRejectsOldGeometry() {
    val s = store()
    val old = Any()
    val fresh = Any()
    publish(s, token = old, length = 7, results = listOf(display))
    assertTrue(s.target("result", "0", null, old, bounds()))
    publish(s, token = fresh, length = 7, results = listOf(display))
    assertTrue(s.snapshot(2160, 1440, overview)!!.targets.isEmpty())
    assertTrue(s.target("result", "0", null, fresh, bounds()))
    assertEquals(1, s.snapshot(2160, 1440, overview)!!.targets.size)
  }

  @Test fun resultIndexMustStillExistAndQueryMustBeNonEmpty() {
    val s = store()
    val token = Any()
    publish(s, token = token)
    s.target("result", "0", null, token, bounds())
    assertTrue(s.snapshot(2160, 1440, overview)!!.targets.isEmpty())
    publish(s, token = token, length = 7, results = listOf(display))
    s.target("result", "1", null, token, bounds())
    assertEquals(listOf("0"), s.snapshot(2160, 1440, overview)!!.targets.map { it.id })
  }

  @Test fun clipsVisibleTargetsAndDropsOffscreenOrEmptyRectangles() {
    val s = store()
    publish(s)
    s.target("home", "home", null, null, SettingsUiProbeBounds(-10, 20, 30, 50))
    s.target("search", "input", null, null, SettingsUiProbeBounds(500, 20, 550, 50))
    assertEquals(SettingsUiProbeBounds(0, 20, 30, 50),
        s.snapshot(200, 200, overview)!!.targets.single().bounds)
    assertFalse(s.target("home", "home", null, null, SettingsUiProbeBounds(10, 10, 10, 20)))
    assertNull(s.snapshot(0, 200, overview))
  }

  @Test fun searchPopupNeverExposesUnderlyingNavigationAsATouchTarget() {
    val s = store()
    val token = Any()
    publish(s, token = token, length = 7, results = listOf(display))
    s.target("category", "photos", null, null, bounds())
    s.target("nav", "display", overview.scope, null, bounds())
    s.target("search", "input", null, null, SettingsUiProbeBounds(10, 1, 100, 10))
    s.target("result", "0", null, token, bounds())
    assertEquals(listOf("search", "result"), s.snapshot(2160, 1440, overview)!!.targets.map { it.kind })
  }

  @Test fun popupBoundsUseTheirOwnWindowScreenOffset() {
    assertEquals(SettingsUiProbeBounds(27, 357, 127, 438),
        SettingsUiProbeBounds.fromWindow(0f, 0f, 100f, 80.2f, 27f, 357f))
  }

  @Test fun unavailableScreenConversionProducesNoTouchTarget() {
    assertNull(SettingsUiProbeBounds.fromWindow(0f, 0f, 100f, 80f, Float.NaN, 357f))
    assertNull(SettingsUiProbeBounds.fromWindow(0f, 0f, 100f, Float.POSITIVE_INFINITY, 0f, 0f))
    assertNull(SettingsUiProbeBounds.fromWindow(0f, 0f, 0f, 80f, 0f, 0f))
  }

  @Test fun acceptsOnlyFixedTargetIdentifiers() {
    val s = store()
    for ((kind, id, scope) in listOf(Triple("category", "unknown", null),
        Triple("nav", "unknown", overview.scope), Triple("search", "password", null),
        Triple("result", "12", null), Triple("private", "home", null),
        Triple("nav", "display", "private-value"))) {
      assertFalse(s.target(kind, id, scope, Any(), bounds()))
    }
  }

  @Test fun disposedPaneCannotReuseItsPriorDrawReadiness() {
    val s = store()
    val owner = Any()
    assertTrue(s.publish(overview, Any(), 0, emptyList()))
    s.markDrawn(overview, owner)
    publish(s, display)
    s.retireDraw(overview, owner)
    assertTrue(s.publish(overview, Any(), 0, emptyList()))
    assertNull(s.snapshot(2160, 1440, overview))
  }

  @Test fun anotherRequestedControlNeedsItsOwnPaneDraw() {
    val s = store()
    publish(s, display)
    val next = display.copy(control = "display.brightness")
    assertTrue(s.publish(next, Any(), 0, emptyList()))
    assertNull(s.snapshot(2160, 1440, next))
    s.markDrawn(next, Any())
    assertNotNull(s.snapshot(2160, 1440, next))
  }

  @Test fun oldPaneDisposalCannotRetireItsReplacement() {
    val s = store()
    val old = Any()
    val fresh = Any()
    publish(s)
    s.markDrawn(overview, old)
    s.markDrawn(overview, fresh)
    s.retireDraw(overview, old)
    assertNotNull(s.snapshot(2160, 1440, overview))
    s.target("home", "home", null, null, bounds(), old)
    s.target("home", "home", null, null, bounds(), fresh)
    s.removeTarget("home", "home", null, null, old)
    assertEquals(1, s.snapshot(2160, 1440, overview)!!.targets.size)
    s.removeTarget("home", "home", null, null, fresh)
    assertTrue(s.snapshot(2160, 1440, overview)!!.targets.isEmpty())
  }

  @Test fun canonicalCategoryOnlySearchResultIsSupported() {
    val s = SettingsUiProbeStore(setOf("appearance", "advanced"), setOf("display"),
        setOf("immortal.advancedSettings"))
    val result = SettingsUiProbeRoute("advanced", null, "immortal.advancedSettings")
    assertTrue(s.publish(overview, Any(), 8, listOf(result)))
    s.markDrawn(overview, Any())
    assertEquals(result, s.snapshot(2160, 1440, overview)!!.results.single())
  }

  @Test fun boundsStorageIsBoundedAndClearDiscardsEverything() {
    val s = store()
    repeat(1000) { s.target("result", "0", null, Any(), bounds()) }
    assertTrue(s.storedTargetCount <= SettingsUiProbeStore.MAX_TARGETS)
    publish(s)
    s.clear()
    assertEquals(0, s.storedTargetCount)
    assertNull(s.snapshot(2160, 1440, overview))
  }

  @Test fun rejectsInvalidLengthsOrOversizedAndUnknownResultSetsAtomically() {
    val s = store()
    publish(s)
    assertFalse(s.publish(overview, Any(), -1, emptyList()))
    assertFalse(s.publish(overview, Any(), 4097, emptyList()))
    assertFalse(s.publish(overview, Any(), 1, List(13) { display }))
    assertFalse(s.publish(overview, Any(), 1, listOf(display.copy(page = "private"))))
    assertNull(s.snapshot(2160, 1440, overview))
  }
}
