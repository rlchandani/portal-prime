/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * Per-section versions for the phone's Control Center poll (pure, unit tested). The derived sections
 * (alerts, security, cameras, scenes, rooms, tesla) are most of a changed answer (~29 KB) and most polls
 * change one of them or none. Each section's version is a 64-bit FNV-1a hash of its JSON, so it needs no
 * state, is the same for every phone and survives a Portal restart.
 *
 * Compatibility, both ways:
 * - A phone that knows versions always sends `sv` (`{}` when it holds nothing). It gets `sv` back and only
 *   the sections whose version differs from the one it sent; a section it already holds is left out.
 * - A phone that doesn't send `sv` (Toolkit 1.8.17 and older) gets every section, exactly as before:
 *   it replaces each section with what it reads, so leaving one out would empty it.
 * - An older Portal ignores `sv` and sends every section without `sv`; the phone then takes them all.
 */
internal object CcRemoteSections {
  const val ALERTS = "alerts"
  const val SECURITY = "security"
  const val CAMERAS = "cameras"
  const val SCENES = "scenes"
  const val ROOMS = "rooms"
  const val TESLA = "tesla"
  const val VERSIONS = "sv"
  val NAMES = listOf(ALERTS, SECURITY, CAMERAS, SCENES, ROOMS, TESLA)

  private const val MAX_VERSION_CHARS = 32

  /** The versions the phone holds, or null for a phone that doesn't know versions (send everything). */
  fun held(body: JSONObject): Map<String, String>? {
    val sv = body.optJSONObject(VERSIONS) ?: return null
    return NAMES.mapNotNull { name ->
      sv.optString(name, "").takeIf { it.isNotEmpty() && it.length <= MAX_VERSION_CHARS }?.let { name to it }
    }.toMap()
  }

  /** A section's version: FNV-1a 64 over its JSON text; a missing section (no car) has a version too. */
  fun version(section: Any?): String {
    val text = section?.toString() ?: "null"
    var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    for (c in text) {
      h = (h xor c.code.toLong()) * 0x100000001b3L
    }
    return java.lang.Long.toHexString(h)
  }

  /**
   * Puts [sections] (name to JSONArray, JSONObject or null) on [out]. With [held] null every non-null
   * section goes out as before; otherwise only changed ones (a section gone to null is sent as JSON null)
   * plus every version under `sv`.
   */
  fun put(out: JSONObject, sections: Map<String, Any?>, held: Map<String, String>?): JSONObject {
    if (held == null) {
      sections.forEach { (name, value) -> out.putOpt(name, value) }
      return out
    }
    val versions = JSONObject()
    sections.forEach { (name, value) ->
      val v = version(value)
      versions.put(name, v)
      if (held[name] != v) out.put(name, value ?: JSONObject.NULL)
    }
    return out.put(VERSIONS, versions)
  }
}
