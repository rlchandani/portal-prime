/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** `/remote/controlcenter*` is reachable only with a paired phone session; the fleet token and strangers get 401. */
class CcRemoteRouteAuthTest {
  init { CcRemoteRoute.install() }

  private fun routes(sessions: String? = null, enabled: Boolean = true): RemoteRoutes {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    `when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
    `when`(prefs.getBoolean(Mockito.eq("enabled"), Mockito.anyBoolean())).thenReturn(enabled)
    `when`(prefs.getString(Mockito.eq("sessions"), Mockito.any())).thenReturn(sessions)
    return RemoteRoutes(context)
  }

  private fun request(routes: RemoteRoutes, method: String, path: String, token: String? = null) =
      routes.handle(FleetHttpServer.Request(method, path, "", token?.let { mapOf("authorization" to "Bearer $it") } ?: emptyMap(),
          ByteArrayInputStream(ByteArray(0)), 0))

  @Test fun everyPathNeedsASession() {
    for (p in listOf(CcRemoteRoute.STATE, CcRemoteRoute.PLAN, CcRemoteRoute.ACT, CcRemoteRoute.CAMERA)) {
      assertEquals(p, 401, request(routes(), "GET", p).status)
      assertEquals(p, 401, request(routes(), "POST", p, "not-a-session").status)
    }
  }

  @Test fun disabledRemoteRefusesEvenAValidSession() {
    assertEquals(401, request(routes("tok123", enabled = false), "GET", CcRemoteRoute.STATE, "tok123").status)
  }

  @Test fun validSessionPassesAuth() {
    // Past auth the route answers on its own terms (HA isn't configured on this mock), never 401.
    val status = request(routes("tok123"), "GET", CcRemoteRoute.PLAN, "tok123").status
    assertNotEquals(401, status)
    assertEquals(405, request(routes("tok123"), "GET", CcRemoteRoute.ACT, "tok123").status)
  }

  @Test fun callerKeyIsStableAndNotTheToken() {
    val k = RemoteFeatureRoutes.callerKey("tok123")
    assertEquals(k, RemoteFeatureRoutes.callerKey("tok123"))
    assertNotEquals(k, RemoteFeatureRoutes.callerKey("tok124"))
    assertEquals(16, k.length)
  }

  @Test fun withoutCallerTheRouteRefuses() {
    val ctx = Mockito.mock(Context::class.java)
    assertEquals(401, CcRemoteRoute.handle(ctx, "GET", JSONObject()).status)
    assertEquals(401, CcRemoteRoute.route(ctx, CcRemoteRoute.STATE, "GET", JSONObject(), "").status)
  }
}
