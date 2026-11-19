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
 * (`POST /action {"action":"whos_there_debug", …}`), like `cc_debug`. Never reachable from the Portal
 * UI. `ignoreQuietHours` is in memory only (a restart clears it); the test person and `enabled`
 * persist until cleared.
 *
 * - `ignoreQuietHours`: lets a glance run inside quiet hours (night testing). Calls, the
 *   privacy switch, foreground and screen-on rules still apply.
 * - `glance`: requests one glance now, as if someone tapped the indicator.
 * - `benchmark`: runs both models on a synthetic, non-person input and reports timings.
 * - `seedTestPerson` / `clearTestPerson`: adds or removes an operator test person whose template
 *   is a synthetic random vector (no face), so the glance pipeline can be exercised at night
 *   with nobody in front of the camera. It can never match a real face.
 * - `enabled`: switches recognition on/off on this Portal (operator testing only).
 * - `simulate`: `"personal"`, `"near"`, `"family"`, `"confirm"` or `"shared"` puts the Home in that
 *   view for the operator test person(s), without the camera, to check layout and frame times.
 */
internal object WhosThereDebug {
  @Volatile var ignoreQuietHours: Boolean = false
    private set

  const val TEST_ID = "wtoperatortest"

  fun apply(context: android.content.Context, body: JSONObject): JSONObject {
    if (body.has("ignoreQuietHours")) ignoreQuietHours = body.optBoolean("ignoreQuietHours")
    if (body.has("enabled")) WhosThereStore.updateSettings(context) { it.copy(enabled = body.optBoolean("enabled")) }
    if (body.optBoolean("seedTestPerson")) {
      val random = java.util.Random(7)
      val vector = Embedding.of(FloatArray(WhosThereLimits.EMBEDDING_SIZE) { random.nextGaussian().toFloat() })!!
      WhosThereStore.updateHousehold(context, { h ->
        h.upsert(WhosTherePerson(TEST_ID, "Test", WhosThereRole.ADULT, 5, System.currentTimeMillis()))
            .withTemplates(FaceTemplates(TEST_ID, listOf(vector)))
      })
    }
    if (body.optBoolean("clearTestPerson")) WhosThereStore.updateHousehold(context, { it.without(TEST_ID) })
    body.optString("simulate").takeIf { it.isNotEmpty() }?.let { WhosThere.simulate(it) }
    val out = JSONObject().put("ignoreQuietHours", ignoreQuietHours)
    if (body.optBoolean("glance")) out.put("glance", WhosThere.requestGlance(GlanceTrigger.MANUAL, operator = true))
    if (body.optBoolean("benchmark")) out.put("benchmark", WhosThere.benchmark())
    return out.put("status", WhosThere.debugStatus())
  }
}
