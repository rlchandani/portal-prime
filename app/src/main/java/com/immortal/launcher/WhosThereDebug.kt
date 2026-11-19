/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * Operator-only test switches, reachable only through the authenticated Fleet agent
 * (`POST /action {"action":"whos_there_debug", …}`), like `cc_debug`. In memory only: a restart
 * clears them, so nothing can be left on by accident. Never reachable from the Portal UI.
 *
 * - `ignoreQuietHours`: lets a glance run inside quiet hours (night testing). Calls, the
 *   privacy switch, foreground and screen-on rules still apply.
 * - `glance`: requests one glance now, as if someone tapped the indicator.
 * - `benchmark`: runs both models on a synthetic, non-person input and reports timings.
 */
internal object WhosThereDebug {
  @Volatile var ignoreQuietHours: Boolean = false
    private set

  fun apply(body: JSONObject): JSONObject {
    if (body.has("ignoreQuietHours")) ignoreQuietHours = body.optBoolean("ignoreQuietHours")
    val out = JSONObject().put("ignoreQuietHours", ignoreQuietHours)
    if (body.optBoolean("glance")) out.put("glance", WhosThere.requestGlance(GlanceTrigger.MANUAL, operator = true))
    if (body.optBoolean("benchmark")) out.put("benchmark", WhosThere.benchmark())
    return out.put("status", WhosThere.debugStatus())
  }
}
