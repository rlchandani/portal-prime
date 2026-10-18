/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Storage for the phone-remote's user-defined **presets** — named macros the user builds
 * on the remote page and fires with one tap. A preset is `{id, name, steps[]}`; each step
 * is one action the remote can already perform:
 *
 *  - `{"type":"launch","packageName":"…"}`  — open an app
 *  - `{"type":"key","action":"home|back|power|apps"}` — a nav button
 *  - `{"type":"text","mode":"set|append|backspace|clear","text":"…"}` — edit the focused field
 *  - `{"type":"wait","ms":500}` — pause between steps
 *
 * Persisted as one JSON blob in its own prefs (mirrors [QuickBarConfig]/[RemotePairing]).
 * Execution lives in [RemoteRoutes] (it drives [RemoteApps]/[RemoteInput]); this object is
 * just the store. The step set is intentionally open-ended — a future `{"type":"config",…}`
 * step can push screensaver/calendar settings (the remote×fleet bridge) without changing
 * storage.
 */
object RemotePresets {
  private const val PREFS = "remote_presets"
  private const val KEY = "json"

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Missing storage is empty; unreadable saved storage is unavailable, never an editable default. */
  @Synchronized
  fun listJson(c: Context): JSONArray = read(prefs(c))

  private fun read(store: SharedPreferences): JSONArray {
    try {
      val raw = store.getString(KEY, null) ?: return JSONArray()
      // JSONTokener conflates literal NUL with EOF on the JVM. Reject it before parsing.
      require('\u0000' !in raw)
      val input = JSONTokener(raw)
      val arr = JSONArray(input)
      // nextClean() accepts comments on Android; only JSON whitespace may follow the array.
      var next = input.next()
      while (next != '\u0000') {
        require(next == ' ' || next == '\t' || next == '\r' || next == '\n')
        next = input.next()
      }
      return arr
    } catch (_: Exception) {
      // Parser/type errors can include saved content, so never attach their message or cause.
      throw IllegalStateException("Saved remote presets are unavailable")
    }
  }

  /** Replace the whole preset list (the editor saves the full set at once). */
  @Synchronized
  fun save(c: Context, presets: JSONArray) {
    val store = prefs(c)
    read(store)
    store.edit().putString(KEY, presets.toString()).apply()
  }

  /** Find a preset by id, or null. */
  @Synchronized
  fun find(c: Context, id: String): JSONObject? {
    val arr = listJson(c)
    for (i in 0 until arr.length()) {
      val o = arr.optJSONObject(i) ?: continue
      if (o.optString("id") == id) return o
    }
    return null
  }
}
