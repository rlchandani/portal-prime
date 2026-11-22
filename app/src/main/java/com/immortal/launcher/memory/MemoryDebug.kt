/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import android.content.Context
import org.json.JSONObject

/**
 * The hidden operator switch for testing Jarvis personalization before the owner turns it on, reachable only through
 * the authenticated Fleet agent (`POST /action {"action":"jarvis_memory_debug", …}`), like `whos_there_debug`.
 * Never in the Portal UI and never synced.
 *
 * - `forceOn`: learning and the note run as if "Jarvis learns from conversations" were on (persists until cleared).
 * - `forgetAll`: wipes every profile and usage count on this Portal.
 * The answer is counts and states only: never labels, ids or anything said.
 */
internal object MemoryDebug {
  private const val PREFS = "hearthpane_memory_debug"
  private const val KEY_FORCE_ON = "force_on"

  fun forceOn(context: Context): Boolean = prefs(context).getBoolean(KEY_FORCE_ON, false)

  fun apply(context: Context, body: JSONObject): JSONObject {
    if (body.has("forceOn")) prefs(context).edit().putBoolean(KEY_FORCE_ON, body.optBoolean("forceOn")).apply()
    val out = JSONObject()
    if (body.optBoolean("forgetAll")) out.put("forgotAll", JarvisMemory.forgetEverything(context))
    if (body.has("forceOn")) JarvisMemory.warm(context)
    return out.put("status", status(context))
  }

  private fun status(context: Context): JSONObject {
    val access = JarvisMemory.accessNow(context)
    val runtime = JarvisMemory.runtime(context)
    val scopes = (MemoryProfileStore.get(context).scopes() + MemoryScope.Household).distinct()
    val byState = scopes.flatMap { runtime.profile(it).facts }.groupingBy { it.state.name.lowercase() }.eachCount()
    return JSONObject()
        .put("enabled", JarvisMemory.enabled(context))
        .put("forceOn", forceOn(context))
        .put("access", when (access) {
          is MemoryAccess.On -> if (access.scope is MemoryScope.Household) "household" else "person"
          is MemoryAccess.Off -> "off:${access.reason.name.lowercase()}"
        })
        .put("scopes", scopes.size)
        .put("facts", JSONObject(byState))
        .put("usageBuckets", runtime.usage().buckets.size)
  }

  private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
