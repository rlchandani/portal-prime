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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/**
 * Regression: feature routes registered with [RemoteFeatureRoutes] must reach the companion
 * layer through the outer [RemoteRoutes] switch. They used to fall through to 404 there, so the
 * phone could never provision the household key on a real Portal.
 */
class RemoteFeatureDispatchTest {
  private val path = "/remote/dispatchprobe"

  init { RemoteFeatureRoutes.register(path, probe) }

  private fun routes(): RemoteRoutes {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    `when`(context.getSharedPreferences(Mockito.anyString(), Mockito.anyInt())).thenReturn(prefs)
    return RemoteRoutes(context)
  }

  private fun get(routes: RemoteRoutes, p: String) =
      routes.handle(FleetHttpServer.Request("GET", p, "", emptyMap(), ByteArrayInputStream(ByteArray(0)), 0))

  @Test fun registeredFeatureRouteReachesCompanionAuth() {
    // Unpaired caller: the companion layer answers 401 before any feature handler runs.
    assertEquals(401, get(routes(), path).status)
  }

  @Test fun unknownRemotePathIsStillNotFound() {
    assertEquals(404, get(routes(), "/remote/nosuchfeature").status)
  }

  private companion object {
    val probe = RemoteFeatureRoutes.Route { _, _, _ -> RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok()) }
  }
}
