/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class RemotePresetTrackedRunTest {
  @Test fun failedLaunchStopsBeforeTheNextAction() {
    val context = mock(Context::class.java)
    val pm = mock(PackageManager::class.java)
    `when`(context.packageManager).thenReturn(pm)
    var backCalls = 0
    val routes = RemoteRoutes(context, PinnedNavigationDispatch(back = {
      backCalls++; JSONObject().put("ok", true)
    }))
    val steps = JSONArray().put(JSONObject().put("type", "launch").put("packageName", "public.missing"))
      .put(JSONObject().put("type", "key").put("action", "back"))
    val method = RemoteRoutes::class.java.getDeclaredMethod("runSteps", JSONArray::class.java).apply { isAccessible = true }
    val results = method.invoke(routes, steps)
    assertEquals(0, backCalls)
    assertTrue(results is JSONArray)
    assertEquals(false, (results as JSONArray).getJSONObject(0).getBoolean("ok"))
    assertEquals(0, results.getJSONObject(0).getInt("step"))
  }

  @Test fun failedBackAdmissionDoesNotRunLaterSteps() {
    var calls = 0
    val routes = RemoteRoutes(mock(Context::class.java), PinnedNavigationDispatch(back = {
      calls++; JSONObject().put("ok", false)
    }))
    val steps = JSONArray("[{type:'key',action:'back'},{type:'key',action:'back'}]")
    val method = RemoteRoutes::class.java.getDeclaredMethod("runSteps", JSONArray::class.java).apply { isAccessible = true }
    method.invoke(routes, steps)
    assertEquals(1, calls)
  }

  @Test fun nonPostPresetRequestsCannotBeginAnExecution() {
    val context = mock(Context::class.java)
    val pairing = mock(SharedPreferences::class.java)
    val saved = mock(SharedPreferences::class.java)
    `when`(context.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(pairing)
    `when`(pairing.getString("sessions", null)).thenReturn("public-session")
    `when`(context.getSharedPreferences("remote_presets", Context.MODE_PRIVATE)).thenReturn(saved)
    val routes = RemoteRoutes(context)
    val bytes = "{id:'missing'}".toByteArray()
    for (method in listOf("GET", "PUT", "DELETE")) {
      val response = routes.handle(FleetHttpServer.Request(method, "/remote/preset", "",
        mapOf("authorization" to "Bearer public-session"), ByteArrayInputStream(bytes), bytes.size))
      assertEquals(405, response.status)
    }
    verify(context, never()).getSharedPreferences("remote_presets", Context.MODE_PRIVATE)
  }

  @Test fun runningPresetOffersCooperativeCancellation() {
    val history = RemoteCompanionHistory()
    val op = history.start("preset", JSONObject().put("id", "public-preset"))!!
    assertTrue(op.getBoolean("cancelSupported"))
    assertFalse(op.getBoolean("retrySupported"))
  }

  @Test fun failedPresetCannotAutomaticallyRepeatEarlierActions() {
    val history = RemoteCompanionHistory()
    val op = history.start("preset", JSONObject())!!
    history.finish(op.getString("id"), JSONArray().put(JSONObject().put("ok", false).put("result", "not_dispatched")))
    assertFalse(history.find(op.getString("id"))!!.getBoolean("retrySupported"))
  }

  @Test fun cancelledPresetIsStoppedRatherThanReportedAsFailed() {
    val history = RemoteCompanionHistory()
    val op = history.start("preset", JSONObject())!!
    history.finish(op.getString("id"), JSONArray().put(JSONObject().put("ok", false).put("result", "cancelled")))
    val done = history.find(op.getString("id"))!!
    assertEquals("cancelled", done.getString("state"))
    assertFalse(done.getBoolean("cancelSupported"))
    assertFalse(done.getBoolean("retrySupported"))
  }
  @Test fun admittedHomeAppsAndRecentsPreservePendingNavigation() {
    for (action in listOf("home","apps","recents")) {
      var admissions = 0; var backCalls = 0
      val routes = RemoteRoutes(mock(Context::class.java), PinnedNavigationDispatch(
        navigation = { admissions++; throw PinnedCommandPending() },
        back = { backCalls++; JSONObject().put("ok",true) }))
      val method = RemoteRoutes::class.java.getDeclaredMethod("runSteps",JSONArray::class.java).apply { isAccessible=true }
      val result = method.invoke(routes,JSONArray("[{type:'key',action:'$action'},{type:'key',action:'back'}]")) as JSONArray
      assertEquals(action,1,admissions); assertEquals(0,backCalls)
      assertEquals("completion_unconfirmed",result.getJSONObject(0).getString("result"))
      assertTrue(result.getJSONObject(0).getBoolean("backendMayContinue"))
    }
  }

}
