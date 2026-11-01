/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class NightBrightnessDeliveryTest {
  private class DeliveryFixture(apiLevel: Int = 29, initial: Float = .03f) {
    var window = initial
    val writes = mutableListOf<Float>()
    val delivery = NightBrightnessDelivery(
        apiLevel = apiLevel,
        readWindow = { window },
        writeWindow = { window = it; writes.add(it) })
  }

  @Test fun legacySameBucketWritesPreserveTheLatestLogicalLevelWithoutWindowWrites() {
    for (api in listOf(24, 28, 29)) {
      val f = DeliveryFixture(apiLevel = api)
      for (level in listOf(.0301f, .031f, .0305f)) {
        f.delivery.write(level) // All truncate to hardware level 7.
        assertEquals("API $api logical level", level, f.delivery.read(), 0f)
        assertEquals("Repeated reads must retain the logical level", level, f.delivery.read(), 0f)
        assertEquals(.03f, f.window, 0f)
        assertTrue("API $api must suppress redundant hardware writes", f.writes.isEmpty())
      }
    }
  }

  @Test fun legacyChangedBucketsWriteTheOriginalFloatInBothDirections() {
    val f = DeliveryFixture()
    f.delivery.write(.034f) // 7 -> 8.
    f.delivery.write(.035f) // Still 8.
    assertEquals(.035f, f.delivery.read(), 0f)
    f.delivery.write(.03f) // 8 -> 7.
    assertEquals(listOf(.034f, .03f), f.writes)
    assertEquals(.03f, f.window, 0f)
    assertEquals(.03f, f.delivery.read(), 0f)
  }

  @Test fun legacyBucketsUseAospTruncationRatherThanRoundingOrADeltaThreshold() {
    val f = DeliveryFixture(initial = .001f)
    f.delivery.write(.0039f) // Less than 1/255: still hardware level 0.
    assertTrue(f.writes.isEmpty())
    assertEquals(.0039f, f.delivery.read(), 0f)
    f.delivery.write(.004f) // A tiny float change crosses into hardware level 1.
    assertEquals(listOf(.004f), f.writes)
    assertEquals(.004f, f.window, 0f)
  }

  @Test fun settlingWritesTheExactEndpointDespiteAnAlreadyMatchingHardwareBucket() {
    val f = DeliveryFixture()
    f.delivery.write(.011f)
    f.delivery.write(.0102f) // Logical progress continues in hardware level 2.
    assertEquals(.011f, f.window, 0f)
    assertEquals(.0102f, f.delivery.read(), 0f)
    f.delivery.settle(.01f)
    assertEquals(listOf(.011f, .01f), f.writes)
    assertEquals(.01f, f.window, 0f)
    assertEquals(.01f, f.delivery.read(), 0f)
    f.delivery.settle(.01f)
    f.delivery.write(.01f)
    assertEquals("An identical settled endpoint must not be written twice", 2, f.writes.size)
  }

  @Test fun settlingFlushesTheLogicalEndpointWhenItAlreadyEqualsTheCachedRead() {
    val f = DeliveryFixture(initial = .011f)
    f.delivery.write(.01f)
    assertEquals(.01f, f.delivery.read(), 0f)
    assertEquals(.011f, f.window, 0f)
    assertTrue(f.writes.isEmpty())
    f.delivery.settle(.01f)
    assertEquals(listOf(.01f), f.writes)
    assertEquals(.01f, f.window, 0f)
    f.delivery.settle(.01f)
    assertEquals(1, f.writes.size)
  }

  @Test fun settlingZeroPreservesTheExactEndpointWithinHardwareLevelZero() {
    val f = DeliveryFixture(initial = .001f)
    f.delivery.write(0f)
    assertEquals(0f, f.delivery.read(), 0f)
    assertEquals(.001f, f.window, 0f)
    f.delivery.settle(0f)
    assertEquals(listOf(0f), f.writes)
    assertEquals(0f, f.window, 0f)
    f.delivery.settle(0f)
    assertEquals(1, f.writes.size)
  }

  @Test fun defaultSentinelTransitionsAlwaysReachTheWindow() {
    val f = DeliveryFixture(initial = -1f)
    for (level in listOf(.001f, -1f, 0f, -1f)) {
      f.delivery.write(level)
      assertEquals(level, f.window, 0f)
      assertEquals(level, f.delivery.read(), 0f)
    }
    assertEquals(listOf(.001f, -1f, 0f, -1f), f.writes)
    f.delivery.settle(-1f)
    f.delivery.settle(-1f)
    assertEquals("Settling the current default sentinel must not duplicate it", 4, f.writes.size)
  }

  @Test fun negativeAndAboveRangeValuesAreNeverMergedWithValidBoundaryBuckets() {
    for ((valid, invalid) in listOf(0f to -.001f, 1f to 1.0001f)) {
      val f = DeliveryFixture(initial = valid)
      f.delivery.write(invalid)
      assertEquals(invalid, f.window, 0f)
      assertEquals(invalid, f.delivery.read(), 0f)
      f.delivery.write(valid)
      assertEquals(listOf(invalid, valid), f.writes)
      assertEquals(valid, f.window, 0f)
    }
  }

  @Test fun differentInvalidValuesPassThroughWithoutQuantizationOrClamping() {
    val f = DeliveryFixture(initial = 0f)
    val levels = listOf(-.001f, -.002f, -1f, -2f, 1.0001f, 1.0002f,
        Float.POSITIVE_INFINITY, Float.NaN, Float.NEGATIVE_INFINITY, 0f)
    for (level in levels) {
      val before = f.writes.size
      f.delivery.write(level)
      assertEquals("Invalid values must not share a hardware bucket", before + 1, f.writes.size)
      assertEquals(level.toRawBits(), f.window.toRawBits())
      assertEquals(level.toRawBits(), f.delivery.read().toRawBits())
    }
    assertEquals(levels.map { it.toRawBits() }, f.writes.map { it.toRawBits() })
  }

  @Test fun externalSameBucketWindowChangesInvalidateTheLogicalCache() {
    val f = DeliveryFixture()
    f.delivery.write(.031f)
    f.window = .0302f // An external override can change the float without changing the bucket.
    assertEquals(.0302f, f.delivery.read(), 0f)
    assertEquals(.0302f, f.delivery.read(), 0f)
    f.delivery.write(.0305f)
    assertTrue(f.writes.isEmpty())
    assertEquals(.0305f, f.delivery.read(), 0f)
    assertEquals(.0302f, f.window, 0f)
  }

  @Test fun externalBucketAndDefaultOverridesBecomeTheNextDeliveryBaseline() {
    val f = DeliveryFixture()
    f.delivery.write(.031f)
    f.window = .05f
    assertEquals(.05f, f.delivery.read(), 0f)
    f.delivery.write(.031f)
    assertEquals(listOf(.031f), f.writes)
    f.window = -1f
    assertEquals(-1f, f.delivery.read(), 0f)
    f.delivery.write(.031f)
    assertEquals(listOf(.031f, .031f), f.writes)
    assertEquals(.031f, f.window, 0f)
  }

  @Test fun aWriteDetectsExternalWindowChangesWithoutAnExplicitReadFirst() {
    val f = DeliveryFixture()
    f.delivery.write(.031f)
    f.window = .05f
    f.delivery.write(.0305f)
    assertEquals(listOf(.0305f), f.writes)
    assertEquals(.0305f, f.window, 0f)
    assertEquals(.0305f, f.delivery.read(), 0f)
  }

  @Test fun modernApisDeliverEveryDifferentFloatIncludingSubOneOver255Updates() {
    for (api in listOf(30, 36)) {
      val f = DeliveryFixture(apiLevel = api)
      val levels = listOf(.0301f, .0302f, .03005f)
      for (level in levels) {
        f.delivery.write(level)
        assertEquals(level, f.window, 0f)
        assertEquals(level, f.delivery.read(), 0f)
        f.delivery.write(level)
        f.delivery.settle(level)
      }
      assertEquals("API $api must only discard identical floats", levels, f.writes)
    }
  }

  private data class Frame(val atMs: Long, val logical: Float, val hardware: Int)

  /** Real fades with only the clock, callback queue, and window boundary supplied by the test. */
  private class FadeFixture(prior: Float, systemLevel: Float, useDelivery: Boolean) {
    var now = 0L
    var window = prior
    var system = systemLevel
    val writes = mutableListOf<Float>()
    val pending = linkedMapOf<Runnable, Long>()
    val frames = mutableListOf<Frame>()
    private fun writeWindow(level: Float) {
      window = level
      writes.add(level)
    }
    private val delivery = if (useDelivery) NightBrightnessDelivery(
        apiLevel = 29, readWindow = { window }, writeWindow = ::writeWindow) else null
    fun read(): Float = delivery?.read() ?: window
    fun hardware(): Int = ((if (window < 0f) system else window) * 255f).toInt()
    val fade = NightBrightnessFade(
        priorBrightness = prior,
        readBrightness = ::read,
        readSystemBrightness = { system },
        writeBrightness = { level ->
          if (delivery == null) writeWindow(level) else delivery.write(level)
        },
        nowMs = { now },
        postDelayed = { task, delay -> pending[task] = now + delay },
        removeCallbacks = { pending.remove(it) },
        settleBrightness = { level ->
          if (delivery == null) writeWindow(level) else delivery.settle(level)
        })

    fun advance(ms: Long) {
      val end = now + ms
      while (pending.values.minOrNull()?.let { it <= end } == true) {
        val next = pending.minBy { it.value }
        now = next.value
        pending.remove(next.key)
        next.key.run()
        frames.add(Frame(now, read(), hardware()))
      }
      now = end
    }
  }

  private class FadeCohort(prior: Float = .03f, system: Float = .03f) {
    val baseline = FadeFixture(prior, system, useDelivery = false)
    val delivered = FadeFixture(prior, system, useDelivery = true)

    fun both(action: (FadeFixture) -> Unit) {
      action(baseline)
      action(delivered)
      assertParity()
    }
    fun advance(ms: Long) = both { it.advance(ms) }

    fun assertParity() {
      assertEquals("Logical brightness must match the unsuppressed fade", baseline.read(),
          delivered.read(), 0f)
      assertEquals("Current hardware brightness", baseline.hardware(), delivered.hardware())
      assertEquals("Callback count", baseline.frames.size, delivered.frames.size)
      for ((index, pair) in baseline.frames.zip(delivered.frames).withIndex()) {
        val (expected, actual) = pair
        assertEquals("Callback $index time", expected.atMs, actual.atMs)
        assertEquals("Callback $index logical level", expected.logical, actual.logical, 0f)
        assertEquals("Callback $index hardware level", expected.hardware, actual.hardware)
      }
      assertEquals("Both fades must keep the same schedule", baseline.pending.values.toList(),
          delivered.pending.values.toList())
    }
  }

  @Test fun pairedThreeSecondNearDarkFadesKeepEveryHardwareLevelWhileDroppingWrites() {
    val pair = FadeCohort()
    pair.both { it.fade.setActive(true) }
    for (target in listOf(.01f, .03f)) {
      val baselineBefore = pair.baseline.writes.size
      val deliveredBefore = pair.delivered.writes.size
      pair.both { it.fade.setNight(true, target) }
      pair.advance(1500)
      assertTrue(pair.delivered.read() in .018f.. .019f)
      pair.advance(1499)
      assertTrue(pair.delivered.read() != target)
      assertEquals(1, pair.delivered.pending.size)
      pair.advance(1)
      assertEquals(target, pair.baseline.window, 0f)
      assertEquals(target, pair.delivered.window, 0f)
      assertTrue(pair.delivered.pending.isEmpty())
      val baselineWrites = pair.baseline.writes.size - baselineBefore
      val deliveredWrites = pair.delivered.writes.size - deliveredBefore
      assertTrue("The baseline must exercise the real 3-second ramp", baselineWrites in 80..91)
      assertTrue("Only five bucket crossings and an exact endpoint are needed",
          deliveredWrites in 1..6)
      assertTrue("Near-dark delivery should remove most window writes",
          deliveredWrites < baselineWrites / 4)
    }
    val settledWrites = pair.delivered.writes.size
    pair.advance(60_000)
    assertEquals(settledWrites, pair.delivered.writes.size)
  }

  @Test fun pairedReversalStartsFromLogicalProgressInsideAnUnchangedHardwareBucket() {
    val pair = FadeCohort()
    pair.both { it.fade.setActive(true); it.fade.setNight(true, .01f) }
    pair.advance(300)
    val logical = pair.delivered.read()
    val window = pair.delivered.window
    assertTrue("The regression needs suppressed progress before reversal", logical != window)
    pair.both { it.fade.setNight(false, .01f) }
    assertEquals(logical, pair.delivered.read(), 0f)
    assertEquals(window, pair.delivered.window, 0f)
    pair.advance(3000)
    assertEquals(.03f, pair.delivered.window, 0f)
    assertTrue(pair.delivered.pending.isEmpty())
  }

  @Test fun pairedCancellationPreservesLogicalProgressAndResumesToTheLatestHiddenTarget() {
    val pair = FadeCohort()
    pair.both { it.fade.setActive(true); it.fade.setNight(true, .01f) }
    pair.advance(600)
    val staleBaseline = pair.baseline.pending.keys.single()
    val staleDelivered = pair.delivered.pending.keys.single()
    val logical = pair.delivered.read()
    val window = pair.delivered.window
    val writes = pair.delivered.writes.size
    pair.both { it.fade.setActive(false) }
    staleBaseline.run()
    staleDelivered.run()
    pair.assertParity()
    pair.advance(6000)
    assertEquals(logical, pair.delivered.read(), 0f)
    assertEquals(window, pair.delivered.window, 0f)
    assertEquals(writes, pair.delivered.writes.size)
    assertTrue(pair.delivered.pending.isEmpty())
    pair.both { it.fade.setNight(true, .02f); it.fade.setActive(true) }
    pair.advance(3000)
    assertEquals(.02f, pair.delivered.window, 0f)
    assertTrue(pair.delivered.pending.isEmpty())
  }

  @Test fun pairedCloseRejectsStaleCallbacksAndFutureRequestsWithoutEndpointWrites() {
    val pair = FadeCohort()
    pair.both { it.fade.setActive(true); it.fade.setNight(true, .01f) }
    pair.advance(600)
    val staleBaseline = pair.baseline.pending.keys.single()
    val staleDelivered = pair.delivered.pending.keys.single()
    val baselineWrites = pair.baseline.writes.toList()
    val deliveredWrites = pair.delivered.writes.toList()
    pair.both { it.fade.close() }
    staleBaseline.run()
    staleDelivered.run()
    pair.both { it.fade.setNight(false, .01f); it.fade.setActive(true) }
    pair.advance(6000)
    assertEquals(baselineWrites, pair.baseline.writes)
    assertEquals(deliveredWrites, pair.delivered.writes)
    assertTrue(pair.delivered.pending.isEmpty())
  }

  @Test fun pairedDefaultRestorationSettlesTheSentinelOnlyAfterTheFullReturnRamp() {
    val pair = FadeCohort(prior = -1f)
    pair.both { it.fade.setActive(true); it.fade.setNight(true, .01f) }
    pair.advance(3000)
    assertEquals(.01f, pair.delivered.window, 0f)
    pair.both { it.fade.setNight(false, .01f) }
    pair.advance(2999)
    assertTrue(pair.delivered.window >= 0f)
    assertFalse(-1f in pair.delivered.writes)
    pair.advance(1)
    assertEquals(-1f, pair.baseline.window, 0f)
    assertEquals(-1f, pair.delivered.window, 0f)
    assertEquals(-1f, pair.delivered.read(), 0f)
    assertEquals(1, pair.delivered.writes.count { it == -1f })
    assertTrue(pair.delivered.pending.isEmpty())
    pair.both { it.fade.setActive(false); it.fade.setActive(true) }
    pair.advance(6000)
    assertEquals(1, pair.delivered.writes.count { it == -1f })
  }
}
