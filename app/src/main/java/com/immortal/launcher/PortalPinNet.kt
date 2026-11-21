/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Android side of pinning Portals over the LAN ([PortalPinWire]): the one in-memory mailbox
 * this Portal hosts, the `/remote/portalpin` route over it, and a household-signed HTTP
 * transport for the typing side. Behind [HomeLinkGate.VAULT_SYNC] like everything that touches
 * the roster. Logs ids and statuses only.
 */
internal object PortalPinNet {
  private const val TAG = "HearthpanePortalPin"
  private const val NONCE_BYTES = 12
  private const val CONNECT_MS = 3_000
  private const val READ_MS = 5_000
  private val replay = HouseholdMessage.ReplayGuard()
  val mailbox = PortalPinMailbox(System::currentTimeMillis)

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (!HomeLinkGate.VAULT_SYNC) return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("not_found"))
    return PortalPinRoute(key = { HouseholdKey.get(context) }, selfId = { selfId(context) }, clock = System::currentTimeMillis,
        replay = replay, announcedHost = ::announcedHost, mailbox = mailbox, log = { Log.i(TAG, it) }).handle(req)
  }

  /** Household Portals we know an address for (never phones). */
  fun peers(): List<PortalPinPeer> = CallingStore.peers.value.mapNotNull { p ->
    val host = p.host ?: return@mapNotNull null
    if (!HomeLinkWire.validDevice(p.id)) null else PortalPinPeer(p.id, host, p.port)
  }

  fun selfId(context: Context): String? = runCatching { RemoteCompanionStore.id(context) }.getOrNull()

  /** Posts household-signed bodies to other Portals' `/remote/portalpin`. Blocking. */
  fun transport(context: Context): PortalPinTransport = PortalPinTransport { peer, body ->
    runCatching { post(context, peer, body) }.onFailure { Log.i(TAG, "pinning post failed: ${it.javaClass.simpleName}") }.getOrNull()
  }

  private fun post(context: Context, peer: PortalPinPeer, body: String): Pair<Int, String?>? {
    val key = HouseholdKey.get(context) ?: return null
    val self = selfId(context) ?: return null
    val nonce = ByteArray(NONCE_BYTES).also(HomeLinkFeature.sharedRandom::nextBytes).let(HouseholdKey::toHex)
    val header = PortalPinWire.requestHeader(key, self, nonce, System.currentTimeMillis(), body) ?: return null
    val port = peer.port.takeIf { it > 0 } ?: FleetConfig.port(context)
    val conn = URL("http", peer.host, port, PortalPinWire.PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(PortalPinWire.HEADER, header)
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      val status = conn.responseCode
      val text = if (status == 200) BoundedRead.reply(conn, PortalPinWire.MAX_BODY) else null
      return status to text
    } finally {
      conn.disconnect()
    }
  }

  private fun announcedHost(id: String): String? = CallingStore.peers.value.firstOrNull { it.id == id }?.host
}
