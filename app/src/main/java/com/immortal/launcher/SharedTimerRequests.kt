/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * `POST /remote/timers` bodies from the phone, validated before anything changes:
 * - `{"action":"start","durationMs":600000,"name":"Pasta","from":"Rohit’s Pixel"}` (name/from optional)
 * - `{"action":"pause|resume|add|cancel|stop","id":"<timer id>"}` (`add` is +1 min)
 */
internal sealed interface SharedTimerRequest {
  data class Start(val name: String, val durationMs: Long, val from: String) : SharedTimerRequest
  data class Act(val action: SharedTimerAction, val id: String) : SharedTimerRequest
  data class Invalid(val code: String) : SharedTimerRequest
}

internal enum class SharedTimerAction(val wire: String) {
  PAUSE("pause"), RESUME("resume"), ADD("add"), CANCEL("cancel"), STOP("stop");

  companion object {
    fun fromWire(code: String?): SharedTimerAction? = entries.firstOrNull { it.wire == code }
  }
}

internal object SharedTimerRequests {
  const val DEFAULT_FROM = "Phone"

  fun parse(body: JSONObject): SharedTimerRequest {
    val action = body.opt("action") as? String ?: return SharedTimerRequest.Invalid("invalid_action")
    if (action == "start") return start(body)
    val act = SharedTimerAction.fromWire(action) ?: return SharedTimerRequest.Invalid("invalid_action")
    val id = (body.opt("id") as? String)?.takeIf(SharedTimerWire::isId) ?: return SharedTimerRequest.Invalid("invalid_id")
    return SharedTimerRequest.Act(act, id)
  }

  private fun start(body: JSONObject): SharedTimerRequest {
    val raw = body.opt("durationMs") as? Number ?: return SharedTimerRequest.Invalid("invalid_duration")
    val duration = raw.toLong()
    if (raw.toDouble() != duration.toDouble() ||
        duration !in SharedTimer.MIN_DURATION_MS..SharedTimer.MAX_DURATION_MS) return SharedTimerRequest.Invalid("invalid_duration")
    val name = when (val n = body.opt("name")) {
      null, JSONObject.NULL -> SharedTimer.DEFAULT_NAME
      is String -> if (n.isBlank()) SharedTimer.DEFAULT_NAME else DinnerWire.cleanName(n) ?: return SharedTimerRequest.Invalid("invalid_name")
      else -> return SharedTimerRequest.Invalid("invalid_name")
    }
    val from = DinnerWire.cleanName(body.opt("from") as? String) ?: DEFAULT_FROM
    return SharedTimerRequest.Start(name, duration, from)
  }
}
