/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.FleetReply.err
import com.immortal.launcher.FleetReply.json
import com.immortal.launcher.FleetReply.ok
import com.immortal.launcher.FleetReply.parseJson
import com.immortal.launcher.FleetReply.requireMethod
import org.json.JSONObject

/**
 * `/perf` (ephemeral operator diagnostics; no preferences, content capture or scheduled
 * monitoring), `/perf/state` and the home diagnostics that only diagnostics builds expose. Pure:
 * capture and diagnostics come in through [FleetPerf], dev mode through [FleetDevice].
 */
internal class FleetPerfApi(private val perf: FleetPerf, private val device: FleetDevice) {

  fun capture(req: FleetHttpServer.Request): FleetHttpServer.Response = when (req.method) {
    "GET" -> json(200, perf.snapshot())
    "POST" -> command(req)
    else -> FleetReply.methodNotAllowed()
  }

  private fun command(req: FleetHttpServer.Request): FleetHttpServer.Response {
    val body = parseJson(req.bodyText())
    val command = body?.let { FramePerformanceRequest.parse(it) }
    return if (command == null) json(400, err("invalid_capture_command"))
    else if (command.action == "start" && !device.devMode()) json(403, err("dev_mode_required"))
    else {
      try {
        val applied = if (command.action == "start") perf.start(command) else perf.stop()
        if (applied == null) json(409, err("filmstrip_busy"))
        else if (applied) json(200, perf.snapshot())
        else json(503, err("capture_command_not_applied"))
      } catch (_: FramePerformanceCommandPending) {
        // A begun command cannot be cancelled safely. Report uncertainty instead of inviting a retry.
        json(202, ok().put("result", "completion_unconfirmed").put("checkStateBeforeRetry", true))
      }
    }
  }

  fun state(req: FleetHttpServer.Request): FleetHttpServer.Response = requireMethod("GET", req) {
    json(200, if (req.queryParam("readiness") == "1") perf.readinessSnapshot() else perf.stateSnapshot())
  }

  fun filmstripRender(req: FleetHttpServer.Request): FleetHttpServer.Response =
      requireMethod("GET", req) { perf.filmstripRender() }

  /** A GET-only diagnostic that diagnostics builds expose and others refuse. */
  fun gated(snapshot: () -> JSONObject): FleetHandler = { req ->
    requireMethod("GET", req) {
      if (!perf.homeDiagnosticsEnabled()) json(403, err("diagnostics_disabled")) else json(200, snapshot())
    }
  }
}
