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
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Real authenticated routes: storage failures must be useful responses without replacement. */
class RemotePresetRoutesTest {
  @Test fun unreadableReadReturnsUnavailableWithoutClaimingAnEmptyList() {
    val f = Fixture("[broken")
    val response = f.request("GET", "/remote/presets")
    assertEquals(503, response.status)
    assertEquals("saved_presets_unavailable", response.json().getString("error"))
    assertFalse(response.json().has("presets"))
    assertEquals("[broken", f.raw.get())
    verify(f.preferences, never()).edit()
  }

  @Test fun unreadableReplacementReturnsUnavailableWithoutWritingOrLeakingData() {
    val f = Fixture("PRIVATE-FIXTURE-SENTINEL")
    val response = f.request("POST", "/remote/presets", """{"presets":[]}""")
    assertEquals(503, response.status)
    assertEquals("saved_presets_unavailable", response.json().getString("error"))
    assertFalse(String(response.jsonBytes!!).contains("PRIVATE-FIXTURE-SENTINEL"))
    assertEquals("PRIVATE-FIXTURE-SENTINEL", f.raw.get())
    verify(f.preferences, never()).edit()
  }

  @Test fun unreadableRunReturnsUnavailableBeforeExecution() {
    val f = Fixture("{}")
    val response = f.request("POST", "/remote/preset", """{"id":"public-preset"}""")
    assertEquals(503, response.status)
    assertEquals("saved_presets_unavailable", response.json().getString("error"))
    assertFalse(response.json().has("running"))
    assertEquals("{}", f.raw.get())
    verify(f.preferences, never()).edit()
  }

  @Test fun missingAndEmptyStoresRemainEditableAndReturnSavedReadback() {
    for (initial in listOf(null, "[]")) {
      val f = Fixture(initial)
      assertEquals(0, f.request("GET", "/remote/presets").json().getJSONArray("presets").length())
      val response = f.request("POST", "/remote/presets", """{"presets":[{"id":"public-preset","name":"Public preset","steps":[],"legacy":true}]}""")
      assertEquals(200, response.status)
      assertTrue(response.json().getJSONArray("presets").getJSONObject(0).getBoolean("legacy"))
      assertEquals("public-preset", JSONObject("{\"presets\":" + f.raw.get() + "}").getJSONArray("presets").getJSONObject(0).getString("id"))
    }
  }

  @Test fun invalidMethodsRequestsAndMissingAuthStillUseTheirExistingErrors() {
    val f = Fixture("[broken")
    assertEquals(401, f.request("GET", "/remote/presets", authorized = false).status)
    assertEquals(405, f.request("PUT", "/remote/presets").status)
    assertEquals(400, f.request("POST", "/remote/presets", "{}").status)
    assertEquals(400, f.request("POST", "/remote/preset", "{}").status)
    verify(f.preferences, never()).edit()
  }

  private class Fixture(initial: String?) {
    val raw = AtomicReference(initial)
    val context = mock(Context::class.java)
    val preferences = mock(SharedPreferences::class.java)
    val editor = mock(SharedPreferences.Editor::class.java)
    private val pairing = mock(SharedPreferences::class.java)
    private val routes = RemoteRoutes(context)
    init {
      `when`(context.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(pairing)
      `when`(pairing.getString("sessions", null)).thenReturn("public-preset-session")
      `when`(context.getSharedPreferences("remote_presets", Context.MODE_PRIVATE)).thenReturn(preferences)
      `when`(preferences.contains("json")).thenAnswer { raw.get() != null }
      `when`(preferences.getString(eq("json"), nullable(String::class.java))).thenAnswer { raw.get() ?: it.getArgument<String?>(1) }
      `when`(preferences.all).thenAnswer { raw.get()?.let { mapOf("json" to it) } ?: emptyMap<String, Any>() }
      `when`(preferences.edit()).thenReturn(editor)
      `when`(editor.putString(eq("json"), anyString())).thenAnswer { raw.set(it.getArgument(1)); editor }
    }
    fun request(method: String, path: String, body: String = "", authorized: Boolean = true): FleetHttpServer.Response {
      val bytes = body.toByteArray()
      return routes.handle(FleetHttpServer.Request(method, path, "",
          if (authorized) mapOf("authorization" to "Bearer public-preset-session") else emptyMap(),
          ByteArrayInputStream(bytes), bytes.size))
    }
  }
  private fun FleetHttpServer.Response.json() = JSONObject(String(requireNotNull(jsonBytes), Charsets.UTF_8))
}
