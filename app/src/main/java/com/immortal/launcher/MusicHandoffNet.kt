/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import org.json.JSONObject

/**
 * HTTP half of "Move to…": the source's `/remote/musicpeer` route and the target's fetch. Not
 * session-authenticated (Portals hold no tokens for each other): every request carries a fresh
 * [HouseholdKey]-signed header pinning its body, an offer is served once and only to the room it
 * names, and the reply is signed against the request nonce ([MusicHandoffAuth]).
 */
internal object MusicHandoffNet {
  const val PATH = "/remote/musicpeer"
  private const val MAX_REQUEST = 1024
  private const val CONNECT_MS = 3_000
  private const val READ_MS = 5_000
  private val replay = HouseholdMessage.ReplayGuard()
  private val random = SecureRandom()
  private const val TAG = "HearthpaneMusicMove"

  /** Source side. Builds the payload from the live engine at fetch time, so the position is current. */
  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    if (req.contentLength !in 1..MAX_REQUEST) return RemoteFeatureRoutes.json(413, RemoteFeatureRoutes.error("body_too_large"))
    val key = HouseholdKey.get(context) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    val text = try { req.bodyText(MAX_REQUEST) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_body"))
    }
    val caller = MusicHandoffAuth.verifyRequest(req.header(MusicHandoffAuth.HEADER), text, key,
        System.currentTimeMillis(), replay, DinnerCall.selfId(context))
        ?: return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
            .also { Log.i(TAG, "serve: request signature rejected") }
    if (!MusicHandoff.claim(caller.offerId, caller.from)) {
      Log.i(TAG, "serve: no matching offer to claim")
      return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("no_offer"))
    }
    val engine = YouTubeMusicEngine.state.value
    val position = MusicV3NowPlaying.livePositionMs(engine, SystemClock.elapsedRealtime())
    val queue = MusicHandoffCodec.fromQueue(engine.queue, position)
        ?: return RemoteFeatureRoutes.json(409, RemoteFeatureRoutes.error("nothing_playing"))
            .also { Log.i(TAG, "serve: queue not encodable (entries=${engine.queue.entries.size})") }
    val payload = MusicHandoffCodec.encode(queue)
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload)
        .put("sig", MusicHandoffAuth.signResponse(key, caller.nonce, payload)))
  }

  /** Target side (blocking; call off the main thread). The verified queue, or null. */
  fun fetch(context: Context, offer: MusicHandoffWire.Offer): MusicHandoffQueue? {
    val key = HouseholdKey.get(context) ?: return null
    val self = DinnerCall.selfId(context) ?: return null
    val body = MusicHandoffAuth.fetchBody(offer.id)
    val nonce = ByteArray(12).also(random::nextBytes).let(HouseholdKey::toHex)
    val header = MusicHandoffAuth.requestHeader(key, self, nonce, System.currentTimeMillis(), body) ?: return null
    val conn = URL("http", offer.host, offer.port, PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(MusicHandoffAuth.HEADER, header)
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      if (conn.responseCode != 200) return null.also { Log.i(TAG, "fetch: HTTP ${conn.responseCode}") }
      val text = conn.inputStream.use { readCapped(it, MAX_RESPONSE) } ?: return null
      val envelope = JSONObject(text)
      val payload = envelope.optString("payload")
      if (!MusicHandoffAuth.verifyResponse(key, nonce, payload, envelope.optString("sig"))) {
        return null.also { Log.i(TAG, "fetch: response signature rejected") }
      }
      return MusicHandoffCodec.decode(payload).also { if (it == null) Log.i(TAG, "fetch: payload not decodable") }
    } finally {
      conn.disconnect()
    }
  }

  /** The envelope wraps the payload as a JSON string, so allow escaping headroom over the payload cap. */
  private const val MAX_RESPONSE = MusicHandoffCodec.MAX_BODY_BYTES * 2

  /** Reads at most [max] bytes; null when the body is longer. */
  private fun readCapped(input: InputStream, max: Int): String? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      if (out.size() + n > max) return null
      out.write(buf, 0, n)
    }
    return out.toString(Charsets.UTF_8.name())
  }
}
