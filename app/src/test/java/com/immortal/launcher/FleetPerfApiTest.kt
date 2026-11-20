/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `/perf` capture commands, `/perf/state`, the filmstrip render and the diagnostics-build gate. */
class FleetPerfApiTest {
  private val host = FleetTestHost()
  private val api = FleetPerfApi(host, host)

  private fun command(body: String) = api.capture(FleetReq.post("/perf", body))
  private val start = JSONObject().put("action", "start").put("durationSec", 20).toString()
  private val stop = JSONObject().put("action", "stop").toString()

  @Test fun getReadsTheFullSnapshot() {
    assertEquals("full", FleetReq.json(api.capture(FleetReq.get("/perf"))).getString("capture"))
    assertEquals("405 method_not_allowed", FleetReq.outcome(api.capture(FleetReq.of("PUT", "/perf"))))
  }

  @Test fun onlyValidCommandsRun() {
    for (body in listOf("nope", "", "{}", "{\"action\":\"pause\"}", "{\"action\":\"stop\",\"x\":1}",
        "{\"action\":\"start\",\"durationSec\":5}", "{\"action\":\"start\",\"durationSec\":901}", "{\"action\":\"start\",\"evil\":1}")) {
      assertEquals(body, "400 invalid_capture_command", FleetReq.outcome(command(body)))
    }
    assertTrue(host.calls.isEmpty())
  }

  @Test fun startNeedsDevModeButStopDoesNot() {
    assertEquals("403 dev_mode_required", FleetReq.outcome(command(start)))
    assertTrue(host.calls.isEmpty())
    assertEquals("full", FleetReq.json(command(stop)).getString("capture"))
    host.devModeOn = true
    assertEquals("full", FleetReq.json(command(start)).getString("capture"))
    assertEquals(listOf("perfStop", "perfStart:20"), host.calls)
  }

  @Test fun busyUnappliedAndPendingCommandsAreReportedDistinctly() {
    host.devModeOn = true
    host.startResult = null
    assertEquals("409 filmstrip_busy", FleetReq.outcome(command(start)))
    host.startResult = false
    assertEquals("503 capture_command_not_applied", FleetReq.outcome(command(start)))
    host.stopResult = false
    assertEquals("503 capture_command_not_applied", FleetReq.outcome(command(stop)))
    host.pending = true
    for (body in listOf(start, stop)) {
      val resp = command(body)
      val o = FleetReq.json(resp)
      assertEquals(202, resp.status)
      assertTrue(o.getBoolean("ok"))
      assertEquals("completion_unconfirmed", o.getString("result"))
      assertTrue(o.getBoolean("checkStateBeforeRetry"))
    }
  }

  @Test fun stateIsReadOnlyWithAnOptionalReadinessView() {
    assertEquals("state", FleetReq.json(api.state(FleetReq.get("/perf/state"))).getString("capture"))
    assertEquals("readiness", FleetReq.json(api.state(FleetReq.get("/perf/state", "readiness=1"))).getString("capture"))
    assertEquals("state", FleetReq.json(api.state(FleetReq.get("/perf/state", "readiness=true"))).getString("capture"))
    assertEquals("405 method_not_allowed", FleetReq.outcome(api.state(FleetReq.post("/perf/state", "{}"))))
  }

  @Test fun filmstripRenderIsGetOnly() {
    assertEquals(1, FleetReq.json(api.filmstripRender(FleetReq.get("/perf/filmstrip-render"))).getInt("render"))
    assertEquals("405 method_not_allowed", FleetReq.outcome(api.filmstripRender(FleetReq.post("/perf/filmstrip-render"))))
  }

  @Test fun gatedDiagnosticsAnswerOnlyInDiagnosticsBuilds() {
    val gated = api.gated(host::recentApps)
    assertEquals("recent", FleetReq.json(gated(FleetReq.get("/perf/recent-apps"))).getString("diag"))
    assertEquals("405 method_not_allowed", FleetReq.outcome(gated(FleetReq.post("/perf/recent-apps"))))
    host.homeDiagnostics = false
    assertEquals("403 diagnostics_disabled", FleetReq.outcome(gated(FleetReq.get("/perf/recent-apps"))))
    // The method is checked first.
    assertEquals("405 method_not_allowed", FleetReq.outcome(gated(FleetReq.post("/perf/recent-apps"))))
  }
}
