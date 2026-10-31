/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Paint
import android.os.LocaleList
import android.text.format.DateFormat
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class QuietNightClockViewTest {
  /** Keep the View framework outside the JVM test; exercise the real lifecycle methods. */
  private class Fixture {
    val context = Mockito.mock(Context::class.java)
    val view = Mockito.mock(QuietNightClockView::class.java, Mockito.CALLS_REAL_METHODS)
    var attached = true
    var refreshes = 0
    var invalidations = 0
    var description = ""
    val clockPaint = paint(0xffb99f87.toInt())
    val datePaint = paint(0xb4b99f87.toInt())
    val pending = linkedSetOf<Runnable>()
    val receivers = linkedSetOf<BroadcastReceiver>()
    val actions = mutableSetOf<String>()

    init {
      val resources = Mockito.mock(Resources::class.java)
      val configuration = Mockito.mock(Configuration::class.java)
      val locales = Mockito.mock(LocaleList::class.java)
      Mockito.`when`(resources.configuration).thenReturn(configuration)
      Mockito.`when`(configuration.locales).thenReturn(locales)
      Mockito.`when`(locales[0]).thenReturn(Locale.US)
      Mockito.`when`(context.getSharedPreferences("immortal_settings", Context.MODE_PRIVATE))
          .thenReturn(Mockito.mock(SharedPreferences::class.java))
      Mockito.doReturn(context).`when`(view).context
      Mockito.doReturn(resources).`when`(view).resources
      Mockito.doAnswer { attached }.`when`(view).isAttachedToWindow
      Mockito.doAnswer { call ->
        pending.remove(call.getArgument<Runnable>(0))
        true
      }.`when`(view).removeCallbacks(Mockito.any(Runnable::class.java))
      Mockito.doAnswer { call ->
        pending.add(call.getArgument(0))
        true
      }.`when`(view).postDelayed(Mockito.any(Runnable::class.java), Mockito.anyLong())
      Mockito.doAnswer { call ->
        description = call.getArgument<CharSequence>(0).toString()
        refreshes++
        null
      }.`when`(view).setContentDescription(Mockito.any(CharSequence::class.java))
      Mockito.doAnswer { invalidations++; null }.`when`(view).invalidate()
      setField("ink", 0xffb99f87.toInt())
      setField("clockPaint", clockPaint)
      setField("datePaint", datePaint)
      QuietNightClockView::class.java.getDeclaredField("tick").apply {
        isAccessible = true
        set(view, type.declaredConstructors.single().apply { isAccessible = true }
            .newInstance(view, context))
      }
      Mockito.doAnswer { call ->
        receivers.add(call.getArgument(0))
        null
      }.`when`(context).registerReceiver(
          Mockito.any(BroadcastReceiver::class.java), Mockito.any(IntentFilter::class.java))
      Mockito.doAnswer { call ->
        receivers.remove(call.getArgument<BroadcastReceiver>(0))
        null
      }.`when`(context).unregisterReceiver(Mockito.any(BroadcastReceiver::class.java))
    }

    private fun setField(name: String, value: Any) {
      QuietNightClockView::class.java.getDeclaredField(name).apply {
        isAccessible = true
        set(view, value)
      }
    }

    fun displayedText(): List<String> = listOf("time", "date").map { name ->
      QuietNightClockView::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(view) as String
      }
    }

    private fun paint(initialColor: Int): Paint {
      var color = initialColor
      return Mockito.mock(Paint::class.java).also { paint ->
        Mockito.doAnswer { color }.`when`(paint).getColor()
        Mockito.doAnswer { color ushr 24 }.`when`(paint).getAlpha()
        Mockito.doAnswer { call ->
          // Paint.setColor also replaces alpha, so the view must restore date opacity.
          color = call.getArgument(0)
          null
        }.`when`(paint).setColor(Mockito.anyInt())
        Mockito.doAnswer { call ->
          color = (color and 0x00ffffff) or (call.getArgument<Int>(0) shl 24)
          null
        }.`when`(paint).setAlpha(Mockito.anyInt())
      }
    }

    fun broadcast(action: String) {
      if (action !in actions) return
      val intent = Mockito.mock(Intent::class.java)
      Mockito.`when`(intent.action).thenReturn(action)
      receivers.toList().forEach { it.onReceive(context, intent) }
    }

    fun detach() {
      // android.jar throws only when our callback reaches the View superclass boundary.
      try {
        QuietNightClockView::class.java.getDeclaredMethod("onDetachedFromWindow").apply {
          isAccessible = true
        }.invoke(view)
      } catch (error: InvocationTargetException) {
        if (error.cause?.message?.startsWith(
            "Method onDetachedFromWindow in android.view.View not mocked.") != true) throw error
      }
    }
  }

  private fun withFixture(block: (Fixture) -> Unit) {
    val fixture = Fixture()
    Mockito.mockStatic(DateFormat::class.java).use { dateFormat ->
      dateFormat.`when`<String> {
        DateFormat.getBestDateTimePattern(Locale.US, "EEEEMMMMd")
      }.thenReturn("EEEE, MMMM d")
      Mockito.mockConstruction(IntentFilter::class.java) { filter, _ ->
        // Record the actual actions registered by the production view.
        Mockito.doAnswer { call ->
          fixture.actions.add(call.getArgument(0))
          null
        }.`when`(filter).addAction(Mockito.anyString())
      }.use {
        Mockito.mockStatic(Color::class.java).use { colors ->
          colors.`when`<Int> { Color.rgb(166, 85, 72) }.thenReturn(0xffa65548.toInt())
          colors.`when`<Int> { Color.rgb(185, 159, 135) }.thenReturn(0xffb99f87.toInt())
          block(fixture)
        }
      }
    }
  }

  @Test fun updateToneRecolorsExistingPaintsAndPreservesDateOpacity() = withFixture { f ->
    f.view.updateTone("red")
    assertEquals(0xffa65548.toInt(), f.clockPaint.color)
    assertEquals(0xb4a65548.toInt(), f.datePaint.color)
    assertEquals(180, f.datePaint.alpha)
    assertEquals(1, f.invalidations)

    f.view.updateTone("warm")
    assertEquals(0xffb99f87.toInt(), f.clockPaint.color)
    assertEquals(0xb4b99f87.toInt(), f.datePaint.color)
    assertEquals(180, f.datePaint.alpha)
    assertEquals(2, f.invalidations)
    assertTrue(f.pending.isEmpty())
    assertTrue(f.receivers.isEmpty())
    assertEquals(0, f.refreshes)
  }

  @Test fun updateToneDoesNothingWhenEffectivePaletteIsUnchanged() = withFixture { f ->
    Mockito.clearInvocations(f.clockPaint, f.datePaint)
    f.view.updateTone("warm")
    f.view.updateTone("unknown")
    assertEquals(0, f.invalidations)
    Mockito.verifyNoInteractions(f.clockPaint, f.datePaint)

    f.view.updateTone("red")
    Mockito.clearInvocations(f.clockPaint, f.datePaint)
    f.view.updateTone("red")
    assertEquals(1, f.invalidations)
    Mockito.verifyNoInteractions(f.clockPaint, f.datePaint)
  }

  @Test fun updateToneRetainsTextMinuteScheduleReceiverAndActiveState() = withFixture { f ->
    f.view.setActive(true)
    val tick = f.pending.single()
    val receiver = f.receivers.single()
    val description = f.description
    val text = f.displayedText()
    Mockito.clearInvocations(f.view, f.context)

    f.view.updateTone("red")
    f.view.updateTone("red")
    assertEquals(text, f.displayedText())
    assertEquals(description, f.description)
    assertEquals(1, f.refreshes)
    assertEquals(2, f.invalidations)
    assertSame(tick, f.pending.single())
    assertSame(receiver, f.receivers.single())
    Mockito.verify(f.view, Mockito.never()).removeCallbacks(Mockito.any(Runnable::class.java))
    Mockito.verify(f.view, Mockito.never()).postDelayed(
        Mockito.any(Runnable::class.java), Mockito.anyLong())
    Mockito.verifyNoInteractions(f.context)

    tick.run()
    assertEquals(2, f.refreshes)
    assertSame(tick, f.pending.single())
    f.broadcast(Intent.ACTION_TIME_CHANGED)
    assertEquals(3, f.refreshes)
    assertSame(receiver, f.receivers.single())
    f.view.setActive(false)
  }

  @Test fun timeAndTimezoneChangesRefreshImmediatelyWithoutWaitingForMinute() = withFixture { f ->
    f.view.setActive(true)
    assertEquals(1, f.refreshes)
    f.broadcast(Intent.ACTION_TIME_CHANGED)
    assertEquals("A changed wall clock must refresh the active view immediately", 2, f.refreshes)
    f.broadcast(Intent.ACTION_TIMEZONE_CHANGED)
    assertEquals("A changed timezone must refresh the active view immediately", 3, f.refreshes)
    f.view.setActive(false)
  }

  @Test fun hiddenClockUnregistersAndRejectsAnAlreadyDispatchedBroadcast() = withFixture { f ->
    f.view.setActive(true)
    val dispatched = f.receivers.singleOrNull()
    assertNotNull("The active clock must observe time changes", dispatched)
    f.view.setActive(false)
    assertTrue(f.receivers.isEmpty())
    assertTrue(f.pending.isEmpty())
    val intent = Mockito.mock(Intent::class.java)
    Mockito.`when`(intent.action).thenReturn(Intent.ACTION_TIME_CHANGED)
    dispatched!!.onReceive(f.context, intent)
    assertEquals(1, f.refreshes)
  }

  @Test fun activationBeforeAttachmentDoesNotRegisterOrRefresh() = withFixture { f ->
    f.attached = false
    f.view.setActive(true)
    assertEquals(0, f.refreshes)
    assertTrue(f.receivers.isEmpty())
  }

  @Test fun resumeRefreshesImmediatelyAndDoesNotDuplicateRegistration() = withFixture { f ->
    f.view.setActive(true)
    f.view.setActive(true)
    assertEquals(2, f.refreshes)
    assertEquals(1, f.receivers.size)
    f.view.setActive(false)
    f.view.setActive(true)
    assertEquals(3, f.refreshes)
    assertEquals(1, f.receivers.size)
    f.view.setActive(false)
  }

  @Test fun timezoneBroadcastRendersTheNewLocalTimeImmediately() = withFixture { f ->
    val original = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("GMT"))
      f.view.setActive(true)
      val before = f.description
      TimeZone.setDefault(TimeZone.getTimeZone("GMT+09:00"))
      f.broadcast(Intent.ACTION_TIMEZONE_CHANGED)
      assertNotEquals(before, f.description)
      assertEquals(1, f.pending.size)
    } finally {
      f.view.setActive(false)
      TimeZone.setDefault(original)
    }
  }

  @Test fun detachCancelsMinuteCallbackAndRejectsLateBroadcastBeforeAttachFlagClears() = withFixture { f ->
    f.view.setActive(true)
    val dispatched = f.receivers.single()
    assertEquals(1, f.pending.size)
    f.detach()
    assertTrue(f.pending.isEmpty())
    assertTrue(f.receivers.isEmpty())
    val intent = Mockito.mock(Intent::class.java)
    Mockito.`when`(intent.action).thenReturn(Intent.ACTION_TIMEZONE_CHANGED)
    dispatched.onReceive(f.context, intent)
    assertEquals(1, f.refreshes)
  }

  @Test fun resumedClockRejectsBroadcastQueuedByThePreviousRegistration() = withFixture { f ->
    f.view.setActive(true)
    val dispatched = f.receivers.single()
    f.view.setActive(false)
    f.view.setActive(true)
    val intent = Mockito.mock(Intent::class.java)
    Mockito.`when`(intent.action).thenReturn(Intent.ACTION_TIME_CHANGED)
    dispatched.onReceive(f.context, intent)
    assertEquals(2, f.refreshes)
    assertEquals(1, f.pending.size)
    f.view.setActive(false)
  }
}
