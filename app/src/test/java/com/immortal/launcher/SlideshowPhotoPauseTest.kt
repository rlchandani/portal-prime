/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class SlideshowPhotoPauseTest {
  private class Fixture {
    val deck = listOf("A", "B", "C", "D")
    val navigation = SlideshowNavigation()
    val presented = mutableListOf<String>()
    val discarded = mutableListOf<String>()
    var held: String? = null
    val admission = SlideshowPhotoPause<String>(
        onHeldChanged = { held = it }, onDiscard = { discarded += it })

    init {
      navigation.request(1, deck)
      navigation.commit("A", null)
      presented += "A"
    }

    fun request(direction: Int = 1, automatic: Boolean = true): String {
      // The controller cancels an automatic cursor before explicit navigation.
      if (!automatic) {
        admission.beforeManualNavigation {
          admission.cancel()
          navigation.cancelRequest()
        }
      }
      val key = deck[navigation.request(direction, deck)]
      admission.requested(automatic)
      return key
    }

    fun decoded(key: String) {
      admission.deliver(key) {
        navigation.commit(it, null)
        presented += it
      }
    }
  }

  @Test fun automaticDecodeCompletingAfterPauseKeepsTheDisplayedPhotoUntilResume() {
    val frame = Fixture()
    assertEquals("B", frame.request())
    frame.admission.setPaused(true)
    frame.decoded("B") // Deterministic completion of the already-requested decode.
    assertEquals(listOf("A"), frame.presented)
    assertEquals("A", frame.navigation.currentKey)
    assertEquals("B", frame.held)
    assertTrue(frame.admission.pending)
    assertTrue("Delivered B establishes its fresh dwell", frame.admission.setPaused(false))
    assertEquals(listOf("A", "B"), frame.presented)
    assertNull(frame.held)
    assertFalse(frame.admission.pending)
    assertEquals("C", frame.request())
  }

  @Test fun resumeBeforeDecodeFinishesKeepsTheRequestPendingSoNoPhotoIsSkipped() {
    val frame = Fixture()
    assertEquals("B", frame.request())
    frame.admission.setPaused(true)
    assertFalse("No delivery has established a fresh dwell yet", frame.admission.setPaused(false))
    assertTrue("Resume must wait for B rather than arm another advance", frame.admission.pending)
    frame.decoded("B")
    assertEquals(listOf("A", "B"), frame.presented)
    assertFalse(frame.admission.pending)
    assertEquals("C", frame.request())
  }

  @Test fun manualNextReplacesHeldAutomaticDecodeWithoutSkippingItsPhoto() {
    val frame = Fixture()
    frame.request()
    frame.admission.setPaused(true)
    frame.decoded("B")
    assertEquals("B", frame.request(automatic = false))
    assertEquals(listOf("B"), frame.discarded)
    frame.decoded("B")
    assertEquals(listOf("A", "B"), frame.presented)
    assertNull(frame.held)
    assertFalse("Manual B was already presented", frame.admission.setPaused(false))
    assertEquals(listOf("A", "B"), frame.presented)
    assertEquals("C", frame.request())
  }

  @Test fun manualPreviousWhilePausedUsesAcceptedHistoryInsteadOfTheAutomaticCursor() {
    val frame = Fixture()
    frame.request()
    frame.decoded("B")
    assertEquals("C", frame.request())
    frame.admission.setPaused(true)
    frame.decoded("C")
    assertEquals("A", frame.request(direction = -1, automatic = false))
    frame.decoded("A")
    assertEquals(listOf("A", "B", "A"), frame.presented)
    assertEquals(listOf("C"), frame.discarded)
    frame.admission.setPaused(false)
    assertEquals("B", frame.request())
  }

  @Test fun heldOwnershipRemainsUntilPresentationTakesItThenClearsOnce() {
    var held: String? = null
    val ownership = mutableListOf<String?>()
    val gate = SlideshowPhotoPause<String>(
        onHeldChanged = { held = it; ownership += it }, onDiscard = { fail("Unexpected discard") })
    gate.requested(true)
    gate.setPaused(true)
    gate.deliver("B") { assertEquals("B", held) }
    assertEquals(listOf("B"), ownership)
    gate.setPaused(false)
    assertEquals(listOf("B", null), ownership)
    gate.setPaused(false)
    gate.close()
    assertEquals(listOf("B", null), ownership)
  }

  @Test fun cancelDiscardsHeldPixelsOnceAndResumeCannotPresentThem() {
    val frame = Fixture()
    frame.request()
    frame.admission.setPaused(true)
    frame.decoded("B")
    frame.admission.cancel()
    frame.admission.cancel()
    frame.admission.setPaused(false)
    assertEquals(listOf("A"), frame.presented)
    assertEquals(listOf("B"), frame.discarded)
    assertNull(frame.held)
  }

  @Test fun disposalDiscardsHeldAndLatePixelsWithoutResurrectingTheFrame() {
    val frame = Fixture()
    frame.request()
    frame.admission.setPaused(true)
    frame.decoded("B")
    frame.admission.close()
    frame.admission.close()
    frame.admission.setPaused(false)
    frame.decoded("C")
    assertEquals(listOf("A"), frame.presented)
    assertEquals(listOf("B", "C"), frame.discarded)
    assertNull(frame.held)
  }

  @Test fun hostSuspensionQueuesCompletionAndReturningDoesNotUndoUserPause() {
    val frame = Fixture()
    val callbacks = ArrayDeque<Runnable>()
    val ui = PausableFrameCallbacks(
        clock = { 0L },
        schedule = { callback, _ -> callbacks.addLast(callback); true },
        cancel = { callbacks.remove(it) })
    frame.request()
    frame.admission.setPaused(true)
    ui.setActive(false)
    ui.post { frame.decoded("B") }
    assertTrue(callbacks.isEmpty())
    ui.setActive(true)
    while (callbacks.isNotEmpty()) callbacks.removeFirst().run()
    assertEquals(listOf("A"), frame.presented)
    assertEquals("B", frame.held)
    frame.admission.setPaused(false)
    assertEquals(listOf("A", "B"), frame.presented)
    ui.close()
    frame.admission.close()
  }

  @Test fun resumeUnderANightCoverWaitsForHostReveal() {
    val frame = Fixture()
    frame.request()
    frame.admission.setPaused(true)
    frame.decoded("B")
    frame.admission.setHostActive(false)
    frame.admission.setPaused(false)
    assertEquals(listOf("A"), frame.presented)
    assertEquals("B", frame.held)
    frame.admission.setHostActive(true)
    assertEquals(listOf("A", "B"), frame.presented)
    assertNull(frame.held)
  }

  @Test fun heldPixelsStayBudgetedAndTransferToTheStageWithoutDoubleCounting() {
    val budget = SlideshowPixelBudget(48)
    val displayed = SlideshowPixelBudget.Entry(Any(), 12)
    val decoded = SlideshowPixelBudget.Entry(Any(), 12)
    val gate = SlideshowPhotoPause<SlideshowPixelBudget.Entry>(
        onHeldChanged = { budget.setOwner("pause", listOfNotNull(it)) },
        onDiscard = { fail("Unexpected discard") })
    budget.setOwner("stage", listOf(displayed))
    gate.requested(true)
    gate.setPaused(true)
    gate.deliver(decoded) {
      assertEquals(24, budget.used())
      budget.setOwner("stage", listOf(it))
      assertEquals(12, budget.used())
    }
    assertEquals(24, budget.used())
    gate.setPaused(false)
    assertEquals(12, budget.used())
    gate.close()
    assertEquals(12, budget.used())
  }

  @Test fun disposalKeepsPixelsBudgetedUntilTheyAreActuallyDiscarded() {
    val budget = SlideshowPixelBudget(48)
    val decoded = SlideshowPixelBudget.Entry(Any(), 12)
    var discards = 0
    val gate = SlideshowPhotoPause<SlideshowPixelBudget.Entry>(
        onHeldChanged = { budget.setOwner("pause", listOfNotNull(it)) },
        onDiscard = {
          assertSame(decoded, it)
          assertEquals("Pixels remain owned while recycling", 12, budget.used())
          discards++
        })
    gate.requested(true)
    gate.setPaused(true)
    gate.deliver(decoded) { fail("Paused pixels must not reach the stage") }
    gate.close()
    gate.close()
    assertEquals(1, discards)
    assertEquals(0, budget.used())
  }

  @Test fun blockedDecodeCompletesDuringHostSuspensionAndCannotReplaceThePausedPhoto() {
    val frame = Fixture()
    val work = SlideshowWork()
    val enteredDecode = CountDownLatch(1)
    val finishDecode = CountDownLatch(1)
    val deliveredToQueue = CountDownLatch(1)
    val callbacks = ConcurrentLinkedQueue<Runnable>()
    val ui = PausableFrameCallbacks(
        clock = { 0L },
        schedule = { callback, _ -> callbacks.add(callback); true },
        cancel = { callbacks.remove(it) })
    try {
      val key = frame.request()
      work.submit { current ->
        enteredDecode.countDown()
        finishDecode.await()
        if (current()) ui.post { frame.decoded(key) }
        deliveredToQueue.countDown()
      }
      assertTrue(enteredDecode.await(2, TimeUnit.SECONDS))
      frame.admission.setPaused(true)
      ui.setActive(false)
      finishDecode.countDown()
      assertTrue(deliveredToQueue.await(2, TimeUnit.SECONDS))
      assertTrue(callbacks.isEmpty())
      ui.setActive(true)
      while (true) (callbacks.poll() ?: break).run()
      assertEquals(listOf("A"), frame.presented)
      assertEquals("B", frame.held)
      frame.admission.setPaused(false)
      assertEquals(listOf("A", "B"), frame.presented)
      assertEquals("C", frame.request())
    } finally {
      finishDecode.countDown()
      work.close()
      ui.close()
      frame.admission.close()
    }
  }

  @Test fun manualNavigationDuringDecodeDoesNotStartFromAnUnseenAutomaticPhoto() {
    val frame = Fixture()
    assertEquals("B", frame.request())
    frame.admission.setPaused(true)
    assertEquals("B", frame.request(automatic = false))
    frame.decoded("B")
    assertEquals(listOf("A", "B"), frame.presented)
    assertTrue(frame.discarded.isEmpty())
  }

  @Test fun aFailedManualPhotoCanRetryTheNextItemWhileStillPaused() {
    val frame = Fixture()
    frame.admission.setPaused(true)
    assertEquals("B", frame.request(automatic = false))
    frame.navigation.skipFailed()
    assertEquals("C", frame.request(automatic = false))
    frame.decoded("C")
    assertEquals(listOf("A", "C"), frame.presented)
    assertNull(frame.held)
    frame.admission.setPaused(false)
    assertEquals("D", frame.request())
  }

  @Test fun rapidManualNextWhilePausedStillAdvancesThePendingManualCursor() {
    val frame = Fixture()
    frame.admission.setPaused(true)
    assertEquals("B", frame.request(automatic = false))
    assertEquals("C", frame.request(automatic = false))
    frame.decoded("C")
    assertEquals(listOf("A", "C"), frame.presented)
    assertNull(frame.held)
  }
}
