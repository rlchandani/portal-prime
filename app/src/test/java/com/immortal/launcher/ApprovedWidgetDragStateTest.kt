/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class ApprovedWidgetDragStateTest {
  private val source = ApprovedWidgetDragState.Bounds("a", 40f, 240f, 100f, 100f)
  private val below = ApprovedWidgetDragState.Bounds("b", 40f, 400f, 100f, 100f)
  private val targets = listOf(source, below)

  @Test fun stationaryFingerDuringScroll_keepsGhostUnderFingerAndFindsLowerCard() {
    val drag = ApprovedWidgetDragState()
    val owner = Any()
    drag.start(owner, source, ApprovedWidgetDragState.Point(80f, 100f), 180f)

    val preview = drag.snapshot(300f, targets)!!
    assertEquals(120f, preview.translationY, .001f)
    // Source was at viewport y=60 before scrolling; its ghost must still be there.
    assertEquals(60f, source.top + preview.translationY - 300f, .001f)
    assertEquals("b", preview.targetKey)
    assertEquals("b", drag.finish(owner, 300f, targets))
  }

  @Test fun fingerMovementAndScroll_areAppliedOnceEach() {
    val drag = ApprovedWidgetDragState()
    val owner = Any()
    drag.start(owner, source, ApprovedWidgetDragState.Point(80f, 100f), 180f)
    drag.move(owner, ApprovedWidgetDragState.Point(110f, 130f))

    val preview = drag.snapshot(300f, targets)!!
    assertEquals(30f, preview.translationX, .001f)
    assertEquals(150f, preview.translationY, .001f)
    assertEquals(90f, source.top + preview.translationY - 300f, .001f)
  }

  @Test fun secondOwnerCannotStartMoveCancelOrFinishTheFirstDrag() {
    val drag = ApprovedWidgetDragState()
    val owner = Any()
    val intruder = Any()
    assertTrue(drag.start(owner, source, ApprovedWidgetDragState.Point(80f, 100f), 180f))
    assertFalse(drag.start(intruder, below, ApprovedWidgetDragState.Point(80f, 260f), 180f))
    assertFalse(drag.move(intruder, ApprovedWidgetDragState.Point(500f, 500f)))
    drag.cancel(intruder)
    assertNull(drag.finish(intruder, 180f, targets))

    val preview = drag.snapshot(180f, targets)!!
    assertEquals("a", preview.key)
    assertEquals(0f, preview.translationX, .001f)
    assertEquals(0f, preview.translationY, .001f)
    assertTrue(drag.isOwner(owner))
  }

  @Test fun cancellationDiscardsDestinationAndAllowsANewOwner() {
    val drag = ApprovedWidgetDragState()
    val owner = Any()
    drag.start(owner, source, ApprovedWidgetDragState.Point(80f, 100f), 180f)
    drag.move(owner, ApprovedWidgetDragState.Point(80f, 260f))
    assertEquals("b", drag.snapshot(180f, targets)!!.targetKey)
    drag.cancel(owner)
    assertNull(drag.snapshot(180f, targets))
    assertNull(drag.finish(owner, 180f, targets))
    assertFalse(drag.move(owner, ApprovedWidgetDragState.Point(80f, 300f)))
    assertTrue(drag.start(Any(), source, ApprovedWidgetDragState.Point(80f, 100f), 180f))
  }

  @Test fun invalidationPreventsStaleReleaseFromFinishingANewerDrag() {
    val drag = ApprovedWidgetDragState()
    val stale = Any()
    val current = Any()
    drag.start(stale, source, ApprovedWidgetDragState.Point(80f, 100f), 180f)
    drag.reset()
    drag.start(current, below, ApprovedWidgetDragState.Point(80f, 260f), 180f)
    assertNull(drag.finish(stale, 300f, targets))
    assertFalse(drag.move(stale, ApprovedWidgetDragState.Point(900f, 900f)))
    assertEquals("b", drag.snapshot(180f, targets)!!.key)
    assertTrue(drag.isOwner(current))
  }

  @Test fun releaseReturnsDestinationOnlyOnce_andEmptySpaceDoesNotMove() {
    val drag = ApprovedWidgetDragState()
    val owner = Any()
    drag.start(owner, source, ApprovedWidgetDragState.Point(80f, 100f), 180f)
    drag.move(owner, ApprovedWidgetDragState.Point(80f, 260f))
    assertEquals("b", drag.snapshot(180f, targets)!!.targetKey)
    assertTrue(drag.isOwner(owner)) // Preview has not ended or committed the session.
    assertEquals("b", drag.finish(owner, 180f, targets))
    assertNull(drag.finish(owner, 180f, targets))
    drag.start(owner, source, ApprovedWidgetDragState.Point(80f, 100f), 180f)
    drag.move(owner, ApprovedWidgetDragState.Point(500f, 100f))
    assertNull(drag.finish(owner, 180f, targets))
  }

  @Test fun rtlPhysicalCoordinates_findTheCardToTheLeftWithoutInvertingTheFinger() {
    val right = ApprovedWidgetDragState.Bounds("right", 300f, 0f, 100f, 100f)
    val left = ApprovedWidgetDragState.Bounds("left", 100f, 0f, 100f, 100f)
    val drag = ApprovedWidgetDragState()
    val owner = Any()
    drag.start(owner, right, ApprovedWidgetDragState.Point(350f, 50f), 0f)
    drag.move(owner, ApprovedWidgetDragState.Point(150f, 50f))
    val preview = drag.snapshot(0f, listOf(right, left))!!
    assertEquals(-200f, preview.translationX, .001f)
    assertEquals("left", preview.targetKey)
    assertEquals("left", drag.finish(owner, 0f, listOf(right, left)))
  }

  @Test fun edgeScrollIsBoundedByTimeAndRemainingScrollableDistance() {
    fun step(y: Float, elapsed: Float, scroll: Float, max: Float) =
        ApprovedWidgetDragState.edgeScrollDelta(y, 600f, 60f, 400f, elapsed, scroll, max)
    assertEquals(0f, step(300f, .02f, 100f, 500f), .001f)
    assertEquals(8f, step(600f, .02f, 100f, 500f), .001f)
    assertEquals(-8f, step(0f, .02f, 100f, 500f), .001f)
    assertEquals(2f, step(600f, .02f, 498f, 500f), .001f)
    assertEquals(-2f, step(0f, .02f, 2f, 500f), .001f)
    assertEquals(0f, step(600f, .02f, 500f, 500f), .001f)
    assertEquals(0f, step(0f, .02f, 0f, 500f), .001f)
    assertEquals(20f, step(900f, 10f, 100f, 500f), .001f)
  }

  @Test fun edgeScrollRampsSmoothly_andSmallViewportsHaveNoOverlappingEdgeBands() {
    fun step(y: Float, height: Float) =
        ApprovedWidgetDragState.edgeScrollDelta(y, height, 60f, 400f, .02f, 100f, 500f)
    assertEquals(0f, step(540f, 600f), .001f)
    assertEquals(2f, step(570f, 600f), .001f)
    assertEquals(8f, step(600f, 600f), .001f)
    assertEquals(0f, step(20f, 40f), .001f)
    assertEquals(0f, step(0f, 0f), .001f)
  }
}
