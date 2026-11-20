/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** This Portal as the engine sees it: settings, storage and outputs in memory, every output recorded. */
internal class FakeCcHost : CcHost {
  var settings = ControlCenterConfig.Settings()
  var baseUrl = "http://ha.test:8123"
  var token = "tok"
  var online = true
  var plan: CcPlan? = null
  var dry = false
  var test: CcPlan? = null
  val scenes = LinkedHashSet<String>()
  val savedPlans = ArrayList<CcPlan>()
  val alertPlans = ArrayList<CcBackgroundPolicy.Plan>()
  val banners = ArrayList<Pair<CcAlert, Boolean>>()
  var dots = 0

  override fun settings() = settings
  override fun haConfigured() = baseUrl.isNotBlank() && token.isNotBlank()
  override fun haBaseUrl() = baseUrl
  override fun haToken() = token
  override fun online() = online
  override fun loadPlan() = plan
  override fun savePlan(plan: CcPlan): Boolean { this.plan = plan; savedPlans += plan; return true }
  override fun dryRun() = dry
  override fun setDryRun(on: Boolean) { dry = on }
  override fun testPlan() = test
  override fun saveTestPlan(plan: CcPlan): Boolean { test = plan; return true }
  override fun clearTestPlan() { test = null }
  override fun scenesSeen(): Set<String> = scenes.toSet()
  override fun markSceneSeen(id: String) { scenes += id }
  override fun presentAlerts(plan: CcBackgroundPolicy.Plan, settings: ControlCenterConfig.Settings, appVisible: Boolean, dryRun: Boolean) {
    alertPlans += plan
  }
  override fun offerBanner(alert: CcAlert, settings: ControlCenterConfig.Settings, appVisible: Boolean, dryRun: Boolean) {
    banners += alert to dryRun
  }
  override fun homeDot(state: CcUiState, visible: Boolean) { dots++ }
}

internal class RecordingCcLog : CcLog {
  val lines = ArrayList<String>()
  override fun i(message: String) { lines += "i $message" }
  override fun w(message: String, error: Throwable?) { lines += "w $message" + (error?.let { " (${it.javaClass.simpleName})" } ?: "") }
}

/** A Home Assistant on the other end of each socket the engine opens. */
internal class FakeCcSockets : CcSocketFactory {
  inner class Socket(val url: String, val events: CcSocketEvents) : CcSocket {
    val sent = ArrayList<JSONObject>()
    var closed: Int? = null
    var cancelled = false
    override fun send(text: String): Boolean { sent += JSONObject(text); return true }
    override fun close(code: Int) { closed = code }
    override fun cancel() { cancelled = true }
    fun types(): List<String> = sent.map { it.optString("type") }
    fun last(type: String): JSONObject = sent.last { it.optString("type") == type }
  }

  val opened = ArrayList<Socket>()
  /** URLs refused as unusable (the factory returns null for them). */
  val refuse = HashSet<String>()

  override fun open(url: String, events: CcSocketEvents): CcSocket? {
    if (url in refuse) return null
    return Socket(url, events).also { opened += it }
  }
}

/**
 * A pure [CcEngine] on fakes and a virtual clock, plus a scripted Home Assistant: [connectLive]
 * runs the usual handshake, and [reply]/[event] answer the engine's last request of a type.
 */
internal class CcEngineRig(configure: FakeCcHost.() -> Unit = {}) {
  val host = FakeCcHost().apply(configure)
  val sockets = FakeCcSockets()
  val sched = VirtualScheduler(T0)
  val log = RecordingCcLog()
  val engine = CcEngine(host, sockets, ExecutorCcScheduler(sched), log) { sched.now }
  val ws: FakeCcSockets.Socket get() = sockets.opened.last()
  val state: CcUiState get() = engine.flow.value

  fun run(block: CcEngine.() -> Unit) { engine.post { engine.block() }; sched.runDue() }
  fun advance(ms: Long) = sched.advance(ms)

  fun ha(msg: JSONObject, socket: FakeCcSockets.Socket = ws) { socket.events.onMessage(msg.toString()); sched.runDue() }
  fun raw(text: String) { ws.events.onMessage(text); sched.runDue() }

  fun reply(type: String, result: Any? = null, ok: Boolean = true, error: String? = null, socket: FakeCcSockets.Socket = ws) =
      ha(JSONObject().put("id", socket.last(type).getInt("id")).put("type", "result").put("success", ok)
          .put("result", result ?: JSONObject.NULL).also { r -> error?.let { r.put("error", JSONObject().put("code", "x").put("message", it)) } }, socket)

  fun event(type: String, data: JSONObject) = ha(JSONObject().put("id", ws.last(type).getInt("id")).put("type", "event").put("event", data))

  fun fail() { ws.events.onFailure(java.io.IOException("reset")); sched.runDue() }
  fun closed(code: Int) { ws.events.onClosed(code); sched.runDue() }

  fun authenticate() {
    ha(JSONObject().put("type", "auth_required"))
    ha(JSONObject().put("type", "auth_ok"))
  }

  /** Auth, the registry, HA's [planInHa], then [entities] as the first `subscribe_entities` event. */
  fun handshake(planInHa: CcPlan? = PLAN, registry: Boolean = true) {
    authenticate()
    if (registry) {
      reply("config/floor_registry/list", FLOORS)
      reply("config/area_registry/list", AREAS)
      reply("config/device_registry/list", JSONArray())
      reply("config/entity_registry/list", REGISTRY)
      reply("config/entity_registry/get_entries", CLASSES)
    } else listOf("floor", "area", "device", "entity").forEach { reply("config/${it}_registry/list", ok = false, error = "Unauthorized") }
    reply("frontend/get_user_data", JSONObject().put("value", planInHa?.let(CcPlanJson::encode) ?: JSONObject.NULL))
  }

  fun connectLive(planInHa: CcPlan? = PLAN, entities: JSONObject = ENTITIES) {
    engine.setVisible(true); sched.runDue()
    handshake(planInHa)
    reply("subscribe_entities")
    event("subscribe_entities", entities)
    advance(CcEngine.FLUSH_MS)
  }

  companion object {
    const val T0 = 1_700_000_000_000L
    val FLOORS: JSONArray = JSONArray().put(JSONObject().put("floor_id", "ground").put("name", "Ground"))
    val AREAS: JSONArray = JSONArray().put(JSONObject().put("area_id", "kitchen").put("name", "Kitchen").put("floor_id", "ground"))
    val REGISTRY: JSONArray = JSONArray(listOf("light.kitchen", "lock.front", "binary_sensor.leak", "camera.porch", "valve.main")
        .map { JSONObject().put("entity_id", it).put("area_id", "kitchen") })
    val CLASSES: JSONObject = JSONObject().put("binary_sensor.leak", JSONObject().put("original_device_class", "moisture"))
    val ENTITIES: JSONObject = JSONObject().put("a", JSONObject()
        .put("light.kitchen", state("on", "Kitchen light")).put("lock.front", state("locked", "Front lock"))
        .put("binary_sensor.leak", state("off", "Leak", "moisture")).put("camera.porch", state("idle", "Porch"))
        .put("valve.main", state("open", "Water main")))
    val PLAN = CcPlan(listOf(CcFloor("ground", "Ground", listOf(CcRoom("kitchen", "Kitchen", CcRect(0, 0, 4, 4), false,
        listOf(CcPlaced("light.kitchen", 0.5f, 0.5f)))))), 1_000L)

    fun state(s: String, name: String, cls: String? = null): JSONObject = JSONObject().put("s", s)
        .put("a", JSONObject().put("friendly_name", name).also { a -> cls?.let { a.put("device_class", it) } })
        .put("lc", (T0 - 60_000L) / 1000.0)

    fun diff(id: String, s: String): JSONObject = JSONObject().put("c", JSONObject().put(id, JSONObject().put("+", JSONObject().put("s", s))))
  }
}
