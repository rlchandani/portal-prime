/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** Sequential legacy macros, with truthful outcomes and cooperative cancellation. */
internal object RemotePresetExecution {
  fun run(steps: JSONArray, cancelled: () -> Boolean, execute: (JSONObject) -> JSONObject,
      sleep: (Long) -> Unit = { Thread.sleep(it) }): JSONArray {
    val results = JSONArray()
    if (steps.length() == 0) return results.put(result(false, "no_steps"))
    for (index in 0 until steps.length()) {
      val step = steps.optJSONObject(index)
      val type = (step?.opt("type") as? String)?.takeIf {
        it in setOf("launch", "key", "text", "config", "wait")
      } ?: "unknown"
      val outcome = try {
        when {
          cancelled() -> result(false, "cancelled")
          step == null -> result(false, "unsupported_step")
          type == "wait" -> {
            var remaining = RemoteRoutes.clampWaitMs(step.optLong("ms", 300))
            while (remaining > 0 && !cancelled()) {
              val chunk = minOf(remaining, 100L)
              sleep(chunk)
              remaining -= chunk
            }
            val stopped = cancelled()
            result(!stopped, if (stopped) "cancelled" else "waited")
          }
          type in setOf("launch", "key", "text", "config") -> execute(step)
          else -> result(false, "unsupported_step")
        }
      } catch (_: PinnedCommandPending) {
        result(true, "completion_unconfirmed").put("backendMayContinue", true)
          .put("checkStateBeforeRetry", true)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        result(false, "interrupted")
      } catch (_: Exception) {
        // A backend exception may contain saved text or credentials; store only a stable code.
        result(false, "step_failed")
      }
      if (outcome.optString("result") == "completion_unconfirmed" || outcome.optBoolean("checkStateBeforeRetry"))
        outcome.put("backendMayContinue", true)
      results.put(outcome.put("step", index).put("type", type))
      if (outcome.opt("ok") != true || outcome.optBoolean("backendMayContinue")) break
    }
    return results
  }
  private fun result(ok: Boolean, code: String) = JSONObject().put("ok", ok).put("result", code)
}
