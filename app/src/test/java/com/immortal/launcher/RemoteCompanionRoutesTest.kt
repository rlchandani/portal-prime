/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import android.content.SharedPreferences

class RemoteCompanionRoutesTest {
  @Test fun managementRoutesRequirePairingBeforeDispatch() {
    val routes = RemoteRoutes(mock(Context::class.java))
    for (path in listOf("companion", "layout", "snapshot", "catalog", "operation", "operations", "jarvis", "scenes", "scene", "photos", "photo", "photo-upload")) {
      val request = FleetHttpServer.Request("GET", "/remote/$path", "", emptyMap(), ByteArrayInputStream(byteArrayOf()), 0)
      assertEquals(path, 401, routes.handle(request).status)
    }
  }
  @Test fun fleetTokenAloneCannotAuthorizeManagementOrPhotos() {
    val c = mock(Context::class.java)
    val preferences = mock(SharedPreferences::class.java)
    `when`(c.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(preferences)
    `when`(preferences.getString("sessions", null)).thenReturn("paired-test-token")
    `when`(preferences.getBoolean("enabled", false)).thenReturn(true)
    val routes = RemoteRoutes(c)
    for (path in listOf("companion", "layout", "snapshot", "catalog", "operation", "operations", "jarvis", "scenes", "scene", "photos", "photo", "photo-upload")) {
      val request = FleetHttpServer.Request("POST", "/remote/$path", "", mapOf("authorization" to "Bearer fleet-test-token"), ByteArrayInputStream(byteArrayOf()), 0)
      assertEquals(path, 401, routes.handle(request).status)
    }
    val paired = FleetHttpServer.Request("PUT", "/remote/layout", "", mapOf("authorization" to "Bearer paired-test-token"), ByteArrayInputStream(byteArrayOf()), 0)
    assertEquals(405, routes.handle(paired).status)
  }
  @Test fun truncatedJsonIsRejectedBeforeAnyBackendAccess() {
    val c = mock(Context::class.java)
    val preferences = mock(SharedPreferences::class.java)
    `when`(c.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(preferences)
    `when`(preferences.getString("sessions", null)).thenReturn("paired-test-token")
    `when`(preferences.getBoolean("enabled", false)).thenReturn(true)
    val routes = RemoteRoutes(c)
    for (path in listOf("snapshot", "layout", "operation", "scenes", "scene", "jarvis")) {
      val request = FleetHttpServer.Request("POST", "/remote/$path", "", mapOf("authorization" to "Bearer paired-test-token"), ByteArrayInputStream("{}".toByteArray()), 8)
      assertEquals(path, 400, routes.handle(request).status)
    }
  }

  @Test fun disablingRemoteBlocksSavedSessionsOnEveryNewRoute() {
    val c = mock(Context::class.java)
    val preferences = mock(SharedPreferences::class.java)
    `when`(c.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(preferences)
    `when`(preferences.getString("sessions", null)).thenReturn("paired-test-token")
    `when`(preferences.getBoolean("enabled", false)).thenReturn(true)
    `when`(preferences.getBoolean("enabled", false)).thenReturn(false)
    val routes = RemoteRoutes(c)
    for (path in listOf("companion", "layout", "snapshot", "catalog", "operation", "operations", "jarvis", "scenes", "scene", "photos", "photo", "photo-upload")) {
      val request = FleetHttpServer.Request("PUT", "/remote/$path", "", mapOf("authorization" to "Bearer paired-test-token"), ByteArrayInputStream(byteArrayOf()), 0)
      assertEquals(path, 401, routes.handle(request).status)
    }
  }

}
