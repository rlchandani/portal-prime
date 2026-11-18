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
 * The Control Center's Home Assistant websocket conversation without the socket (pure, unit
 * tested). Auth with the saved token, then every request gets an id and a [CcReq] tag so results
 * route back. Live state uses `subscribe_entities` (only the entities we show); if HA refuses it,
 * `get_states` + `subscribe_events state_changed` filtered here — the doorbell's fallback.
 * The token appears only in the auth message and is never logged or echoed.
 */
internal class CcProtocol(private val token: String) {
  enum class Kind { FLOORS, AREAS, DEVICES, ENTITIES, PLAN_GET, PLAN_SET, SUBSCRIBE, GET_STATES, EVENTS, CALL, PING, ENERGY_PREFS, STATS, UNSUBSCRIBE }

  data class Req(val kind: Kind, val key: String = "")

  sealed interface Out {
    data class Send(val text: String) : Out
    object AuthOk : Out
    object AuthFailed : Out
    /** A compressed `subscribe_entities` event (`a`/`c`/`r`). */
    data class Entities(val event: JSONObject) : Out
    /** Fallback snapshot from `get_states`. */
    data class Snapshot(val entities: List<CcEntity>) : Out
    /** Fallback `state_changed` for a watched entity; [entity] null when it was removed. */
    data class Changed(val entityId: String, val entity: CcEntity?) : Out
    data class Result(val req: Req, val ok: Boolean, val result: Any?, val error: String?) : Out
    object Subscribed : Out
    object Pong : Out
  }

  private var nextId = 1
  private val pending = HashMap<Int, Req>()
  private var entitySub = -1
  private var eventSub = -1
  private var watched: Set<String>? = null
  private var pingId = -1

  /** Epoch ms when the outstanding ping was sent, or 0 when none is waiting. */
  var pingSentAt = 0L
    private set

  fun onMessage(text: String): List<Out> {
    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
    return when (msg.optString("type")) {
      "auth_required" -> listOf(Out.Send(JSONObject().put("type", "auth").put("access_token", token).toString()))
      "auth_ok" -> listOf(Out.AuthOk)
      "auth_invalid" -> listOf(Out.AuthFailed)
      "result" -> result(msg)
      "event" -> event(msg)
      "pong" -> if (msg.optInt("id") == pingId) { pingSentAt = 0L; pingId = -1; listOf(Out.Pong) } else emptyList()
      else -> emptyList()
    }
  }

  /** A new request; the result arrives as [Out.Result] with [req]. */
  fun request(req: Req, payload: JSONObject): Out.Send {
    val id = nextId++
    pending[id] = req
    payload.put("id", id)
    return Out.Send(payload.toString())
  }

  fun registry(): List<Out.Send> = listOf(
      request(Req(Kind.FLOORS), JSONObject().put("type", "config/floor_registry/list")),
      request(Req(Kind.AREAS), JSONObject().put("type", "config/area_registry/list")),
      request(Req(Kind.DEVICES), JSONObject().put("type", "config/device_registry/list")),
      request(Req(Kind.ENTITIES), JSONObject().put("type", "config/entity_registry/list")))

  fun getPlan(): Out.Send = request(Req(Kind.PLAN_GET), JSONObject().put("type", "frontend/get_user_data").put("key", CcPlanJson.HA_KEY))

  fun setPlan(plan: JSONObject): Out.Send =
      request(Req(Kind.PLAN_SET), JSONObject().put("type", "frontend/set_user_data").put("key", CcPlanJson.HA_KEY).put("value", plan))

  /** Live state for [ids] (null = every entity; only used when the registry is unavailable). */
  fun subscribe(ids: Set<String>?): Out.Send {
    watched = ids
    val payload = JSONObject().put("type", "subscribe_entities")
    ids?.let { payload.put("entity_ids", JSONArray(it.sorted())) }
    return request(Req(Kind.SUBSCRIBE), payload).also { entitySub = nextId - 1 }
  }

  /** Swap the live set (plan edited): drop the old subscription, then subscribe to [ids]. */
  fun resubscribe(ids: Set<String>?): List<Out.Send> {
    val old = if (entitySub > 0) entitySub else eventSub
    val drop = old.takeIf { it > 0 }?.let {
      request(Req(Kind.UNSUBSCRIBE), JSONObject().put("type", "unsubscribe_events").put("subscription", it))
    }
    eventSub = -1
    return listOfNotNull(drop, subscribe(ids))
  }

  fun call(call: CcCall, key: String): Out.Send {
    val id = nextId++
    pending[id] = Req(Kind.CALL, key)
    return Out.Send(call.toMessage(id).toString())
  }

  fun ping(now: Long): Out.Send {
    pingSentAt = now
    return request(Req(Kind.PING), JSONObject().put("type", "ping")).also { pingId = nextId - 1; pending.remove(pingId) }
  }

  fun energyPrefs(): Out.Send = request(Req(Kind.ENERGY_PREFS), JSONObject().put("type", "energy/get_prefs"))

  /** Hourly/daily `change` statistics for [ids] between ISO [start] and [end]. */
  fun statistics(key: String, ids: List<String>, startIso: String, endIso: String, period: String): Out.Send =
      request(Req(Kind.STATS, key), JSONObject().put("type", "recorder/statistics_during_period")
          .put("start_time", startIso).put("end_time", endIso).put("statistic_ids", JSONArray(ids))
          .put("period", period).put("types", JSONArray(listOf("change"))))

  private fun result(msg: JSONObject): List<Out> {
    val id = msg.optInt("id", -1)
    val req = pending.remove(id) ?: return emptyList()
    val ok = msg.optBoolean("success", false)
    val error = msg.optJSONObject("error")?.optString("message")
    return when (req.kind) {
      Kind.SUBSCRIBE -> if (ok) listOf(Out.Subscribed) else fallback()
      Kind.EVENTS -> if (ok) listOf(Out.Subscribed) else listOf(Out.Result(req, false, null, error))
      Kind.GET_STATES -> listOf(Out.Snapshot(states(msg.optJSONArray("result"))))
      else -> listOf(Out.Result(req, ok, msg.opt("result"), error))
    }
  }

  private fun fallback(): List<Out> {
    entitySub = -1
    val states = request(Req(Kind.GET_STATES), JSONObject().put("type", "get_states"))
    val events = request(Req(Kind.EVENTS), JSONObject().put("type", "subscribe_events").put("event_type", "state_changed"))
    eventSub = nextId - 1
    return listOf(states, events)
  }

  private fun states(a: JSONArray?): List<CcEntity> {
    if (a == null) return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(CcEntityParser::fromFull) }.filter(::isWatched)
  }

  private fun isWatched(e: CcEntity): Boolean = watched?.contains(e.id) ?: true

  private fun event(msg: JSONObject): List<Out> {
    val id = msg.optInt("id", -1)
    val event = msg.optJSONObject("event") ?: return emptyList()
    return when (id) {
      entitySub -> listOf(Out.Entities(event))
      eventSub -> {
        val data = event.optJSONObject("data") ?: return emptyList()
        val entityId = data.optString("entity_id")
        if (watched?.contains(entityId) == false) return emptyList()
        listOf(Out.Changed(entityId, data.optJSONObject("new_state")?.let(CcEntityParser::fromFull)))
      }
      else -> emptyList()
    }
  }
}
