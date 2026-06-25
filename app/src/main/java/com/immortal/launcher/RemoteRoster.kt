/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Server-side backup of the phone's **device roster** (the `{name, base, token}` list the remote
 * keeps in `localStorage`). Each paired Portal stores the latest roster the phone pushed to it, so
 * a fresh browser — storage wiped by Safari's 7-day eviction, a different origin, a changed
 * DHCP IP — can **pair one Portal and get the whole fleet back** instead of re-pairing every device.
 *
 * The phone is the source of truth; the Portal is a dumb store. Retrieval is auth-gated exactly like
 * input ([RemoteRoutes.roster]) — the on-screen PIN that mints a session is the same gate that
 * releases the roster, so only someone who paired in person can pull it. A Portal still never mints
 * or shares another Portal's token itself; the phone is the only courier, carrying its own tokens.
 *
 * Stored blobs are run through [sanitize] on the way in and out so a malformed or oversized upload
 * can't wedge the store or balloon SharedPreferences.
 */
object RemoteRoster {
  private const val PREFS = "remote_roster"
  private const val KEY_ROSTER = "roster" // JSON array of {name, base, token}

  internal const val MAX_ENTRIES = 64
  internal const val MAX_FIELD = 512

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** The stored roster (sanitized), or an empty array if none has been pushed yet. */
  fun load(c: Context): JSONArray = sanitize(prefs(c).getString(KEY_ROSTER, null))

  /** Replace the stored roster with [blob] (a JSON array string), sanitizing first. */
  fun save(c: Context, blob: String) {
    prefs(c).edit().putString(KEY_ROSTER, sanitize(blob).toString()).apply()
  }

  fun clear(c: Context) {
    prefs(c).edit().remove(KEY_ROSTER).apply()
  }

  // --- pure helper (unit-tested) ----------------------------------------------

  /**
   * Parse [raw] into a clean roster array: keep only objects with a non-blank `base` **and** `token`,
   * dedupe by `base` (first wins), default/clamp `name`, clamp field lengths, and cap the count.
   * Anything that isn't a JSON array — or any junk entry — is dropped rather than stored.
   */
  internal fun sanitize(raw: String?): JSONArray {
    val out = JSONArray()
    val arr = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return out
    val seen = HashSet<String>()
    for (i in 0 until arr.length()) {
      if (out.length() >= MAX_ENTRIES) break
      val o = arr.optJSONObject(i) ?: continue
      val base = o.optString("base").trim().take(MAX_FIELD)
      val token = o.optString("token").trim().take(MAX_FIELD)
      if (base.isEmpty() || token.isEmpty()) continue
      if (!seen.add(base)) continue
      val name = o.optString("name").trim().ifEmpty { "Portal" }.take(48)
      out.put(JSONObject().put("name", name).put("base", base).put("token", token))
    }
    return out
  }
}
