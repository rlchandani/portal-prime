/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Real routes/actions/history, with admission APIs and Android/worker boundaries controlled. */
class PinnedNavigationRoutesTest {
  @Test fun screensaverLeavesThePinDecisionToMainAdmission() = withFixture { f ->
    val events = mutableListOf<String>()
    val dispatch = PinnedNavigationDispatch(selectFrame = {
      // Admission owns the decision even when a pin arrives after the phone request.
      events += "admission"
      events += "cover"
      accepted()
    })
    val response = f.key(RemoteRoutes(f.context, dispatch), "screensaver")
    assertEquals(200, response.status)
    assertTrue(response.json().getBoolean("ok"))
    assertEquals(listOf("admission", "cover"), events)
    assertTrue(f.launches.isEmpty())
  }

  @Test fun unpinnedSelectionLaunchesTheExistingPreviewIntentOnce() = withFixture { f ->
    var admissions = 0
    val dispatch = PinnedNavigationDispatch(selectFrame = { legacy ->
      admissions++; accepted(legacy())
    })
    val response = f.key(RemoteRoutes(f.context, dispatch), "screensaver")
    assertEquals(200, response.status)
    assertEquals(1, admissions)
    assertEquals(1, f.launches.size)
  }

  @Test fun rejectedSelectionNeverFallsBackToAnActivity() = withFixture { f ->
    val dispatch = PinnedNavigationDispatch(selectFrame = { accepted(false).put("error", "main_timeout") })
    val response = f.key(RemoteRoutes(f.context, dispatch), "screensaver")
    assertEquals(500, response.status)
    assertFalse(response.json().getBoolean("ok"))
    assertTrue(f.launches.isEmpty())
  }

  @Test fun admittedScreensaverTimeoutIsAcceptedAndDoesNotRetryTheLegacyLaunch() = withFixture { f ->
    val dispatch = PinnedNavigationDispatch(selectFrame = { throw PinnedCommandPending() })
    assertPending(f.key(RemoteRoutes(f.context, dispatch), "screensaver"))
    assertTrue(f.launches.isEmpty())
  }

  @Test fun dashboardRetiresOwnedSurfacesBeforeDispatchingItsIntent() = withFixture { f ->
    var retired = false
    Mockito.doAnswer { call ->
      assertTrue("The navigation API must retire the cover before launch", retired)
      f.launches += call.getArgument<Intent>(0)
      null
    }.`when`(f.context).startActivity(Mockito.any(Intent::class.java))
    val dispatch = PinnedNavigationDispatch(navigation = { launch -> retired = true; accepted(launch()) })
    val response = f.key(RemoteRoutes(f.context, dispatch), "dashboard")
    assertEquals(200, response.status)
    assertTrue(response.json().getBoolean("ok"))
    assertEquals(1, f.launches.size)
  }

  @Test fun admittedDashboardTimeoutIsAcceptedWithoutASecondLaunch() = withFixture { f ->
    val dispatch = PinnedNavigationDispatch(navigation = { throw PinnedCommandPending() })
    assertPending(f.key(RemoteRoutes(f.context, dispatch), "dashboard"))
    assertTrue(f.launches.isEmpty())
  }

  @Test fun pinCommandAndStatusPendingResponsesUseTheSameAcceptedContract() = withFixture { f ->
    val dispatch = PinnedNavigationDispatch(
        command = { throw PinnedCommandPending() }, readStatus = { throw PinnedCommandPending() })
    val routes = RemoteRoutes(f.context, dispatch)
    assertPending(f.request(routes, "POST", "/remote/pin", JSONObject().put("action", "unpin")))
    assertPending(f.request(routes, "GET", "/remote/pin", JSONObject()))
  }

  @Test fun coveredBackNeverFallsThroughWhenTheAdmittedRevealFails() = withFixture { f ->
    var admissions = 0
    val dispatch = PinnedNavigationDispatch(back = {
      admissions++; accepted(false).put("result", "covered_dismissed")
    })
    withRemoteInput { input ->
      val response = f.key(RemoteRoutes(f.context, dispatch), "back")
      assertEquals(409, response.status)
      assertEquals(1, admissions)
      Mockito.verify(input, Mockito.never()).performGlobalAction(Mockito.anyInt())
    }
  }

  @Test fun coverArrivingAtBackAdmissionSuppressesTheLegacyAction() = withFixture { f ->
    var admissions = 0
    val dispatch = PinnedNavigationDispatch(
        command = { error("Back must use its admission gate") },
        back = {
          // The gate observes a cover which arrived after the request reached the worker.
          admissions++
          accepted().put("result", "covered_dismissed")
        })
    withRemoteInput { input ->
      val response = f.key(RemoteRoutes(f.context, dispatch), "back")
      assertEquals(200, response.status)
      assertEquals("covered_dismissed", response.json().getString("result"))
      assertEquals(1, admissions)
      Mockito.verify(input, Mockito.never()).performGlobalAction(Mockito.anyInt())
    }
  }

  @Test fun pendingCoveredBackNeverReachesTheUnderlyingApp() = withFixture { f ->
    val dispatch = PinnedNavigationDispatch(back = { throw PinnedCommandPending() })
    withRemoteInput { input ->
      assertPending(f.key(RemoteRoutes(f.context, dispatch), "back"))
      Mockito.verify(input, Mockito.never()).performGlobalAction(Mockito.anyInt())
    }
  }

  @Test fun uncoveredBackStillUsesTheNormalAccessibilityAction() = withFixture { f ->
    withRemoteInput { input ->
      `when`(input.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)).thenReturn(true)
      val dispatch = PinnedNavigationDispatch(back = { legacy -> accepted(legacy()).put("result", "back_requested") })
      val response = f.key(RemoteRoutes(f.context, dispatch), "back")
      assertEquals(200, response.status)
      assertTrue(response.json().getBoolean("dispatched"))
      Mockito.verify(input).performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }
  }

  @Test fun missingAccessibilityIsCheckedOnlyInsideBackAdmission() = withFixture { f ->
    RemoteInput.unregister()
    var admissions = 0
    val dispatch = PinnedNavigationDispatch(back = { legacy ->
      admissions++; accepted(legacy()).put("result", "back_requested")
    })
    val response = f.key(RemoteRoutes(f.context, dispatch), "back")
    assertEquals(1, admissions)
    assertEquals(503, response.status)
    assertEquals("no_accessibility", response.json().getString("error"))
  }

  @Test fun missingAppDoesNotAdmitNavigationOrReleaseThePin() = withFixture { f ->
    var admissions = 0
    val dispatch = PinnedNavigationDispatch(navigation = { admissions++; accepted(it()) })
    val response = f.request(RemoteRoutes(f.context, dispatch), "POST", "/remote/launch",
        JSONObject().put("packageName", "example.missing"))
    assertEquals(404, response.status)
    assertEquals(0, admissions)
    assertTrue(f.launches.isEmpty())
  }

  @Test fun appLaunchRemainsInsideNavigationAdmission() = withFixture { f ->
    val intent = Mockito.mock(Intent::class.java, Mockito.RETURNS_SELF)
    `when`(f.context.packageManager.getLaunchIntentForPackage("example.dashboard")).thenReturn(intent)
    var retired = false
    Mockito.doAnswer { call ->
      assertTrue("The pin must be retired before the selected app launches", retired)
      f.launches += call.getArgument<Intent>(0)
      null
    }.`when`(f.context).startActivity(Mockito.any(Intent::class.java))
    val dispatch = PinnedNavigationDispatch(navigation = { launch -> retired = true; accepted(launch()) })
    val response = f.request(RemoteRoutes(f.context, dispatch), "POST", "/remote/launch",
        JSONObject().put("packageName", "example.dashboard"))
    assertEquals(200, response.status)
    assertEquals(listOf(intent), f.launches)
    Mockito.verify(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  }

  @Test fun admittedAppLaunchTimeoutDoesNotLaunchAgainOnTheHttpWorker() = withFixture { f ->
    val intent = Mockito.mock(Intent::class.java, Mockito.RETURNS_SELF)
    `when`(f.context.packageManager.getLaunchIntentForPackage("example.dashboard")).thenReturn(intent)
    var admissions = 0
    val dispatch = PinnedNavigationDispatch(navigation = { admissions++; throw PinnedCommandPending() })
    assertPending(f.request(RemoteRoutes(f.context, dispatch), "POST", "/remote/launch",
        JSONObject().put("packageName", "example.dashboard")))
    assertEquals(1, admissions)
    assertTrue(f.launches.isEmpty())
  }

  @Test fun presetBackUsesCoveredDismissalWithoutFallingThroughOnRejection() = withFixture { f ->
    var admissions = 0
    val dispatch = PinnedNavigationDispatch(back = { admissions++; accepted(false).put("result", "covered_dismissed") })
    val worker = RemoteRoutes::class.java.getDeclaredMethod("runSteps", JSONArray::class.java)
        .apply { isAccessible = true }
    withRemoteInput { input ->
      worker.invoke(RemoteRoutes(f.context, dispatch),
          JSONArray().put(JSONObject().put("type", "key").put("action", "back")))
      assertEquals(1, admissions)
      Mockito.verify(input, Mockito.never()).performGlobalAction(Mockito.anyInt())
    }
  }

  @Test fun companionScreensaverUsesSelectionAndPreservesPendingInHistory() = withFixture { f ->
    withStoreAndWorker {
      var selections = 0
      val dispatch = PinnedNavigationDispatch(selectFrame = { selections++; throw PinnedCommandPending() })
      val operations = RemoteCompanionOperations(f.context, dispatch)
      val body = JSONObject().put("type", "action").put("action", "screensaver")
      val operation = checkNotNull(operations.start("action", body) { JSONArray().put(operations.action(body)) })
      val completed = checkNotNull(operations.history.find(operation.getString("id")))
      val result = completed.getJSONArray("results").getJSONObject(0)
      assertEquals(1, selections)
      assertTrue(result.getBoolean("ok"))
      assertEquals("completion_unconfirmed", result.getString("result"))
      assertTrue(result.getBoolean("backendMayContinue"))
      assertTrue(completed.getBoolean("backendMayContinue"))
      assertEquals("succeeded", completed.getString("state"))
      assertFalse(completed.getBoolean("retrySupported"))
      assertTrue(f.launches.isEmpty())
    }
  }

  @Test fun companionCoveredBackDoesNotFallThroughAfterRejectedDismissal() = withFixture { f ->
    withStoreAndWorker {
      var admissions = 0
      val dispatch = PinnedNavigationDispatch(back = {
        admissions++; accepted(false).put("result", "covered_dismissed")
      })
      withRemoteInput { input ->
        val result = RemoteCompanionOperations(f.context, dispatch).action(JSONObject().put("action", "back"))
        assertFalse(result.getBoolean("ok"))
        assertEquals(1, admissions)
        Mockito.verify(input, Mockito.never()).performGlobalAction(Mockito.anyInt())
      }
    }
  }

  @Test fun companionCoveredBackPendingIsAcceptedWithoutUnderlyingBack() = withFixture { f ->
    withStoreAndWorker {
      val dispatch = PinnedNavigationDispatch(back = { throw PinnedCommandPending() })
      withRemoteInput { input ->
        val operations = RemoteCompanionOperations(f.context, dispatch)
        val body = JSONObject().put("type", "action").put("action", "back")
        val operation = checkNotNull(operations.start("action", body) { JSONArray().put(operations.action(body)) })
        val completed = checkNotNull(operations.history.find(operation.getString("id")))
        val result = completed.getJSONArray("results").getJSONObject(0)
        assertTrue(completed.getBoolean("backendMayContinue"))
        assertFalse(completed.getBoolean("retrySupported"))
        assertTrue(result.getBoolean("ok"))
        assertEquals("completion_unconfirmed", result.getString("result"))
        assertTrue(result.getBoolean("backendMayContinue"))
        Mockito.verify(input, Mockito.never()).performGlobalAction(Mockito.anyInt())
      }
    }
  }

  private fun accepted(ok: Boolean = true) = JSONObject().put("ok", ok)
  private fun assertPending(response: FleetHttpServer.Response) {
    assertEquals(202, response.status)
    val body = response.json()
    assertTrue(body.getBoolean("ok"))
    assertEquals("completion_unconfirmed", body.getString("result"))
    assertTrue(body.getBoolean("checkStateBeforeRetry"))
    assertFalse(body.has("error"))
  }

  private fun withRemoteInput(block: (AccessibilityService) -> Unit) {
    val input = Mockito.mock(AccessibilityService::class.java)
    RemoteInput.register(input)
    try { block(input) } finally { RemoteInput.unregister() }
  }

  private fun withStoreAndWorker(block: () -> Unit) {
    // No files, network, real worker threads or deadlines are used by these boundary tests.
    val stream = Mockito.mock(FileOutputStream::class.java)
    Mockito.mockConstruction(AtomicFile::class.java) { file, _ ->
      `when`(file.openRead()).thenThrow(FileNotFoundException("Synthetic empty store"))
      `when`(file.startWrite()).thenReturn(stream)
    }.use {
      val worker = Mockito.mock(ExecutorService::class.java)
      Mockito.doAnswer { it.getArgument<Runnable>(0).run(); null }.`when`(worker)
          .execute(Mockito.any(Runnable::class.java))
      Mockito.mockStatic(Executors::class.java).use { executors ->
        executors.`when`<ExecutorService> {
          Executors.newSingleThreadExecutor(Mockito.any(ThreadFactory::class.java))
        }.thenReturn(worker)
        Mockito.mockConstruction(ScheduledThreadPoolExecutor::class.java) { scheduler, _ ->
          val future = Mockito.mock(ScheduledFuture::class.java)
          `when`(scheduler.schedule(Mockito.any(Runnable::class.java), Mockito.anyLong(), Mockito.eq(TimeUnit.MINUTES)))
              .thenReturn(future)
        }.use { block() }
      }
    }
  }

  private fun withFixture(block: (Fixture) -> Unit) {
    Mockito.mockConstruction(Intent::class.java, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
      Mockito.mockStatic(Uri::class.java).use { uris ->
        uris.`when`<Uri> { Uri.parse(Mockito.anyString()) }.thenReturn(Mockito.mock(Uri::class.java))
        block(Fixture())
      }
    }
  }

  private class Fixture {
    val context = Mockito.mock(Context::class.java)
    val launches = mutableListOf<Intent>()
    init {
      val pairing = Mockito.mock(SharedPreferences::class.java)
      val fleet = Mockito.mock(SharedPreferences::class.java)
      val dashboard = Mockito.mock(SharedPreferences::class.java)
      `when`(context.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(pairing)
      `when`(context.getSharedPreferences("fleet_agent", Context.MODE_PRIVATE)).thenReturn(fleet)
      `when`(context.getSharedPreferences("immortal_home_dashboard", Context.MODE_PRIVATE)).thenReturn(dashboard)
      `when`(fleet.getString("token", null)).thenReturn("synthetic-pinned-navigation-token")
      `when`(dashboard.getString("url", "")).thenReturn("https://dashboard.invalid/")
      `when`(context.packageManager).thenReturn(Mockito.mock(PackageManager::class.java))
      `when`(context.noBackupFilesDir).thenReturn(File("/synthetic/immortal"))
      Mockito.doAnswer { launches += it.getArgument<Intent>(0); null }.`when`(context)
          .startActivity(Mockito.any(Intent::class.java))
    }
    fun key(routes: RemoteRoutes, action: String) =
        request(routes, "POST", "/remote/key", JSONObject().put("action", action))
    fun request(routes: RemoteRoutes, method: String, path: String, body: JSONObject): FleetHttpServer.Response {
      val bytes = body.toString().toByteArray(Charsets.UTF_8)
      return routes.handle(FleetHttpServer.Request(method, path, "",
          mapOf("authorization" to "Bearer synthetic-pinned-navigation-token"),
          ByteArrayInputStream(bytes), bytes.size))
    }
  }

  private fun FleetHttpServer.Response.json() = JSONObject(String(checkNotNull(jsonBytes), Charsets.UTF_8))
}
