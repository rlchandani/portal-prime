/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The vault route hands out the household identity's private key, and a phone holding the
 * household key could pose as a Portal to get it. The vault now moves only to pinned Portal device
 * keys ([HomeLinkGate.syncs]); until that is reviewed the route and sync also stay measurement-only:
 * ordinary builds (unit tests use one) don't register `/remote/homelinkpeer` at all.
 */
class HomeLinkGateTest {
  private fun post(path: String) = FleetHttpServer.Request("POST", path, "",
      mapOf(HomeLinkSyncWire.HEADER.lowercase() to "v1.x.y"), ByteArrayInputStream(ByteArray(16)), 16, "192.168.1.20")

  private fun text(r: FleetHttpServer.Response) = String(r.jsonBytes ?: ByteArray(0), Charsets.UTF_8)

  @Test fun theFlagIsOffInOrdinaryBuilds() {
    assertFalse(BuildConfig.PORTAL_CALL_LOOPBACK)
    assertFalse(HomeLinkGate.VAULT_SYNC)
  }

  /** The security condition: nothing syncs until a Portal's device key is pinned, whatever the build. */
  @Test fun syncNeedsAPinnedPortalEvenWithTheBuildSwitchOn() {
    val random = java.security.SecureRandom()
    val me = HomeLinkIdentity.generate(random).publicKey
    val pinned = PortalRoster().pin(HomeLinkPeer("Kitchen", HomeLinkIdentity.generate(random).publicKey, "kitchen-1"), me, 1L)!!
    assertFalse(HomeLinkGate.syncs(PortalRoster(), buildSwitch = true))
    assertTrue(HomeLinkGate.syncs(pinned, buildSwitch = true))
    assertFalse(HomeLinkGate.syncs(pinned, buildSwitch = false))
    assertFalse(HomeLinkGate.syncs(pinned))
  }

  @Test fun theVaultRouteIsNotRegisteredWhenTheFlagIsOff() {
    val context = mock(Context::class.java)
    val routes = RemoteRoutes(context)
    val vault = routes.handle(post(HomeLinkSyncWire.PATH))
    val unknown = routes.handle(post("/remote/nosuchroute"))
    assertEquals(404, vault.status)
    assertEquals(text(unknown), text(vault))
    assertTrue(text(vault).contains("not_found"))
    verifyNoInteractions(context)
  }

  @Test fun thePinningMailboxIsNotRegisteredWhenTheFlagIsOff() {
    val context = mock(Context::class.java)
    val routes = RemoteRoutes(context)
    val pin = routes.handle(post(PortalPinWire.PATH))
    assertEquals(404, pin.status)
    assertEquals(text(routes.handle(post("/remote/nosuchroute"))), text(pin))
    assertEquals(404, PortalPinNet.handle(context, post(PortalPinWire.PATH)).status)
    verifyNoInteractions(context)
  }

  @Test fun syncNeitherAnswersNorStartsWhenTheFlagIsOff() {
    val context = mock(Context::class.java)
    assertEquals(404, HomeLinkSync.handle(context, post(HomeLinkSyncWire.PATH)).status)
    HomeLinkSync.install(context)
    HomeLinkSync.signal(context)
    verifyNoInteractions(context)
  }

  /** Release and performance builds must not turn it on: only the measurement-build property does. */
  @Test fun noBuildTypeTurnsTheFlagOn() {
    val script = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile }.readText()
    val uses = Regex("""buildConfigField\("boolean", "PORTAL_CALL_LOOPBACK",\s*([^\n]*)""").findAll(script).toList()
    assertEquals(1, uses.size)
    assertTrue(uses.single().groupValues[1].contains("gradleProperty(\"portalCallLoopback\")"))
  }
}
