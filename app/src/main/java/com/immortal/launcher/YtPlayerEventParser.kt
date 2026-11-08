/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * Pure parser for messages the IFrame host page posts through the `@JavascriptInterface` bridge.
 * Messages are untrusted (any frame can reach a JavaScript interface), so only small, versioned,
 * well-typed messages become events; everything else is dropped.
 *
 * Wire format (v1): `{"v":1,"e":"ready"|"state"|"error"|"progress"|"autoplayBlocked"|"apiError",
 * "s":stateCode,"c":errorCode,"t":seconds,"d":seconds,"id":videoId,"ti":title,"au":author}`.
 */
object YtPlayerEventParser {
  const val MAX_MESSAGE_CHARS = 512
  private const val MAX_TEXT_CHARS = 120
  private const val VERSION = 1
  private const val MAX_MEDIA_SECONDS = 24.0 * 60 * 60

  /** One event, or null for anything malformed. [elapsedMs] stamps progress for extrapolation. */
  fun parse(raw: String?, elapsedMs: Long): YtPlayerEvent? {
    if (raw.isNullOrBlank() || raw.length > MAX_MESSAGE_CHARS) return null
    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    if ((obj.opt("v") as? Number)?.toInt() != VERSION) return null
    return when (obj.opt("e")) {
      "ready" -> YtPlayerEvent.Ready
      "state" -> int(obj, "s")?.let(YtPlayerStatus::fromWire)
          ?.takeIf { it != YtPlayerStatus.NOT_READY }?.let { YtPlayerEvent.StateChanged(it) }
      "error" -> int(obj, "c")?.let { YtPlayerEvent.Error(it) }
      "progress" -> progress(obj, elapsedMs)
      "autoplayBlocked" -> YtPlayerEvent.AutoplayBlocked
      "apiError" -> YtPlayerEvent.ApiUnavailable
      else -> null
    }
  }

  private fun progress(obj: JSONObject, elapsedMs: Long): YtPlayerEvent? {
    val position = seconds(obj.opt("t")) ?: return null
    val duration = seconds(obj.opt("d")) ?: return null
    val positionMs = (position * 1_000).toLong()
    val durationMs = (duration * 1_000).toLong()
    val clamped = if (durationMs > 0) positionMs.coerceAtMost(durationMs) else positionMs
    val id = (obj.opt("id") as? String)?.takeIf(YtTrack::isValidVideoId)
    return YtPlayerEvent.Progress(clamped, durationMs, id, elapsedMs, text(obj, "ti"), text(obj, "au"))
  }

  /** Optional short display text from `getVideoData()`; control characters dropped. */
  private fun text(obj: JSONObject, key: String): String? =
      (obj.opt(key) as? String)?.filterNot { it.isISOControl() }?.trim()?.take(MAX_TEXT_CHARS)?.ifBlank { null }

  private fun int(obj: JSONObject, key: String): Int? =
      (obj.opt(key) as? Number)?.toDouble()?.takeIf { it % 1.0 == 0.0 && it in -1e6..1e6 }?.toInt()

  private fun seconds(value: Any?): Double? =
      (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 && it <= MAX_MEDIA_SECONDS }
}
