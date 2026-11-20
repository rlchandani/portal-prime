/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Both ends of the signed `/remote/settingspeer` exchange (see [SettingsSyncWire]), wired to this
 * Portal: [SyncPeerClient] and [SyncPeerRoute] decide; this supplies the key, the device id, the
 * clock and the HTTP connection.
 */
internal object SettingsSyncNet {
  const val PATH = "/remote/settingspeer"
  const val MAX_BODY = 256 * 1024
  private const val NONCE_BYTES = 12
  private val random = SecureRandom()
  private val replay = HouseholdMessage.ReplayGuard()

  /** POSTs our payload to a peer; returns its verified payload text, or null. Blocking. */
  fun post(context: Context, host: String, port: Int, body: String): String? =
      SyncPeerClient({ HouseholdKey.get(context) }, { RemoteCompanionStore.id(context) }, HttpSyncTransport, System::currentTimeMillis,
          { ByteArray(NONCE_BYTES).also(random::nextBytes).let(HouseholdKey::toHex) }).post(host, port, body)

  /** The route: verify the household signature, merge, reply signed against the caller's nonce. */
  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response =
      SyncPeerRoute({ HouseholdKey.get(context) }, { runCatching { RemoteCompanionStore.id(context) }.getOrNull() }, System::currentTimeMillis,
          replay) { from, text -> SettingsSync.handlePeer(context, from, text) }.handle(req)
}

/** One bounded POST over HttpURLConnection. */
internal object HttpSyncTransport : SyncTransport {
  private const val CONNECT_MS = 3_000
  private const val READ_MS = 5_000

  override fun post(host: String, port: Int, path: String, authHeader: String, body: String): String? {
    val conn = URL("http", host, port, path).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(SettingsSyncWire.HEADER, authHeader)
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      if (conn.responseCode != 200) return null
      return BoundedRead.reply(conn, SettingsSyncNet.MAX_BODY)
    } finally {
      conn.disconnect()
    }
  }
}
