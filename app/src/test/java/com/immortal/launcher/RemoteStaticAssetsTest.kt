/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class RemoteStaticAssetsTest {
  private fun request(path: String, method: String = "GET") = FleetHttpServer.Request(
      method, path, "", emptyMap(), ByteArrayInputStream(byteArrayOf()), 0)

  @Test fun publicAssetsUseExactPathsAndMimeTypesWithoutPairing() {
    val context = mock(Context::class.java)
    val assets = mock(AssetManager::class.java)
    `when`(context.assets).thenReturn(assets)
    for ((file, type) in listOf("control-center.css" to "text/css", "control-center.js" to "text/javascript", "core.mjs" to "text/javascript")) {
      `when`(assets.open("remote/$file")).thenReturn(ByteArrayInputStream("test-asset".toByteArray()))
      val response = RemoteRoutes(context).handle(request("/remote/assets/$file"))
      assertEquals(200, response.status)
      assertEquals("$type; charset=utf-8", response.contentType)
      assertEquals("nosniff", response.extraHeaders["X-Content-Type-Options"])
      val output = ByteArrayOutputStream()
      response.streamWriter!!.invoke(output)
      assertEquals("test-asset", output.toString("UTF-8"))
    }
    verify(context, never()).getSharedPreferences(anyString(), anyInt())
  }

  @Test fun providerCachesAssetsAcrossRequestsAndRejectsOtherMethods() {
    val context = mock(Context::class.java)
    val assets = mock(AssetManager::class.java)
    `when`(context.assets).thenReturn(assets)
    `when`(assets.open("remote/core.mjs")).thenReturn(ByteArrayInputStream("cached".toByteArray()))
    val routes = RemoteRoutes(context)
    repeat(3) {
      val response = routes.handle(request("/remote/assets/core.mjs"))
      val bytes = ByteArrayOutputStream()
      response.streamWriter!!.invoke(bytes)
      assertEquals("cached", bytes.toString("UTF-8"))
    }
    verify(assets, times(1)).open("remote/core.mjs")
    assertEquals(405, routes.handle(request("/remote/assets/core.mjs", "POST")).status)
  }

  @Test fun traversalAndUnlistedAssetsNeverReachAssetManager() {
    val context = mock(Context::class.java)
    val routes = RemoteRoutes(context)
    for (path in listOf("/remote/assets/../catalog.json", "/remote/assets/%2e%2e/catalog.json", "/remote/assets/secret.json", "/remote/assets/core.mjs/extra")) {
      assertNull(RemoteRoutes.staticAssetName(path))
      assertEquals(404, routes.handle(request(path)).status)
    }
    verifyNoInteractions(context)
  }

  @Test fun compatiblePageEntrypointLoadsOnlyBundledControlCenter() {
    assertTrue(RemoteHtml.PAGE.contains("/remote/assets/control-center.css"))
    assertTrue(RemoteHtml.PAGE.contains("/remote/assets/control-center.js"))
    assertTrue(RemoteHtml.PAGE.contains("viewport-fit=cover"))
    assertFalse(RemoteHtml.PAGE.contains("onclick="))
    assertFalse(RemoteHtml.PAGE.contains("function api("))
    assertFalse(RemoteHtml.PAGE.contains("cdn"))
  }
}
