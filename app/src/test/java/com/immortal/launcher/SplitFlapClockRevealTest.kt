/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.animation.ValueAnimator
import android.view.View
import com.immortal.launcher.ui.clock.SplitFlapClockFaceView
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Runs the shipped reveal/lifecycle methods with only Android framework boundaries mocked. */
class SplitFlapClockRevealTest {
  @Test fun inactiveRevealSnapsSuppliedTimeWithoutStartingFlipAnimators() = fixture { f ->
    f.clock.setHostActive(false)

    f.clock.refreshForReveal(time("2030-01-01T23:59:00Z"), false)
    f.assertSnapped("2359")
    f.clock.refreshForReveal(time("2030-01-02T00:00:00Z"), true)
    f.assertSnapped("0000")

    Mockito.verify(f.view, Mockito.atLeastOnce()).invalidate()
  }

  @Test fun revealRefreshKeepsHostInactiveAndOrdinaryTicksSuspended() = fixture { f ->
    f.clock.setHostActive(false)
    f.clock.refreshForReveal(time("2030-01-01T12:34:00Z"), true)
    assertFalse(f.hostActive())
    f.assertSnapped("1234")
    Mockito.clearInvocations(f.view)

    f.clock.update(time("2030-01-01T12:35:00Z"), false)

    assertFalse(f.hostActive())
    f.assertSnapped("1234")
    Mockito.verifyNoInteractions(f.view)
  }

  @Test fun disposedClockIgnoresRevealRefresh() = fixture { f ->
    f.clock.dispose()
    Mockito.clearInvocations(f.view)

    f.clock.refreshForReveal(time("2030-01-01T12:34:00Z"), true)

    assertTrue(f.get("disposed") as Boolean)
    assertFalse(f.hostActive())
    f.assertSnapped("0817")
    Mockito.verifyNoInteractions(f.view)
  }

  @Test fun resumeAfterRevealSnapsTheLatestMinuteOnItsFirstOrdinaryTick() = fixture { f ->
    f.clock.setHostActive(false)
    f.clock.refreshForReveal(time("2030-01-01T12:34:00Z"), true)
    f.assertSnapped("1234")
    Mockito.clearInvocations(f.view)

    f.clock.setHostActive(true)
    f.clock.update(time("2030-01-01T12:35:00Z"), false)

    assertTrue(f.hostActive())
    f.assertSnapped("1235")
    Mockito.verify(f.view, Mockito.atLeastOnce()).invalidate()
  }

  @Test fun ordinaryResumeStillSnapsCurrentTimeWithoutARevealRefresh() = fixture { f ->
    f.clock.setHostActive(false)
    f.clock.update(time("2030-01-01T12:34:00Z"), true)
    f.assertSnapped("0817")

    f.clock.setHostActive(true)
    f.clock.update(time("2030-01-01T12:35:00Z"), false)

    assertTrue(f.hostActive())
    f.assertSnapped("1235")
    Mockito.verify(f.view, Mockito.atLeastOnce()).invalidate()
  }

  private class Fixture {
    val clock = Mockito.mock(SplitFlapClockFaceView::class.java, Mockito.CALLS_REAL_METHODS)
    val view = Mockito.mock(View::class.java)

    init {
      // CALLS_REAL_METHODS skips the Android constructor; seed its retained clock state.
      set("spec", ClockSpec())
      set("timeFmt", SimpleDateFormat("HHmm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
      })
      set("displayedDigits", "0817".toCharArray())
      set("pendingDigits", "0817".toCharArray())
      set("flipProgress", FloatArray(4))
      set("animators", arrayOfNulls<ValueAnimator>(4))
      set("hostActive", true)
      set("disposed", false)
      set("refreshImmediately", false)
      set("view", view)
      Mockito.doReturn(view).`when`(clock).view
    }

    fun hostActive(): Boolean = get("hostActive") as Boolean

    fun assertSnapped(expected: String) {
      assertArrayEquals(expected.toCharArray(), get("displayedDigits") as CharArray)
      assertArrayEquals(expected.toCharArray(), get("pendingDigits") as CharArray)
      assertArrayEquals(FloatArray(4), get("flipProgress") as FloatArray, 0f)
      assertTrue("A snapped clock must retain no flip animators",
          (get("animators") as Array<*>).all { it == null })
    }

    fun get(name: String): Any? = field(name).get(clock)
    private fun set(name: String, value: Any) = field(name).set(clock, value)
    private fun field(name: String) =
        SplitFlapClockFaceView::class.java.getDeclaredField(name).apply { isAccessible = true }
  }

  private fun time(iso: String): Date = Date.from(Instant.parse(iso))

  private fun fixture(block: (Fixture) -> Unit) {
    // Fail at the factory boundary if a reveal or first resumed tick tries to animate.
    Mockito.mockStatic(ValueAnimator::class.java, org.mockito.stubbing.Answer<Any?> {
      throw AssertionError("Reveal/resume must not call ValueAnimator.${it.method.name}")
    }).use { animators ->
      block(Fixture())
      animators.verifyNoInteractions()
    }
  }
}
