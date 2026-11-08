/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject

/**
 * Wireless-update endpoints over [OtaUpdater]:
 * - fleet `GET|POST /update/ota` (bearer token, checked by [FleetRoutes]; POST may send `force:true`
 *   to run in developer mode),
 * - paired phone `GET|POST /remote/ota` (session auth upstream in [RemoteCompanionRoutes]; never forced).
 *
 * GET → `{ok:true, ota:<state>}` ([OtaWire.toJson]). POST `{action:"check"|"install", force?}`:
 * check waits up to [OtaConstants.CHECK_WAIT_MS] → 200 `{ok:true, ota}` (202 `{ok:true, pending:true, ota}`
 * when still running); install starts in the background → 202 `{ok:true, status:"started", ota}`.
 * Errors: 400 `bad_json|unknown_action`, 409 `busy|dev_mode` (with `ota`), 405 `method_not_allowed`.
 */
internal object OtaRoutes : RemoteFeatureRoutes.Route {
  const val FLEET_PATH = "/update/ota"
  const val REMOTE_PATH = "/remote/ota"
  private const val HTTP_OK = 200
  private const val HTTP_ACCEPTED = 202
  private const val HTTP_BAD_REQUEST = 400
  private const val HTTP_METHOD_NOT_ALLOWED = 405
  private const val HTTP_CONFLICT = 409

  fun install() = RemoteFeatureRoutes.register(REMOTE_PATH, this)

  /** Paired phone: same shapes as the fleet route, but `force` is ignored. */
  override fun handle(context: Context, method: String, body: JSONObject): FleetHttpServer.Response =
      respond(context, method, body, allowForce = false)

  fun fleet(context: Context, req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = if (req.method == "POST") parse(req.bodyText()) ?: return json(HTTP_BAD_REQUEST, error("bad_json"))
        else JSONObject()
    return respond(context, req.method, body, allowForce = true)
  }

  private fun respond(context: Context, method: String, body: JSONObject, allowForce: Boolean) = when (method) {
    "GET" -> json(HTTP_OK, ok(OtaUpdater.refresh(context)))
    "POST" -> post(context, body, allowForce)
    else -> json(HTTP_METHOD_NOT_ALLOWED, error("method_not_allowed"))
  }

  private fun post(context: Context, body: JSONObject, allowForce: Boolean): FleetHttpServer.Response {
    val request = OtaWire.decode(body) ?: return json(HTTP_BAD_REQUEST, error("unknown_action"))
    val force = allowForce && request.force
    val current = OtaUpdater.refresh(context)
    if (current.devMode && !force) return json(HTTP_CONFLICT, error("dev_mode").put("ota", OtaWire.toJson(current)))
    val trigger = if (request.action == OtaAction.INSTALL) OtaTrigger.INSTALL else OtaTrigger.CHECK
    val run = OtaUpdater.submit(context, trigger, force)
        ?: return json(HTTP_CONFLICT, error("busy").put("ota", OtaWire.toJson(OtaUpdater.state.value)))
    if (trigger == OtaTrigger.INSTALL) {
      return json(HTTP_ACCEPTED, ok(OtaUpdater.state.value).put("status", "started"))
    }
    val done = try {
      run.get(OtaConstants.CHECK_WAIT_MS, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
      null
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      null
    }
    return if (done == null) json(HTTP_ACCEPTED, ok(OtaUpdater.state.value).put("pending", true))
        else json(HTTP_OK, ok(done))
  }

  private fun ok(state: OtaState) = JSONObject().put("ok", true).put("ota", OtaWire.toJson(state))
  private fun error(code: String) = JSONObject().put("ok", false).put("error", code)
  private fun json(status: Int, body: JSONObject) = FleetHttpServer.Response(status, body.toString())
  private fun parse(text: String): JSONObject? =
      runCatching { JSONObject(if (text.isBlank()) "{}" else text) }.getOrNull()
}
