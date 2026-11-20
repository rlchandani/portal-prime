/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone ↔ Portal calls on the home Wi-Fi (`/remote/call/poll|send|offer`). Each request needs a
 * paired phone session (Bearer, checked here: the fleet token is not a phone) *and* a fresh
 * household-signed header pinning body and path ([CallPhoneAuth]); responses are signed over the
 * request nonce. The phone is always the WebRTC offerer and its SDP fingerprint is bound exactly as
 * between Portals, so media keys are authenticated by the household key end to end.
 */
internal object CallPhoneRoute {
  private const val TAG = "HearthpaneCallPhone"
  const val POLL = "/remote/call/poll"
  const val SEND = "/remote/call/send"
  const val OFFER = "/remote/call/offer"
  val PATHS = setOf(POLL, SEND, OFFER)
  private const val MAX_BODY = 48 * 1024
  private val replay = HouseholdMessage.ReplayGuard()

  fun handle(context: Context, req: FleetHttpServer.Request, token: String?): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    if (token == null || !RemotePairing.isEnabled(context) || !RemotePairing.isValidSession(context, token)) return unauthorized()
    if (req.contentLength !in 1..MAX_BODY) return RemoteFeatureRoutes.json(413, RemoteFeatureRoutes.error("body_too_large"))
    val key = HouseholdKey.get(context) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    val text = try { req.bodyText(MAX_BODY) } catch (e: Exception) {
      return FleetHttpServer.requestBodyError(e) ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_body"))
    }
    val phoneId = CallPhoneId.of(token)
    val auth = CallPhoneAuth.verify(req.header(CallPhoneAuth.HEADER), req.path, text, key, phoneId, System.currentTimeMillis(), replay)
        ?: return unauthorized()
    val body = runCatching { JSONObject(text) }.getOrNull() ?: return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_json"))
    return when (req.path) {
      POLL -> signed(key, auth, poll(context, phoneId, body))
      SEND -> send(context, phoneId, body)?.let { signed(key, auth, it) }
          ?: RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_message"))
      else -> offer(context, key, auth, text)
    }
  }

  private fun poll(context: Context, phoneId: String, body: JSONObject): JSONObject {
    val now = Calling.now()
    val info = CallPhoneWire.pollInfo(body)
    // A background poll parks until a message (or a newly queued video message) arrives.
    val polled = CallPhoneMailbox.poll(phoneId, info, now, Calling::now) {
      CallPhoneOutbox.waiting(context, phoneId).any { it.atMs > now }
    }
    val msgs = polled.messages
    val config = CallingConfig.load(context)
    val portal = JSONObject().put("id", Calling.selfId(context)).put("name", Calling.selfName(context))
        .put("inCall", Calling.state.value.call != null)
        .put("quiet", DinnerQuiet.isQuiet(ChimeConfig.load(context), now, Calling.zone()))
        .put("acceptsPhones", config.who == CallWhoCanCall.HOME && config.phonesOn)
    val waiting = CallPhoneOutbox.waiting(context, phoneId)
    return JSONObject().put("ok", true).put("portal", portal).put("now", Calling.now()).put("w", polled.grantedMs)
        .put("msgs", JSONArray(msgs.map { JSONObject().put("t", it.type).put("b", JSONObject(it.body)) }))
        .apply { if (waiting.isNotEmpty()) put("vm", JSONArray(waiting.map(PhoneOutbox::announce))) }
  }

  /** A phone → Portal call message, dispatched exactly like the bus would; null when malformed. */
  private fun send(context: Context, phoneId: String, body: JSONObject): JSONObject? {
    val type = body.optString("t").takeIf { it in CallPhoneWire.SENDABLE } ?: return null
    val b = body.optJSONObject("b") ?: return null
    val self = Calling.selfId(context) ?: return null
    if (b.optString("to") != self) return null
    val now = Calling.now()
    val name = CallPhoneMailbox.phone(phoneId)?.name ?: CallPhoneWire.DEFAULT_NAME
    return when (type) {
      CallWire.RING -> CallPhoneWire.decodeRing(b)?.let { ring ->
        val decision = Calling.onPhoneRing(context, CallContact(phoneId, ring.name, kind = CallContact.Kind.PHONE), ring)
        JSONObject().put("ok", true).put("r", replyFor(decision).wire)
            .apply { if (decision == IncomingDecision.Reject(CallReply.QUIET)) put("qe", ChimeConfig.load(context).quietEndMin) }
      }
      CallWire.REPLY -> CallPhoneWire.decodeReply(b)?.let {
        Calling.dispatch(CallEvent.Replied(it.id, phoneId, it.reply, null, now)); ok()
      }
      CallWire.END -> CallWire.decodeEnd(b)?.let {
        Calling.dispatch(CallEvent.RemoteEnded(it.id, phoneId, it.reason, now, it.movedTo)); ok()
      }
      // The phone vouches for the Portal it moved its call to (only a connected member's vouch counts).
      CallWire.JOIN -> CallPhoneWire.decodeJoin(b)?.let { Calling.dispatch(CallEvent.RemoteJoin(it.id, phoneId, it.newcomer, now)); ok() }
      CallPhoneWire.MOVED -> CallPhoneWire.decodeMoved(b)?.let { Calling.dispatch(CallEvent.RemoteMoved(it.id, phoneId, now)); ok() }
      CallPhoneWire.PULL -> CallPhoneWire.decodeMoved(b)?.let { Calling.dispatch(CallEvent.RemotePull(it.id, phoneId, now)); ok() }
      CallWire.MEDIA -> CallWire.decodeMedia(b)?.let {
        Calling.dispatch(CallEvent.RemoteMedia(it.id, phoneId, it.cameraOn, it.micOn)); ok()
      }
      else -> null
    }.also { if (it != null) Log.i(TAG, "$type from $name") }
  }

  private fun offer(context: Context, key: ByteArray, auth: CallPhoneAuth.Request, text: String): FleetHttpServer.Response {
    val offer = CallSignalAuth.parseOffer(text) ?: return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_offer"))
    if (offer.to != Calling.selfId(context)) return RemoteFeatureRoutes.json(403, RemoteFeatureRoutes.error("not_for_this_portal"))
    val call = Calling.acceptRemoteOffer(offer.callId, auth.phoneId)
        ?: return RemoteFeatureRoutes.json(409, RemoteFeatureRoutes.error("no_such_call"))
    val answer = CallMediaHub.answerBlocking(context, call, auth.phoneId, offer.sdp)
    val fp = answer?.let(CallSignalAuth::fingerprintOf)
    if (answer == null || fp == null) {
      Calling.dispatch(CallEvent.MediaFailed(call.id, CallEndReason.FAILED, Calling.now()))
      return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("media_unavailable"))
    }
    val payload = CallSignalAuth.answerPayload(CallSignalAuth.Answer(call.id, answer, fp))
    return RemoteFeatureRoutes.json(200, CallPhoneAuth.envelope(key, auth.nonce, payload, CallPhoneAuth.ANSWER_DOMAIN))
  }

  internal fun replyFor(decision: IncomingDecision): CallReply = when (decision) {
    is IncomingDecision.Reject -> decision.reply
    else -> CallReply.RINGING
  }

  private fun ok() = JSONObject().put("ok", true)

  private fun signed(key: ByteArray, auth: CallPhoneAuth.Request, payload: JSONObject) =
      RemoteFeatureRoutes.json(200, CallPhoneAuth.envelope(key, auth.nonce, payload.toString()))

  private fun unauthorized() = RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
}
