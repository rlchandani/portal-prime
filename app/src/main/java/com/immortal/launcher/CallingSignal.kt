/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Callee side of the signed SDP exchange: POST the offer, verify the signed answer. */
internal object CallSignalClient {
  private const val TAG = "HearthpaneCallSignal"
  private const val CONNECT_MS = 3_000
  /** The caller builds its answer (ICE gathering included) while we wait. */
  private const val READ_MS = 10_000
  private const val MAX_RESPONSE = 64 * 1024

  private val net = Executors.newSingleThreadExecutor { r -> Thread(r, "hearthpane-call-signal").apply { isDaemon = true } }
  private val main = Handler(Looper.getMainLooper())
  private val random = SecureRandom()

  sealed interface Result {
    data class Answer(val sdp: String) : Result
    /** 409: that member doesn't know about us yet (its `call_join` is still on the way). */
    data object NotYet : Result
    data object Failed : Result
  }

  /** [done] runs on the main thread with the verified answer, [Result.NotYet] or [Result.Failed]. */
  fun sendOffer(context: Context, callId: String, to: String, host: String?, port: Int, sdp: String, done: (Result) -> Unit) {
    if (host == null || port !in 1..65535) { main.post { done(Result.Failed) }; return }
    net.execute {
      val answer = runCatching { exchange(context, callId, to, host, port, sdp) }
          .onFailure { Log.i(TAG, "offer exchange failed: ${it.javaClass.simpleName}") }
          .getOrDefault(Result.Failed)
      main.post { done(answer) }
    }
  }

  private fun exchange(context: Context, callId: String, to: String, host: String, port: Int, sdp: String): Result {
    val key = HouseholdKey.get(context) ?: return Result.Failed
    val self = RemoteCompanionStore.id(context)
    val fp = CallSignalAuth.fingerprintOf(sdp) ?: return Result.Failed
    val body = CallSignalAuth.offerBody(CallSignalAuth.Offer(callId, to, sdp, fp))
    val nonce = ByteArray(12).also(random::nextBytes).let(HouseholdKey::toHex)
    val header = CallSignalAuth.requestHeader(key, self, nonce, System.currentTimeMillis(), body) ?: return Result.Failed
    val conn = URL("http", host, port, CallSignalAuth.PATH).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = CONNECT_MS
      conn.readTimeout = READ_MS
      conn.requestMethod = "POST"
      conn.doOutput = true
      conn.instanceFollowRedirects = false
      conn.setRequestProperty("Content-Type", "application/json")
      conn.setRequestProperty(CallSignalAuth.HEADER, header)
      val bytes = body.toByteArray(Charsets.UTF_8)
      conn.setFixedLengthStreamingMode(bytes.size)
      conn.outputStream.use { it.write(bytes) }
      if (conn.responseCode == HTTP_CONFLICT) return Result.NotYet
      if (conn.responseCode != HTTP_OK) { Log.i(TAG, "offer refused: ${conn.responseCode}"); return Result.Failed }
      val text = conn.inputStream.use { input ->
        val buf = input.readBytes()
        if (buf.size > MAX_RESPONSE) return Result.Failed
        String(buf, Charsets.UTF_8)
      }
      val envelope = JSONObject(text)
      val payload = envelope.optString("payload")
      if (!CallSignalAuth.verifyResponse(key, nonce, payload, envelope.optString("sig"))) { Log.w(TAG, "answer signature bad"); return Result.Failed }
      return CallSignalAuth.parseAnswer(payload, callId)?.sdp?.let(Result::Answer) ?: Result.Failed
    } finally {
      conn.disconnect()
    }
  }

  private const val HTTP_CONFLICT = 409
  private const val HTTP_OK = 200
}

/**
 * Caller side: `POST /remote/callpeer`. Not session-authenticated (Portals hold no tokens for
 * each other); the request must carry a fresh household-signed header pinning the body, name a
 * call this Portal is ringing, come from the device it rang, and carry a fingerprint-bound SDP.
 */
internal object CallPeerRoute {
  private const val MAX_BODY = 64 * 1024
  private val replay = HouseholdMessage.ReplayGuard()

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    if (req.contentLength !in 1..MAX_BODY) return RemoteFeatureRoutes.json(413, RemoteFeatureRoutes.error("body_too_large"))
    val key = HouseholdKey.get(context) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    val text = try { req.bodyText(MAX_BODY) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_body"))
    }
    val self = Calling.selfId(context)
    val caller = CallSignalAuth.verifyRequest(req.header(CallSignalAuth.HEADER), text, key, System.currentTimeMillis(), replay, self)
        ?: return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    val offer = CallSignalAuth.parseOffer(text) ?: return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_offer"))
    if (offer.to != self) return RemoteFeatureRoutes.json(403, RemoteFeatureRoutes.error("not_for_this_portal"))
    val call = Calling.acceptRemoteOffer(offer.callId, caller.from)
        ?: return RemoteFeatureRoutes.json(409, RemoteFeatureRoutes.error("no_such_call"))
    val answer = CallMediaHub.answerBlocking(context, call, caller.from, offer.sdp)
    val fp = answer?.let(CallSignalAuth::fingerprintOf)
    if (answer == null || fp == null) {
      Calling.dispatch(CallEvent.MediaFailed(call.id, CallEndReason.FAILED, Calling.now()))
      return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("media_unavailable"))
    }
    val payload = CallSignalAuth.answerPayload(CallSignalAuth.Answer(call.id, answer, fp))
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload)
        .put("sig", CallSignalAuth.signResponse(key, caller.nonce, payload)))
  }
}
