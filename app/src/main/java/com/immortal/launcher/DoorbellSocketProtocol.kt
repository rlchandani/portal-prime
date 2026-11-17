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
 * The Home Assistant websocket conversation for the doorbell, without the socket (pure, so it is
 * unit tested). After `auth_ok` it subscribes to state changes of just the watched entities
 * (`subscribe_trigger`, platform state). If HA refuses that (a non-admin token), it falls back to
 * `subscribe_events` for `state_changed` and filters by entity id here.
 *
 * The token is only ever placed in the auth message; it is never logged or echoed.
 */
internal class DoorbellSocketProtocol(private val token: String, watched: List<String>) {
  private val entities = watched.toSet()
  private val ordered = watched

  sealed interface Out {
    data class Send(val text: String) : Out
    data class Change(val entityId: String, val old: JSONObject?, val new: JSONObject?) : Out
    object Ready : Out
    object AuthFailed : Out
    object Failed : Out
  }

  fun onMessage(text: String): List<Out> {
    val msg = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
    return when (msg.optString("type")) {
      "auth_required" -> listOf(Out.Send(JSONObject().put("type", "auth").put("access_token", token).toString()))
      "auth_ok" -> listOf(Out.Send(subscribeTrigger()))
      "auth_invalid" -> listOf(Out.AuthFailed)
      "result" -> result(msg)
      "event" -> listOfNotNull(event(msg))
      else -> emptyList()
    }
  }

  private fun result(msg: JSONObject): List<Out> {
    val ok = msg.optBoolean("success", false)
    return when (msg.optInt("id", -1)) {
      TRIGGER_ID -> if (ok) listOf(Out.Ready) else listOf(Out.Send(subscribeEvents()))
      EVENTS_ID -> if (ok) listOf(Out.Ready) else listOf(Out.Failed)
      else -> emptyList()
    }
  }

  private fun event(msg: JSONObject): Out.Change? {
    val event = msg.optJSONObject("event") ?: return null
    return when (msg.optInt("id", -1)) {
      TRIGGER_ID -> event.optJSONObject("variables")?.optJSONObject("trigger")?.let { t ->
        change(t.optString("entity_id"), t.optJSONObject("from_state"), t.optJSONObject("to_state"))
      }
      EVENTS_ID -> event.optJSONObject("data")?.let { d ->
        change(d.optString("entity_id"), d.optJSONObject("old_state"), d.optJSONObject("new_state"))
      }
      else -> null
    }
  }

  private fun change(entityId: String, old: JSONObject?, new: JSONObject?): Out.Change? =
      if (entityId in entities) Out.Change(entityId, old, new) else null

  private fun subscribeTrigger(): String = JSONObject().put("id", TRIGGER_ID).put("type", "subscribe_trigger")
      .put("trigger", JSONObject().put("platform", "state").put("entity_id", JSONArray(ordered))).toString()

  private fun subscribeEvents(): String =
      JSONObject().put("id", EVENTS_ID).put("type", "subscribe_events").put("event_type", "state_changed").toString()

  companion object {
    const val TRIGGER_ID = 1
    const val EVENTS_ID = 2
  }
}

/** Reconnect delays: 2 s, 4 s, 8 s … capped at a minute (a doorbell can't wait long). Pure. */
internal object DoorbellBackoff {
  const val BASE_MS = 2_000L
  const val MAX_MS = 60_000L
  /** A refused token won't fix itself; try again only now and then (or when settings change). */
  const val AUTH_RETRY_MS = 300_000L
  private const val MAX_SHIFT = 16

  fun delayMs(attempt: Int): Long = (BASE_MS shl attempt.coerceIn(0, MAX_SHIFT)).coerceAtMost(MAX_MS)
}

/** Home Assistant URLs for the doorbell, from the saved base address. Pure. */
internal object DoorbellUrls {
  fun websocket(base: String): String? {
    val clean = base.trim().trimEnd('/')
    return when {
      clean.startsWith("https://", ignoreCase = true) -> "wss://" + clean.substring("https://".length) + "/api/websocket"
      clean.startsWith("http://", ignoreCase = true) -> "ws://" + clean.substring("http://".length) + "/api/websocket"
      else -> null
    }
  }

  fun stream(base: String, camera: String): String = "${base.trim().trimEnd('/')}/api/camera_proxy_stream/$camera"

  fun snapshot(base: String, camera: String): String = "${base.trim().trimEnd('/')}/api/camera_proxy/$camera"
}
