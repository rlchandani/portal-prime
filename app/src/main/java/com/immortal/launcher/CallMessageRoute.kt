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
 * Recipient side of `POST /remote/callmsg`. Not session-authenticated (Portals hold no tokens for
 * each other): the household-signed `vmsg_put` header must be fresh, unreplayed, addressed to this
 * Portal and announce an acceptable size and length before a byte is read; the body must then open
 * chunk by chunk under the household-derived transit key. Arrival is silent (quiet hours or not).
 */
internal object CallMessageRoute {
  private const val TAG = "HearthpaneCallMsg"
  private val replay = HouseholdMessage.ReplayGuard()

  fun handle(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (req.method != "POST") return RemoteFeatureRoutes.json(405, RemoteFeatureRoutes.error("method_not_allowed"))
    val key = HouseholdKey.get(context) ?: return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("no_household_key"))
    val v = CallMessageWire.verify(req.header(CallMessageWire.HEADER), key, System.currentTimeMillis(), replay, Calling.selfId(context))
        ?: return RemoteFeatureRoutes.json(401, RemoteFeatureRoutes.error("unauthorized"))
    if (req.contentLength.toLong() != v.put.sealedBytes) return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("length_mismatch"))
    val on = CallingConfig.load(context).messagesOn
    val refusal = try {
      CallMessageStore.receive(context, v, on, Calling.now()) { out -> req.streamBodyTo(out) }
    } catch (e: CallMessageCrypto.Tampered) {
      Log.w(TAG, "message rejected: ${e.message}")
      return RemoteFeatureRoutes.json(400, RemoteFeatureRoutes.error("bad_message"))
    } catch (e: Exception) {
      Log.w(TAG, "message not stored: ${e.javaClass.simpleName}")
      return RemoteFeatureRoutes.json(500, RemoteFeatureRoutes.error("store_failed"))
    }
    if (refusal != null) return RemoteFeatureRoutes.json(when (refusal) {
      CallMessageRefusal.MESSAGES_OFF -> 403
      CallMessageRefusal.TOO_LARGE -> 413
      CallMessageRefusal.BUSY -> 503
      else -> 409
    },
        RemoteFeatureRoutes.error(refusal.code))
    val payload = CallMessageWire.ackPayload(v.put.id)
    return RemoteFeatureRoutes.json(200, JSONObject().put("payload", payload).put("sig", CallMessageWire.signAck(key, v.nonce, payload)))
  }
}
