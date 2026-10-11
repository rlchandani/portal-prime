/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.Window
import android.view.ViewTreeObserver
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class SettingsEntryCaptureTest {
  private class Fixture {
    var elapsedMs = 100L
    var elapsedNs = 100_000_000L
    var clockReads = 0
    var onMain = true
    var throwClock = false
    val current = FramePerformanceCapture.javaClass.getDeclaredField("current").apply { isAccessible = true }
    private val constructor = Class.forName("com.immortal.launcher.FramePerformanceCapture\$Session")
        .getDeclaredConstructor(java.lang.Long.TYPE, FramePerformanceRequest::class.java)
        .apply { isAccessible = true }
    private var nextId = 0L
    fun session(phase: String = "settings"): Any =
        constructor.newInstance(++nextId, FramePerformanceRequest("start", 10, phase))
    fun select(session: Any?) { current.set(FramePerformanceCapture, session) }
    fun close(session: Any) {
      session.javaClass.getDeclaredField("closedAt").apply { isAccessible = true }.set(session, elapsedMs)
    }
    fun samples(session: Any): JSONObject =
        (session.javaClass.getDeclaredField("settingsEntryTimeline").apply { isAccessible = true }
            .get(session) as SettingsEntryTimeline).snapshot()
    fun intent(sessionId: Long? = null, entryId: Long? = null): Intent {
      val result = Mockito.mock(Intent::class.java)
      Mockito.`when`(result.hasExtra(FramePerformanceCapture.SETTINGS_ENTRY_SESSION)).thenReturn(sessionId != null)
      Mockito.`when`(result.getLongExtra(FramePerformanceCapture.SETTINGS_ENTRY_SESSION, -1L))
          .thenReturn(sessionId ?: -1L)
      Mockito.`when`(result.getLongExtra(FramePerformanceCapture.SETTINGS_ENTRY_ID, -1L))
          .thenReturn(entryId ?: -1L)
      return result
    }
    fun id(session: Any): Long =
        session.javaClass.getDeclaredField("id").apply { isAccessible = true }.getLong(session)
  }

  private fun withFixture(block: (Fixture) -> Unit) {
    val f = Fixture()
    val previous = f.current.get(FramePerformanceCapture)
    val main = Mockito.mock(Looper::class.java)
    val other = Mockito.mock(Looper::class.java)
    Mockito.mockStatic(SystemClock::class.java).use { clocks ->
      clocks.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer {
        f.clockReads++
        if (f.throwClock) throw IllegalStateException("synthetic clock failure")
        f.elapsedMs
      }
      clocks.`when`<Long> { SystemClock.elapsedRealtimeNanos() }.thenAnswer {
        f.clockReads++
        if (f.throwClock) throw IllegalStateException("synthetic clock failure")
        f.elapsedNs
      }
      Mockito.mockStatic(Looper::class.java).use { loopers ->
        loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
        loopers.`when`<Looper> { Looper.myLooper() }.thenAnswer { if (f.onMain) main else other }
        Mockito.mockConstruction(HandlerThread::class.java) { thread, _ ->
          Mockito.`when`(thread.looper).thenReturn(main)
        }.use {
          Mockito.mockConstruction(Handler::class.java).use {
            try { f.select(null); block(f) } finally { f.select(previous) }
          }
        }
      }
    }
  }

  @Test fun inactiveLaunchPreservesIntentAndAddsNoDiagnosticReads() = withFixture { f ->
    val context = Mockito.mock(Context::class.java)
    val intent = f.intent()
    FramePerformanceCapture.launchSettings(context, intent)
    Mockito.verify(context).startActivity(intent)
    Mockito.verifyNoInteractions(intent)
    assertEquals(0, f.clockReads)
    assertNull(FramePerformanceCapture.beginSettingsCreate(intent))
    assertEquals(0, f.clockReads)
    Mockito.verifyNoInteractions(intent)
  }

  @Test fun closedAndUnrelatedCaptureDoNotInstrumentNavigation() = withFixture { f ->
    for (closed in listOf(false, true)) {
      val s = f.session(if (closed) "settings" else "apps")
      f.select(s)
      if (closed) f.close(s)
      f.clockReads = 0
      val context = Mockito.mock(Context::class.java)
      val intent = f.intent()
      FramePerformanceCapture.launchSettings(context, intent)
      assertNull(FramePerformanceCapture.beginSettingsCreate(intent))
      Mockito.verify(context).startActivity(intent)
      Mockito.verifyNoInteractions(intent)
      assertEquals(0, f.clockReads)
      assertEquals(0, f.samples(s).getInt("entryCount"))
    }
  }

  @Test fun observedLaunchAndLifecycleShareOneEntry() = withFixture { f ->
    val s = f.session(); f.select(s)
    val context = Mockito.mock(Context::class.java)
    val launchIntent = f.intent()
    FramePerformanceCapture.launchSettings(context, launchIntent)
    Mockito.verify(launchIntent).putExtra(FramePerformanceCapture.SETTINGS_ENTRY_SESSION, f.id(s))
    Mockito.verify(launchIntent).putExtra(FramePerformanceCapture.SETTINGS_ENTRY_ID, 1L)
    f.elapsedNs += 20_000_000L
    val report = FramePerformanceCapture.beginSettingsCreate(f.intent(f.id(s), 1L))!!
    f.elapsedNs += 5_000_000L
    assertTrue(report.record(SettingsEntryStage.CREATE_FINISHED))
    f.elapsedNs += 8_000_000L
    assertTrue(report.record(SettingsEntryStage.FIRST_PRE_DRAW))
    val snapshot = f.samples(s)
    assertEquals(1, snapshot.getInt("entryCount"))
    val entry = snapshot.getJSONArray("entries").getJSONObject(0)
    assertTrue(entry.getBoolean("observedLaunch"))
    assertEquals(20.0, entry.getJSONObject("intervalsMs").getDouble("launchToCreate"), 0.0)
    assertEquals(5.0, entry.getJSONObject("intervalsMs").getDouble("createBody"), 0.0)
    assertEquals(33.0, entry.getJSONObject("intervalsMs").getDouble("launchToPreDraw"), 0.0)
  }

  @Test fun unobservedLaunchIsExplicitAndHasNoInventedLatency() = withFixture { f ->
    val s = f.session(); f.select(s)
    assertNotNull(FramePerformanceCapture.beginSettingsCreate(f.intent()))
    val entry = f.samples(s).getJSONArray("entries").getJSONObject(0)
    assertFalse(entry.getBoolean("observedLaunch"))
    assertTrue(entry.getJSONObject("intervalsMs").isNull("launchToCreate"))
  }

  @Test fun replacementCannotAdoptStaleIntentOrRecordOldReport() = withFixture { f ->
    val first = f.session(); f.select(first)
    FramePerformanceCapture.launchSettings(Mockito.mock(Context::class.java), f.intent())
    val report = FramePerformanceCapture.beginSettingsCreate(f.intent(f.id(first), 1L))!!
    val second = f.session(); f.select(second)
    assertNull(FramePerformanceCapture.beginSettingsCreate(f.intent(f.id(first), 1L)))
    assertFalse(report.record(SettingsEntryStage.CREATE_FINISHED))
    assertEquals(3L, f.samples(first).getLong("records"))
    assertEquals(0, f.samples(second).getInt("entryCount"))
  }

  @Test fun closedOrExpiredReportCannotWriteAndClosedDoesNotReadClock() = withFixture { f ->
    for (closed in listOf(false, true)) {
      val s = f.session(); f.select(s)
      val report = FramePerformanceCapture.beginSettingsCreate(f.intent())!!
      if (closed) f.close(s) else f.elapsedMs += 10_001
      f.clockReads = 0
      assertFalse(report.record(SettingsEntryStage.CREATE_FINISHED))
      if (closed) assertEquals(0, f.clockReads)
      assertEquals(1L, f.samples(s).getLong("records"))
    }
  }

  @Test fun clockFailureCannotPreventActualNavigation() = withFixture { f ->
    val s = f.session(); f.select(s); f.throwClock = true
    val context = Mockito.mock(Context::class.java)
    FramePerformanceCapture.launchSettings(context, f.intent())
    Mockito.verify(context).startActivity(Mockito.any(Intent::class.java))
    assertNull(FramePerformanceCapture.beginSettingsCreate(f.intent()))
    assertEquals(0, f.samples(s).getInt("entryCount"))
  }

  @Test fun actualLaunchFailureRemainsFailureAndCannotBecomeCreate() = withFixture { f ->
    val s = f.session(); f.select(s)
    val context = Mockito.mock(Context::class.java)
    val intent = f.intent()
    val failure = IllegalStateException("synthetic start failure")
    Mockito.doThrow(failure).`when`(context).startActivity(intent)
    assertSame(failure, assertThrows(IllegalStateException::class.java) {
      FramePerformanceCapture.launchSettings(context, intent)
    })
    assertNull(FramePerformanceCapture.beginSettingsCreate(f.intent(f.id(s), 1L)))
    val events = f.samples(s).getJSONArray("entries").getJSONObject(0).getJSONObject("eventsMs")
    assertTrue(events.has("launchFailed"))
    assertFalse(events.has("launchReturned"))
  }

  @Test fun staleBindingDoesNotInflateOrObserveAnotherWindow() = withFixture { f ->
    val first = f.session(); f.select(first)
    val report = FramePerformanceCapture.beginSettingsCreate(f.intent())!!
    f.select(f.session())
    val window = Mockito.mock(Window::class.java)
    assertNull(FramePerformanceCapture.bindSettingsEntry(window, report))
    Mockito.verifyNoInteractions(window)
  }

  @Test fun stoppingCaptureRemovesPendingPreDrawAndSuppressesQueuedCallback() = withFixture { f ->
    val s = f.session(); f.select(s)
    s.javaClass.getDeclaredField("expiry").apply { isAccessible = true }.set(s, Runnable {})
    val report = FramePerformanceCapture.beginSettingsCreate(f.intent())!!
    val window = Mockito.mock(Window::class.java)
    val view = Mockito.mock(View::class.java)
    val observer = Mockito.mock(ViewTreeObserver::class.java)
    Mockito.`when`(window.decorView).thenReturn(view)
    Mockito.`when`(view.viewTreeObserver).thenReturn(observer)
    Mockito.`when`(observer.isAlive).thenReturn(true)
    val observation = FramePerformanceCapture.bindSettingsEntry(window, report)!!
    Mockito.verify(observer).addOnPreDrawListener(observation)
    assertTrue(FramePerformanceCapture.stop())
    Mockito.verify(observer).removeOnPreDrawListener(observation)
    assertTrue(observation.onPreDraw())
    assertEquals(1L, f.samples(s).getLong("records"))
    assertEquals(0, (s.javaClass.getDeclaredField("settingsEntryWindows")
        .apply { isAccessible = true }.get(s) as List<*>).size)
  }

  @Test fun backgroundLifecycleIsRejectedButFrameReceiptIsAdmitted() = withFixture { f ->
    val s = f.session(); f.select(s)
    val report = FramePerformanceCapture.beginSettingsCreate(f.intent())!!
    f.onMain = false
    assertFalse(report.record(SettingsEntryStage.CREATE_FINISHED))
    assertTrue(report.record(SettingsEntryStage.FIRST_FRAME_RECEIVED))
    assertEquals(2L, f.samples(s).getLong("records"))
  }
}
