/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Exercises route dispatch; only the Android Intent/Context boundary needs JVM doubles. */
class RemoteRoutesNavigationTest {
  @Test fun appsKeySelectsTheRetainedHomeAppsPage() = withNavigation { fixture, launches ->
    val response = fixture.key("apps")
    assertEquals(200, response.status)
    assertTrue(response.json().getBoolean("ok"))
    assertEquals("apps", response.json().getString("action"))
    assertEquals(1, launches.size)
    assertHomePage(launches.single(), "apps")
  }

  @Test fun homeKeyStillSelectsTheRetainedHomePage() = withNavigation { fixture, launches ->
    val response = fixture.key("home")
    assertEquals(200, response.status)
    assertTrue(response.json().getBoolean("dispatched"))
    assertHomePage(launches.single(), "home")
  }

  @Test fun appsLaunchFailureIsReported() = withNavigation { fixture, _ ->
    Mockito.doThrow(SecurityException("Synthetic navigation denial"))
        .`when`(fixture.context).startActivity(Mockito.any(Intent::class.java))
    val response = fixture.key("apps")
    assertEquals(500, response.status)
    assertFalse(response.json().getBoolean("ok"))
  }

  @Test fun unauthorizedAndUnknownKeysCannotOpenAnActivity() = withNavigation { fixture, launches ->
    assertEquals(401, fixture.key("apps", authorized = false).status)
    assertEquals(400, fixture.key("not-a-key").status)
    assertTrue(launches.isEmpty())
  }

  @Test fun appsAndLegacyRecentsPresetStepsSelectTheRetainedAppsPage() =
      withNavigation { fixture, launches ->
        // Run the real preset worker synchronously: Mockito's Android constructor doubles are
        // thread-local. Preset storage, auth and async completion are covered by the device probe.
        val worker = RemoteRoutes::class.java.getDeclaredMethod("runSteps", JSONArray::class.java)
            .apply { isAccessible = true }
        for (action in listOf("apps", "recents")) {
          launches.clear()
          worker.invoke(fixture.routes,
              JSONArray().put(JSONObject().put("type", "key").put("action", action)))
          assertEquals(action, 1, launches.size)
          assertHomePage(launches.single(), "apps")
        }
      }

  private fun assertHomePage(launch: Launch, page: String) {
    assertEquals("Remote navigation must keep Home's host", HomeActivity::class.java, launch.target)
    assertEquals(page, launch.extras["approved_home_tab"])
    assertEquals(false, launch.extras["approved_home_search"])
    assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP, launch.flags)
  }

  private data class Launch(val target: Any?, val extras: MutableMap<String, Any?> = linkedMapOf(),
      var flags: Int = 0)

  private fun withNavigation(block: (Fixture, MutableList<Launch>) -> Unit) {
    val intents = linkedMapOf<Intent, Launch>()
    val launches = mutableListOf<Launch>()
    Mockito.mockConstruction(Intent::class.java) { intent, construction ->
      val launch = Launch(construction.arguments()[1])
      intents[intent] = launch
      Mockito.doAnswer { call ->
        launch.extras[call.getArgument<String>(0)] = call.getArgument(1)
        intent
      }.`when`(intent).putExtra(Mockito.anyString(), Mockito.anyString())
      Mockito.doAnswer { call ->
        launch.extras[call.getArgument<String>(0)] = call.getArgument<Boolean>(1)
        intent
      }.`when`(intent).putExtra(Mockito.anyString(), Mockito.anyBoolean())
      Mockito.doAnswer { call -> launch.flags = launch.flags or call.getArgument<Int>(0); intent }
          .`when`(intent).addFlags(Mockito.anyInt())
    }.use {
      val fixture = Fixture()
      Mockito.doAnswer { call -> launches += checkNotNull(intents[call.getArgument<Intent>(0)]); null }
          .`when`(fixture.context).startActivity(Mockito.any(Intent::class.java))
      Mockito.mockStatic(Looper::class.java).use { loops ->
        val main = Mockito.mock(Looper::class.java)
        loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
        loops.`when`<Looper> { Looper.myLooper() }.thenReturn(main)
        block(fixture, launches)
      }
    }
  }

  private class Fixture {
    val context = Mockito.mock(Context::class.java)
    val routes = RemoteRoutes(context)
    init {
      val pairing = Mockito.mock(SharedPreferences::class.java)
      val fleet = Mockito.mock(SharedPreferences::class.java)
      `when`(context.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(pairing)
      `when`(context.getSharedPreferences("fleet_agent", Context.MODE_PRIVATE)).thenReturn(fleet)
      `when`(fleet.getString("token", null)).thenReturn("synthetic-navigation-token")
    }
    fun key(action: String, authorized: Boolean = true): FleetHttpServer.Response {
      val bytes = JSONObject().put("action", action).toString().toByteArray(Charsets.UTF_8)
      return routes.handle(FleetHttpServer.Request("POST", "/remote/key", "",
          if (authorized) mapOf("authorization" to "Bearer synthetic-navigation-token") else emptyMap(),
          ByteArrayInputStream(bytes), bytes.size))
    }
  }

  private fun FleetHttpServer.Response.json() = JSONObject(String(checkNotNull(jsonBytes), Charsets.UTF_8))
}
