/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Video messages with paired phones (phase 2b). Every request needs the phone's session (Bearer)
 * and a fresh household-signed header from that session's phone id:
 * - `POST /remote/call/msg`: the phone leaves a message here. `vmsg_put` header, sealed body,
 *   streamed and opened chunk by chunk exactly like a Portal's upload ([CallMessageRoute]).
 * - `POST /remote/call/msg/get` `{i}`: the phone collects a message this Portal left for it. The
 *   body is the transit-sealed file; `X-Immortal-Sig` binds id and length to the request nonce.
 * - `POST /remote/call/msg/ack` `{i}`: the phone holds it now; this Portal deletes its copy.
 */
internal object CallPhoneMessageRoute {
  private const val TAG = "HearthpaneCallMsg"
  const val PUT = "/remote/call/msg"
  const val GET = "/remote/call/msg/get"
  const val ACK = "/remote/call/msg/ack"
  val PATHS = setOf(PUT, GET, ACK)
  const val SIG_HEADER = "X-Immortal-Sig"
  const val GET_DOMAIN = "vmsg_get"
  private const val MAX_JSON = 1024
  private val replay = HouseholdMessage.ReplayGuard()

  fun handle(context: Context, req: FleetHttpServer.Request, token: String?): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    if (token == null || !RemotePairing.isEnabled(context) || !RemotePairing.isValidSession(context, token)) return unauthorized()
    val key = HouseholdKey.get(context) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    val phoneId = CallPhoneId.of(token)
    return if (req.path == PUT) put(context, req, key, phoneId) else small(context, req, key, phoneId)
  }

  private fun put(context: Context, req: FleetHttpServer.Request, key: ByteArray, phoneId: String): FleetHttpServer.Response {
    val v = CallMessageWire.verifyFromPhone(req.header(CallMessageWire.HEADER), key, System.currentTimeMillis(), replay,
        Calling.selfId(context), phoneId) ?: return unauthorized()
    if (req.contentLength.toLong() != v.put.sealedBytes) return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("length_mismatch"))
    val config = CallingConfig.load(context)
    val refusal = try {
      CallMessageStore.receive(context, v, config.messagesOn && config.phonesOn, Calling.now()) { out -> req.streamBodyTo(out) }
    } catch (e: CallMessageCrypto.Tampered) {
      Log.w(TAG, "phone message rejected: ${e.message}")
      return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_message"))
    } catch (e: Exception) {
      Log.w(TAG, "phone message not stored: ${e.javaClass.simpleName}")
      return RemoteFeatureRoutes.json(500, RemoteFeatureRoutes.error("store_failed"))
    }
    if (refusal != null) return RemoteFeatureRoutes.json(if (refusal == CallMessageRefusal.MESSAGES_OFF) 403 else 409,
        RemoteFeatureRoutes.error(refusal.code))
    val payload = CallMessageWire.ackPayload(v.put.id)
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload).put("sig", CallMessageWire.signAck(key, v.nonce, payload)))
  }

  private fun small(context: Context, req: FleetHttpServer.Request, key: ByteArray, phoneId: String): FleetHttpServer.Response {
    if (req.contentLength !in 1..MAX_JSON) return RemoteFeatureRoutes.json(413, RemoteFeatureRoutes.error("body_too_large"))
    val text = try { req.bodyText(MAX_JSON) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_body"))
    }
    val auth = CallPhoneAuth.verify(req.header(CallPhoneAuth.HEADER), req.path, text, key, phoneId, System.currentTimeMillis(), replay)
        ?: return unauthorized()
    val id = runCatching { JSONObject(text).optString("i") }.getOrNull()?.takeIf(CallMessageList::isId)
        ?: return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_id"))
    if (req.path == ACK) {
      CallPhoneOutbox.delivered(context, phoneId, id)
      return RemoteFeatureRoutes.json(200, CallPhoneAuth.envelope(key, auth.nonce, JSONObject().put("ok", true).put("i", id).toString()))
    }
    val m = PhoneOutbox.find(CallPhoneOutbox.list(context), phoneId, id)
        ?: return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("no_such_message"))
    val file = CallPhoneOutbox.file(context, id)?.takeIf { it.length() == m.sealedBytes }
        ?: return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("no_such_message"))
    val input = runCatching { file.inputStream() }.getOrNull() ?: return RemoteFeatureRoutes.json(404, RemoteFeatureRoutes.error("no_such_message"))
    val sig = CallPhoneAuth.sign(key, GET_DOMAIN, auth.nonce, getBinding(id, m.sealedBytes))
    return FleetHttpServer.Response.stream(200, "application/octet-stream", m.sealedBytes, mapOf(SIG_HEADER to sig),
        onClose = { runCatching { input.close() } }) { out -> input.copyTo(out, CallMessageCrypto.SEALED_CHUNK) }
  }

  /** What `X-Immortal-Sig` signs (with the request nonce): the message id and its sealed length. */
  fun getBinding(id: String, sealedBytes: Long): String = "$id|$sealedBytes"

  private fun unauthorized() = RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
}
