package com.immortal.launcher

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntiBurnInTest {

  @Test
  fun shift_staysWithinBounds() {
    val max = 6f
    var t = 0L
    while (t <= 600_000L) { // sample a 10-minute span
      val s = AntiBurnIn.shift(t, max)
      assertTrue("x out of range at $t: ${s.x}", s.x in -max..max)
      assertTrue("y out of range at $t: ${s.y}", s.y in -max..max)
      t += 250L
    }
  }

  @Test
  fun shift_isDeterministic() {
    assertEquals(AntiBurnIn.shift(123_456L, 8f), AntiBurnIn.shift(123_456L, 8f))
  }

  @Test
  fun shift_startsCentredOnXAndExtremeOnY() {
    val s = AntiBurnIn.shift(0L, 10f)
    assertEquals(0f, s.x, 1e-4f) // sin(0) = 0
    assertEquals(10f, s.y, 1e-4f) // cos(0) = 1
  }

  @Test
  fun shift_actuallyMoves() {
    // Two samples a minute apart must differ on both axes — it's not a no-op.
    val a = AntiBurnIn.shift(0L, 10f)
    val b = AntiBurnIn.shift(60_000L, 10f)
    assertTrue(abs(a.x - b.x) > 0.5f)
    assertTrue(abs(a.y - b.y) > 0.5f)
  }

  @Test
  fun shift_scalesLinearlyWithMaxPx() {
    // Same instant, double the radius → double the offset.
    val small = AntiBurnIn.shift(40_000L, 5f)
    val big = AntiBurnIn.shift(40_000L, 10f)
    assertEquals(small.x * 2f, big.x, 1e-3f)
    assertEquals(small.y * 2f, big.y, 1e-3f)
  }
}
