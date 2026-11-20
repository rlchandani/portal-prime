/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * Photo-source secrets never leave the Portal over the phone remote. A read shows [MASK] in the
 * secret's slot (so a form that round-trips every field sends the mask back, not a blank), a
 * `has…`/`…Configured` flag, and for the Immich API key a two-character [hint]. On save, a
 * missing, blank or masked secret means "keep the saved one" (subject to the same-endpoint rule in
 * [RemoteCompanionSources]); only a newly typed value replaces it.
 */
internal object SourceSecrets {
  /** The secret fields of a photo-source form. */
  val FIELDS = listOf("immichKey", "smbPass", "davPass")
  private const val BULLET = '•'
  const val MASK = "••••••••"
  private const val HINT_CHARS = 2
  /** Shorter secrets get no hint: two characters of a short password give too much away. */
  private const val HINT_MIN_LENGTH = 16

  /** A placeholder from a masked read (any run of bullets), never a real typed secret. */
  fun isMasked(value: String): Boolean = value.isNotEmpty() && value.all { it == BULLET }

  /** What a read shows in the secret's slot: [MASK] when one is saved, else blank. */
  fun masked(secret: String?): String = if (secret.isNullOrBlank()) "" else MASK

  /** The last two characters of a long API key, or "" (never for passwords). */
  fun hint(secret: String?): String =
      secret?.takeIf { it.length >= HINT_MIN_LENGTH }?.takeLast(HINT_CHARS).orEmpty()

  /**
   * A copy of an incoming form with every blank or masked secret removed, so the save treats it as
   * "not sent" (keep). A non-string secret is left for validation to refuse.
   */
  fun withoutKeptSecrets(input: JSONObject): JSONObject {
    val out = JSONObject(input.toString())
    for (field in FIELDS) {
      val value = out.opt(field) as? String ?: continue
      if (value.isBlank() || isMasked(value)) out.remove(field)
    }
    return out
  }
}
