/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Control Center on the paired phone (Portal Toolkit). The phone talks only to this Portal, which
 * keeps the Home Assistant token: the phone never sees it, nor any camera access token.
 * [RemoteCompanionRoutes] has already checked the paired session, the body limit and the JSON.
 *
 * - `GET  /remote/controlcenter` → everything; `POST {since, minute, want?}` → the delta ([CcRemoteState]).
 * - `GET  /remote/controlcenter/plan` → the floor plan ([CcPlanJson]).
 * - `POST /remote/controlcenter/act {entity|key, action, value?, on?, mode?, code?, ticket?}` → `{ok, done}`;
 *   an asking call answers `409 confirm` with a one-time ticket ([CcRemoteConfirm], [CcPhonePolicy]).
 * - `POST /remote/controlcenter/camera {entity, width?}` → the JPEG, fetched by this Portal.
 *
 * Every request is rate limited per phone ([CcRemoteLimiter]) and renews the live lease
 * ([ControlCenter.remoteWatch]). Nothing here logs a token, a code, a ticket or a picture.
 */
internal object CcRemoteRoute : RemoteFeatureRoutes.CallerRoute {
  const val STATE = "/remote/controlcenter"
  const val PLAN = "/remote/controlcenter/plan"
  const val ACT = "/remote/controlcenter/act"
  const val CAMERA = "/remote/controlcenter/camera"
  /** How long an act waits for Home Assistant's answer before saying "sent". */
  const val ANSWER_WAIT_MS = 5_000L

  private val journal = CcRemoteJournal()
  private val limiter = CcRemoteLimiter()
  private val confirm = CcRemoteConfirm()
  /**
   * The agent serves every route from a 4-thread pool; an act waiting on HA or a slow snapshot
   * holds a thread. At most [SLOTS] of each run at once, so the rest of the agent stays responsive.
   */
  private val actSlots = Semaphore(SLOTS)
  private val cameraSlots = Semaphore(SLOTS)
  @Volatile var clock: () -> Long = System::currentTimeMillis

  private val subPaths = listOf(PLAN, ACT, CAMERA).map(::Path)

  fun install() {
    RemoteFeatureRoutes.register(STATE, this)
    subPaths.forEach { RemoteFeatureRoutes.register(it.path, it) }
  }

  /** One registration per path; each carries its path into [route]. */
  private class Path(val path: String) : RemoteFeatureRoutes.CallerRoute {
    override fun handleFrom(context: Context, method: String, body: JSONObject, caller: String) = route(context, path, method, body, caller)
  }

  override fun handleFrom(context: Context, method: String, body: JSONObject, caller: String) = route(context, STATE, method, body, caller)

  internal fun route(context: Context, path: String, method: String, body: JSONObject, caller: String): FleetHttpServer.Response {
    if (caller.isBlank()) return err(401, "unauthorized")
    val (kind, allowed) = when (path) {
      STATE -> CcRemoteLimiter.Kind.STATE to setOf("GET", "POST")
      PLAN -> CcRemoteLimiter.Kind.PLAN to setOf("GET")
      ACT -> CcRemoteLimiter.Kind.ACT to setOf("POST")
      CAMERA -> CcRemoteLimiter.Kind.CAMERA to setOf("POST")
      else -> return err(404, "not_found")
    }
    if (method !in allowed) return err(405, "method_not_allowed")
    val wait = limiter.take(caller, kind, clock())
    if (wait > 0) return RemoteFeatureRoutes.json(429, RemoteFeatureRoutes.error("rate_limited").put("retryMs", wait))
    if (!HaConfig.isConfigured(context)) return err(503, "ha_not_configured")
    ControlCenter.remoteWatch(context)
    val s = ControlCenter.state(context).value
    return when (path) {
      STATE -> state(context, s, body)
      PLAN -> RemoteFeatureRoutes.json(200, CcRemoteState.plan(s))
      ACT -> slot(actSlots) { act(context, s, body, caller) }
      else -> slot(cameraSlots) { camera(context, s, body) }
    }
  }

  private fun state(context: Context, s: CcUiState, body: JSONObject): FleetHttpServer.Response {
    if (body.optString("want") == "tesla" && s.chargeWeek == null && s.registry?.tesla?.weekStat != null) ControlCenter.loadChargeWeek(context)
    val since = body.optLong("since", 0L).coerceAtLeast(0L)
    val minute = body.optLong("minute", -1L)
    return RemoteFeatureRoutes.json(200, CcRemoteState.json(s, journal, since, minute, clock(), FleetConfig.name(context)))
  }

  private fun act(context: Context, s: CcUiState, body: JSONObject, caller: String): FleetHttpServer.Response {
    val intent = when (val i = CcRemoteActions.build(s, body)) {
      is CcRemoteIntent.Invalid -> return err(i.status, i.code)
      is CcRemoteIntent.Call -> i
    }
    val seen = { ControlCenterConfig.scenesSeen(context) }
    val ask = CcPhonePolicy.ask(intent.entity, intent.call, s.settings, seen)
    val confirmed = ask != CcPolicy.Ask.NONE && confirm.redeem(body.optString("ticket"), caller, intent.key, clock())
    if (ask != CcPolicy.Ask.NONE && !confirmed) return confirmFirst(s, intent, ask, caller, body.has("ticket"))
    CcPhonePolicy.refusal(intent.entity, intent.call, s.settings, confirmed, seen)?.let {
      return RemoteFeatureRoutes.json(403, RemoteFeatureRoutes.error("refused").put("reason", it))
    }
    if (intent.call.domain == "scene") ControlCenterConfig.markSceneSeen(context, intent.entity.id)
    return send(context, intent.call)
  }

  private fun confirmFirst(s: CcUiState, i: CcRemoteIntent.Call, ask: CcPolicy.Ask, caller: String, retried: Boolean): FleetHttpServer.Response {
    val (title, text, button) = CcPhonePolicy.prompt(i.entity, i.call, ask, s.registry?.tesla?.label ?: CcTeslaIds.DEFAULT_LABEL)
    val camera = if (ask == CcPolicy.Ask.CAMERA_CONFIRM) CcRemoteSlices.cameraFor(s, i.entity) else null
    return RemoteFeatureRoutes.json(409, RemoteFeatureRoutes.error("confirm").put("ask", ask.name.lowercase())
        .put("ticket", confirm.issue(caller, i.key, clock())).put("ttlMs", CcRemoteConfirm.TTL_MS)
        .put("title", title).put("body", text).put("button", button).put("expired", retried)
        .put("code", ask == CcPolicy.Ask.PIN && i.entity.codeFormat != null).putOpt("camera", camera))
  }

  private fun send(context: Context, call: CcCall): FleetHttpServer.Response {
    val done = CountDownLatch(1)
    val answer = AtomicReference<Pair<Boolean, String?>>()
    ControlCenter.sendFromPhone(context, call) { ok, msg -> answer.set(ok to msg); done.countDown() }?.let { reason ->
      return RemoteFeatureRoutes.json(503, RemoteFeatureRoutes.error("ha_unavailable").put("reason", reason))
    }
    if (!done.await(ANSWER_WAIT_MS, TimeUnit.MILLISECONDS)) return RemoteFeatureRoutes.json(202, RemoteFeatureRoutes.ok().put("done", false))
    val (ok, msg) = answer.get()
    return if (ok) RemoteFeatureRoutes.json(200, RemoteFeatureRoutes.ok().put("done", true))
    else RemoteFeatureRoutes.json(502, RemoteFeatureRoutes.error("ha_refused").put("message", (msg ?: "Home Assistant refused").take(MAX_MESSAGE)))
  }

  private fun camera(context: Context, s: CcUiState, body: JSONObject): FleetHttpServer.Response {
    val cam = s.entity(body.optString("entity"))?.takeIf { it.domain == "camera" } ?: return err(404, "unknown_camera")
    val width = body.optInt("width", CcCameraLogic.SNAPSHOT_WIDTH)
    if (width !in CcRemoteCamera.MIN_WIDTH..CcRemoteCamera.MAX_WIDTH) return err(400, "bad_width")
    val shot = CcRemoteCamera.fetch(context, cam.id, width, cam.attrs) ?: return err(502, "snapshot_failed")
    val type = if (shot.bytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"
    val headers = buildMap {
      put("Cache-Control", "no-store")
      shot.captured?.let { put(CAPTURED_HEADER, it.toString()) }
    }
    return FleetHttpServer.Response.stream(200, type, shot.bytes.size.toLong(), headers) { it.write(shot.bytes) }
  }

  private inline fun slot(slots: Semaphore, block: () -> FleetHttpServer.Response): FleetHttpServer.Response {
    if (!slots.tryAcquire()) return RemoteFeatureRoutes.json(429, RemoteFeatureRoutes.error("busy").put("retryMs", BUSY_RETRY_MS))
    return try { block() } finally { slots.release() }
  }

  private fun err(status: Int, code: String) = RemoteFeatureRoutes.json(status, RemoteFeatureRoutes.error(code))

  const val CAPTURED_HEADER = "X-Cc-Captured"
  private const val MAX_MESSAGE = 160
  private const val SLOTS = 2
  private const val BUSY_RETRY_MS = 500L
}
