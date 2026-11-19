/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * The derived parts of the phone's state (pure, unit tested), worked out here with the very
 * helpers the Portal's own screens use — alerts, the Security sensor list, people, cameras,
 * scenes and room summaries — so the phone shows the same house in the same words. Kids mode
 * plays no part (see [CcPhonePolicy]); dismissals made on this Portal's screen don't hide
 * anything from the phone either.
 */
internal object CcRemoteSlices {
  const val MAX_SCENES = 24

  fun roomName(s: CcUiState): (String) -> String? = { id -> s.plan?.roomOf(id)?.name ?: s.registry?.areaName(id) }

  /** Active alerts for the whole house (the car's own sensors live on the Tesla screen). */
  fun alerts(s: CcUiState, now: Long): List<CcAlert> {
    val car = s.registry?.tesla?.all.orEmpty()
    val house = if (car.isEmpty()) s.entities else s.entities.filterKeys { it !in car }
    return CcAlerts.active(house, now, s.settings.warningAfterMin, roomName(s), s.appliancesDone)
  }

  fun alertsJson(s: CcUiState, now: Long): JSONArray = JSONArray().also { out ->
    val rooms: (String) -> String? = { id -> s.plan?.roomOf(id)?.id }
    alerts(s, now).forEach { a ->
      val item = CcAlertRules.item(a, true, s.entities, rooms, s.connection.canControl, now, ::ccTime)
      val act = item.op as? CcAlertOp.Act
      out.put(JSONObject().put("id", a.id).put("sev", a.severity.name.lowercase()).put("kind", a.kind.name.lowercase())
          .put("entity", a.entityId).put("title", a.title).put("body", item.line).put("since", a.since)
          .put("icon", item.icon).put("action", a.action.name.lowercase())
          .putOpt("label", item.label)
          .putOpt("actEntity", act?.entity?.id).putOpt("act", act?.let { actKey(it.call) })
          .putOpt("room", (item.op as? CcAlertOp.OpenRoom)?.roomId ?: s.plan?.roomOf(a.entityId)?.id))
    }
  }

  /** The phone action that makes [call] (close the garage, shut the water). */
  private fun actKey(call: CcCall): String = when (call.service) {
    "close_cover", "close_valve" -> "close"
    "turn_off" -> "power_off"
    else -> call.service
  }

  fun securityJson(s: CcUiState, now: Long): JSONObject {
    val phone = CcPhonePolicy.settings(s.settings)
    val sections = CcSecurityList.sections(s.entities.values, phone, roomName(s))
    val panel = s.entities.values.filter { it.domain == "alarm_control_panel" }.minByOrNull { it.id }
    val side = CcSecurity.side(s.entities.values.filter { it.domain == "person" }.sortedBy { it.name.lowercase() },
        s.entities.values, ::ccTime, panel)
    return JSONObject().putOpt("alarm", panel?.let { alarmJson(it, s.settings, now) })
        .put("readiness", JSONObject().put("title", side.readiness.title).putOpt("line", side.readiness.line)
            .put("tone", side.readiness.tone.name.lowercase()))
        .put("sections", JSONArray(sections.map(::sectionJson)))
        .put("people", JSONArray(side.people.map { p ->
          JSONObject().put("id", p.id).put("name", p.name).put("home", p.home).put("line", p.line)
        }))
        .put("checks", JSONArray(side.checks.map { c ->
          JSONObject().put("icon", c.icon).put("title", c.title).put("detail", c.detail).putOpt("tag", c.tag).putOpt("ok", c.ok)
        }))
  }

  private fun alarmJson(panel: CcEntity, settings: ControlCenterConfig.Settings, now: Long): JSONObject {
    val remaining = if (panel.state == "arming") CcSecurity.exitRemaining(panel.lastChanged, settings.exitDelaySec, now) else 0
    return JSONObject().put("id", panel.id).put("state", panel.state).put("title", CcSecurity.title(panel))
        .put("subtitle", CcSecurity.subtitle(panel, remaining)).put("tone", CcSecurity.tone(panel.state).name.lowercase())
        .put("code", panel.codeFormat != null)
        .put("modes", JSONArray(CcAlarmMode.offered(panel).map { it.name.lowercase() }))
        .putOpt("current", CcSecurity.currentMode(panel)?.name?.lowercase())
  }

  private fun sectionJson(sec: CcSecSection): JSONObject = JSONObject().put("group", sec.group.name.lowercase())
      .put("title", sec.group.title).put("icon", sec.group.icon).put("tag", sec.tag).putOpt("ok", sec.ok).put("summary", sec.summary)
      .put("items", JSONArray(sec.items.map { i ->
        JSONObject().put("id", i.id).put("name", i.name).putOpt("area", i.area).put("state", i.state).put("since", i.since)
            .put("attention", i.attention).put("unavailable", i.unavailable).putOpt("action", i.action?.name?.lowercase())
      }))

  fun camerasJson(s: CcUiState): JSONArray = JSONArray().also { out ->
    val cams = s.entities.values.filter { it.domain == "camera" }.sortedBy { it.name.lowercase() }
    CcCameraLogic.gridCameras(cams, cams.size).forEach { c ->
      val info = CcCamInfo.of(s, c.id)
      out.put(JSONObject().put("id", c.id).put("name", c.name).put("canStream", info.canStream).putOpt("captured", info.captured)
          .putOpt("platform", CcCamLiveness.platformName(info.platform)).put("why", CcCamLiveness.whyNotLive(info.platform))
          .put("refresh", info.refreshButton != null || !info.unavailable).put("unavailable", info.unavailable)
          .putOpt("room", s.plan?.roomOf(c.id)?.id))
    }
  }

  /** The camera the garage confirm shows (the Portal's rule): one in the same plan room, else a "garage" camera. */
  fun cameraFor(s: CcUiState, e: CcEntity): String? =
      s.plan?.roomOf(e.id)?.devices?.map { it.entityId }?.firstOrNull { it.startsWith("camera.") && s.entity(it) != null }
          ?: s.entities.keys.filter { it.startsWith("camera.") && "garage" in it }.minOrNull()

  fun scenesJson(s: CcUiState): JSONArray {
    val all = s.entities.values.filter { it.domain == "scene" || it.domain == "script" }
    val ordered = CcSideLogic.pick(all, kids = false).let { top -> top + all.filter { it !in top }.sortedBy { it.name.lowercase() } }
    return JSONArray(ordered.take(MAX_SCENES).map { e ->
      val tone = CcSideLogic.tone(e)
      JSONObject().put("id", e.id).put("name", e.name).put("tone", tone.name.lowercase()).put("icon", CcSideLogic.icon(tone))
          .put("sensitive", CcPolicy.sensitive(e))
    })
  }

  /** Per room on every floor: lights, the glows to draw, climate and what's open (the plan's own summary). */
  fun roomsJson(s: CcUiState): JSONArray = JSONArray().also { out ->
    val climates = s.entities.values.filter { it.domain == "climate" }
    s.plan?.floors.orEmpty().forEach { f ->
      CcRooms.views(f, s.entities, climates).forEach { v -> out.put(roomJson(f.id, v)) }
    }
  }

  private fun roomJson(floorId: String, v: CcRoomView): JSONObject = JSONObject().put("id", v.room.id).put("floor", floorId)
      .put("lightsOn", v.lightsOn).put("lights", v.lights).putOpt("temp", v.temperature).putOpt("humidity", v.humidity)
      .put("motion", v.motion).put("open", v.openCount).putOpt("powerW", v.powerW).putOpt("climate", v.climateId)
      .put("glows", JSONArray(v.glows.map { g ->
        JSONObject().put("id", g.entityId).put("fx", g.fx.toDouble()).put("fy", g.fy.toDouble()).put("rgb", g.rgb).put("k", g.strength.toDouble())
      }))
}
