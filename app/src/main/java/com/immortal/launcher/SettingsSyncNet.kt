/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** Both ends of the signed `/remote/settingspeer` exchange (see [SettingsSyncWire]). */
internal object SettingsSyncNet {
  const val PATH = "/remote/settingspeer"
  private const val MAX_BODY = 256 * 1024
  private const val CONNECT_MS = 3_000
  private const val READ_MS = 5_000
  private val random = SecureRandom()
  private val replay = HouseholdMessage.ReplayGuard()

  /** POSTs our payload to a peer; returns its verified payload text, or null. Blocking. */
  fun post(context: Context, host: String, port: Int, body: String): String? {
    if (!HouseholdListPeerAuth.isPrivateLan(host)) return null
    val key = HouseholdKey.get(context) ?: return null
    val self = RemoteCompanionStore.id(context)
    val nonce = ByteArray(12).also(random::nextBytes).let(HouseholdKey::toHex)
    val header = SettingsSyncWire.requestHeader(key, self, nonce, System.currentTimeMillis(), body) ?: return null
    val conn = URL("http", host, port, PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(SettingsSyncWire.HEADER, header)
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      if (conn.responseCode != 200) return null
      val text = BoundedRead.reply(conn, MAX_BODY) ?: return null
      val envelope = JSONObject(text)
      val payload = envelope.optString("payload")
      return payload.takeIf { SettingsSyncWire.verifyResponse(key, nonce, it, envelope.optString("sig")) }
    } finally {
      conn.disconnect()
    }
  }

  /** The route: verify the household signature, merge, reply signed against the caller's nonce. */
  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    if (req.contentLength !in 1..MAX_BODY) return RemoteFeatureRoutes.json(413, RemoteFeatureRoutes.error("body_too_large"))
    val key = HouseholdKey.get(context) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    // Header first: an unsigned or stale request never gets its body read.
    if (!PeerRequestGate.admits(req.header(SettingsSyncWire.HEADER), SettingsSyncWire.REQUEST_TYPE, key, System.currentTimeMillis()))
      return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    val text = try { req.bodyText(MAX_BODY) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_body"))
    }
    val self = runCatching { RemoteCompanionStore.id(context) }.getOrNull()
    val caller = SettingsSyncWire.verifyRequest(req.header(SettingsSyncWire.HEADER), text, key, System.currentTimeMillis(), replay, self)
        ?: return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    val payload = SettingsSync.handlePeer(context, caller.from, text)
        ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("unavailable"))
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload)
        .put("sig", SettingsSyncWire.signResponse(key, caller.nonce, payload)))
  }
}
