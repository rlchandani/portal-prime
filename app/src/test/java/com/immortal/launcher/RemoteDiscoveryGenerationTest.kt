/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.MockedConstruction
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Real discovery callbacks and peer JSON, with Android NSD calls ending at inert mocks. */
class RemoteDiscoveryGenerationTest {
  private lateinit var serviceInfos: MockedConstruction<NsdServiceInfo>
  private lateinit var logs: MockedStatic<Log>

  @Before
  fun isolateAndroidBoundaries() {
    logs = Mockito.mockStatic(Log::class.java)
    serviceInfos = Mockito.mockConstruction(NsdServiceInfo::class.java)
    RemoteDiscovery.stop()
  }

  @After
  fun retireDiscovery() {
    try {
      RemoteDiscovery.stop()
    } finally {
      serviceInfos.close()
      logs.close()
    }
  }

  private class Session {
    val context: Context = Mockito.mock(Context::class.java)
    val manager: NsdManager = Mockito.mock(NsdManager::class.java)
    val registrations = mutableListOf<NsdManager.RegistrationListener>()
    val discoveries = mutableListOf<NsdManager.DiscoveryListener>()
    val resolutions = mutableListOf<NsdManager.ResolveListener>()
    var rejectResolve = false

    init {
      `when`(context.applicationContext).thenReturn(context)
      `when`(context.getSystemService(Context.NSD_SERVICE)).thenReturn(manager)
      Mockito.doAnswer { call ->
            registrations += call.getArgument<NsdManager.RegistrationListener>(2)
            null
          }
          .`when`(manager)
          .registerService(
              Mockito.any(NsdServiceInfo::class.java),
              Mockito.eq(NsdManager.PROTOCOL_DNS_SD),
              Mockito.any(NsdManager.RegistrationListener::class.java))
      Mockito.doAnswer { call ->
            discoveries += call.getArgument<NsdManager.DiscoveryListener>(2)
            null
          }
          .`when`(manager)
          .discoverServices(
              Mockito.eq("_immortal-remote._tcp."),
              Mockito.eq(NsdManager.PROTOCOL_DNS_SD),
              Mockito.any(NsdManager.DiscoveryListener::class.java))
      Mockito.doAnswer { call ->
            resolutions += call.getArgument<NsdManager.ResolveListener>(1)
            if (rejectResolve) throw IllegalStateException("generated resolve rejection")
            null
          }
          .`when`(manager)
          .resolveService(
              Mockito.any(NsdServiceInfo::class.java),
              Mockito.any(NsdManager.ResolveListener::class.java))
    }

    fun found(info: NsdServiceInfo): NsdManager.ResolveListener {
      val before = resolutions.size
      discoveries.single().onServiceFound(info)
      assertEquals("current peer must request resolution", before + 1, resolutions.size)
      return resolutions.last()
    }
  }

  private fun start(name: String = "Fixture Self"): Session =
      Session().also { RemoteDiscovery.start(it.context, name, 8723) }

  private fun info(
      name: String = "Fixture Peer",
      addressLastByte: Int? = 21,
      port: Int = 8723
  ): NsdServiceInfo =
      Mockito.mock(NsdServiceInfo::class.java).also {
        `when`(it.serviceName).thenReturn(name)
        `when`(it.serviceType).thenReturn("_immortal-remote._tcp.")
        `when`(it.port).thenReturn(port)
        val address =
            addressLastByte?.let { last ->
              InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, last.toByte()))
            }
        `when`(it.host).thenReturn(address)
      }

  private fun assertEmpty() {
    assertEquals("retired or unresolved peer must stay absent", 0, RemoteDiscovery.peersJson().length())
  }

  private fun assertPeer(name: String, host: String, port: Int) {
    val peers = RemoteDiscovery.peersJson()
    assertEquals(1, peers.length())
    val peer = peers.getJSONObject(0)
    assertEquals(name, peer.getString("name"))
    assertEquals(host, peer.getString("host"))
    assertEquals(port, peer.getInt("port"))
  }

  @Test
  fun lostPeerCannotReturnThroughDelayedResolution() {
    val session = start()
    val peer = info()
    val held = session.found(peer)

    session.discoveries.single().onServiceLost(peer)
    held.onServiceResolved(peer)

    assertEmpty()
  }

  @Test
  fun stoppedDiscoveryCannotPublishDelayedResolution() {
    val session = start()
    val peer = info()
    val held = session.found(peer)

    RemoteDiscovery.stop()
    held.onServiceResolved(peer)

    assertEmpty()
  }

  @Test
  fun restartedDiscoveryRejectsPreviousSessionResolution() {
    val old = start()
    val peer = info()
    val held = old.found(peer)
    RemoteDiscovery.stop()
    val current = start("Current Self")

    held.onServiceResolved(peer)
    assertEmpty()
    current.found(info("Current Peer", 22)).onServiceResolved(info("Current Peer", 22))

    assertPeer("Current Peer", "192.0.2.22", 8723)
  }

  @Test
  fun previousSessionLossCannotEraseCurrentPeerWithSameName() {
    val old = start()
    val peer = info()
    old.found(peer)
    RemoteDiscovery.stop()
    val current = start("Current Self")
    val replacement = info(addressLastByte = 22)
    current.found(replacement).onServiceResolved(replacement)

    old.discoveries.single().onServiceLost(peer)

    assertPeer("Fixture Peer", "192.0.2.22", 8723)
  }

  @Test
  fun previousRegistrationCannotChangeCurrentSelfExclusion() {
    val old = start("Old Self")
    RemoteDiscovery.stop()
    val current = start("Current Self")
    current.registrations.single().onServiceRegistered(info("Current Self (2)"))

    old.registrations.single().onServiceRegistered(info("Old Self (2)"))
    current.discoveries.single().onServiceFound(info("Current Self (2)"))

    assertEquals("our current registered name must not be resolved", 0, current.resolutions.size)
    assertEmpty()
  }

  @Test
  fun currentResolvedPeerKeepsLiteralAddressAndPort() {
    val session = start()
    val peer = info("Kitchen Fixture", 31, 8899)

    session.found(peer).onServiceResolved(peer)

    assertPeer("Kitchen Fixture", "192.0.2.31", 8899)
  }

  @Test
  fun currentRegisteredSelfIsExcluded() {
    val session = start()
    val self = info("Fixture Self (2)")
    session.registrations.single().onServiceRegistered(self)

    session.discoveries.single().onServiceFound(self)

    assertEquals("self discovery must not issue a resolve", 0, session.resolutions.size)
    assertEmpty()
  }

  @Test
  fun duplicateFoundKeepsFirstResolutionViableUntilLoss() {
    val session = start()
    val peer = info()
    val first = session.found(peer)
    session.found(peer)

    first.onServiceResolved(peer)

    assertPeer("Fixture Peer", "192.0.2.21", 8723)
  }

  @Test
  fun refoundPeerRejectsOldResolutionAndAcceptsNewAddress() {
    val session = start()
    val oldPeer = info()
    val oldResolve = session.found(oldPeer)
    session.discoveries.single().onServiceLost(oldPeer)
    val replacement = info(addressLastByte = 22)
    val currentResolve = session.found(replacement)

    oldResolve.onServiceResolved(oldPeer)
    assertEmpty()
    currentResolve.onServiceResolved(replacement)

    assertPeer("Fixture Peer", "192.0.2.22", 8723)
  }

  @Test
  fun missingHostAndResolveRejectionStayBestEffort() {
    val session = start()
    val unresolved = info("Missing Host", addressLastByte = null)
    session.found(unresolved).onServiceResolved(unresolved)
    assertEmpty()

    session.rejectResolve = true
    session.discoveries.single().onServiceFound(info("Rejected Peer"))
    assertEmpty()

    session.rejectResolve = false
    val current = info("Current Peer", 24)
    session.found(current).onServiceResolved(current)

    assertPeer("Current Peer", "192.0.2.24", 8723)
  }
}
