/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Live ownership reads must preserve expiry/authentication without doing full diagnostics. */
class FramePerformanceStateReadTest {
  private class Fixture {
    var now = 100L
    var clockReads = 0
    var memoryReads = 0
    val current = FramePerformanceCapture.javaClass.getDeclaredField("current").apply { isAccessible = true }
    val constructor = Class.forName("com.immortal.launcher.FramePerformanceCapture\$Session")
        .getDeclaredConstructor(java.lang.Long.TYPE, FramePerformanceRequest::class.java).apply { isAccessible = true }
    fun session(id: Long = 1L, sampled: Boolean = false): Any {
      val value = constructor.newInstance(id, FramePerformanceRequest("start", 10, "apps", sampled))
      field(value, "expiry").set(value, Runnable { })
      return value
    }
    fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
    fun select(value: Any?) { current.set(FramePerformanceCapture, value) }
    fun state(): JSONObject = FramePerformanceCapture.stateSnapshot()
    fun assertLight(value: JSONObject) {
      assertFalse(value.has("memory"))
      assertFalse(value.has("hosts"))
      assertFalse(value.has("mainThreadSamples"))
      assertFalse(value.has("slideshowWork"))
      assertEquals(0, memoryReads)
    }
    fun routes(): FleetRoutes {
      val context = Mockito.mock(Context::class.java)
      val prefs = Mockito.mock(SharedPreferences::class.java)
      Mockito.`when`(context.getSharedPreferences("fleet_agent", Context.MODE_PRIVATE)).thenReturn(prefs)
      Mockito.`when`(prefs.getString("token", null)).thenReturn("generated-test-token")
      return FleetRoutes(context)
    }
    fun request(method: String = "GET", authenticated: Boolean = true) = FleetHttpServer.Request(
        method, "/perf/state", "", if (authenticated) mapOf("authorization" to "Bearer generated-test-token") else emptyMap(),
        ByteArrayInputStream(byteArrayOf()), 0)
  }

  private fun fixture(block: (Fixture) -> Unit) {
    val f = Fixture()
    val previous = f.current.get(FramePerformanceCapture)
    val main = Mockito.mock(Looper::class.java)
    Mockito.mockStatic(SystemClock::class.java).use { clock ->
      clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { f.clockReads++; f.now }
      Mockito.mockStatic(Looper::class.java).use { loopers ->
        loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
        Mockito.mockConstruction(HandlerThread::class.java) { thread, _ -> Mockito.`when`(thread.looper).thenReturn(main) }.use {
          Mockito.mockConstruction(Handler::class.java).use {
            Mockito.mockStatic(Debug::class.java).use { memory ->
              memory.`when`<Unit> { Debug.getMemoryInfo(Mockito.any(Debug.MemoryInfo::class.java)) }.thenAnswer {
                f.memoryReads++
                throw IllegalStateException("generated full-memory sentinel")
              }
              try { f.select(null); block(f) } finally { f.select(previous) }
            }
          }
        }
      }
    }
  }

  @Test fun inactiveStateReadDoesNotCreateCaptureOrFullMetrics() = fixture { f ->
    val value = f.state()
    assertTrue(value.getBoolean("ok"))
    assertFalse(value.getBoolean("active"))
    assertFalse(value.getBoolean("collectorRunning"))
    assertEquals(0, value.getInt("activeListeners"))
    assertFalse(value.has("sessionId"))
    assertNull(f.current.get(FramePerformanceCapture))
    f.assertLight(value)
  }

  @Test fun activeStateIncludesCurrentIdentityAndDeadlineWithoutMemoryRead() = fixture { f ->
    val session = f.session(11, sampled = true); f.select(session); f.now += 250
    f.field(session, "attached").setInt(session, 2)
    f.field(session, "sampleMainActive").setBoolean(session, true)
    val value = f.state()
    assertEquals(11L, value.getLong("sessionId"))
    assertEquals("apps", value.getString("phase"))
    assertEquals(10, value.getInt("durationSec"))
    assertEquals(250L, value.getLong("elapsedMs"))
    assertEquals(9_750L, value.getLong("remainingMs"))
    assertTrue(value.getBoolean("active"))
    assertTrue(value.getBoolean("sampleMainActive"))
    assertEquals(2, value.getInt("activeListeners"))
    f.assertLight(value)
  }

  @Test fun replacementIsReadLiveRatherThanCached() = fixture { f ->
    f.select(f.session(21)); assertEquals(21L, f.state().getLong("sessionId"))
    f.select(f.session(22)); assertEquals(22L, f.state().getLong("sessionId"))
    f.assertLight(f.state())
  }

  @Test fun expiredStateRequestsOwnedCleanupAndDoesNotReportActiveSampling() = fixture { f ->
    val session = f.session(sampled = true); f.select(session)
    f.field(session, "sampleMainActive").setBoolean(session, true)
    f.now += 10_001
    val value = f.state()
    assertFalse(value.getBoolean("active"))
    assertFalse(value.getBoolean("sampleMainActive"))
    assertEquals(0L, value.getLong("remainingMs"))
    assertEquals(10_000L, value.getLong("elapsedMs"))
    assertTrue(f.field(session, "stopRequested").getBoolean(session))
    f.assertLight(value)
  }

  @Test fun closedStateRetainsIdentityAndActualListenerCountForCleanupChecks() = fixture { f ->
    val session = f.session(31); f.select(session); f.now += 10
    f.field(session, "attached").setInt(session, 2)
    f.field(session, "closedAt").set(session, f.now)
    val value = f.state()
    assertEquals(31L, value.getLong("sessionId"))
    assertFalse(value.getBoolean("active"))
    assertEquals(2, value.getInt("activeListeners"))
    assertEquals(0L, value.getLong("remainingMs"))
    f.assertLight(value)
  }

  @Test fun fullSnapshotStillRunsExplicitMemoryDiagnostics() = fixture { f ->
    f.select(f.session())
    assertThrows(IllegalStateException::class.java) { FramePerformanceCapture.snapshot() }
    assertEquals(1, f.memoryReads)
  }

  @Test fun authenticatedReadOnlyRouteReturnsLightState() = fixture { f ->
    val response = f.routes().handle(f.request())
    assertEquals(200, response.status)
    f.assertLight(JSONObject(String(requireNotNull(response.jsonBytes), Charsets.UTF_8)))
  }

  @Test fun unauthorizedStateRouteReturnsBeforeReadingCapture() = fixture { f ->
    val response = f.routes().handle(f.request(authenticated = false))
    assertEquals(401, response.status)
    assertEquals(0, f.clockReads)
    assertEquals(0, f.memoryReads)
  }

  @Test fun postToStateRouteCannotChangeOrReadCapture() = fixture { f ->
    val response = f.routes().handle(f.request(method = "POST"))
    assertEquals(405, response.status)
    assertEquals(0, f.clockReads)
    assertNull(f.current.get(FramePerformanceCapture))
  }
}
