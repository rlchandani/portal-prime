/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A photo a phone just shared, waiting for (or having) its one badged turn. */
internal data class FreshPhoto(val id: String, val path: String, val from: String, val at: Long)

/**
 * The "fresh photo" queue shared by every photo surface (slideshow and Home photo widget). Each
 * new photo gets exactly one badged turn on whichever surface shows it first, in arrival order.
 */
internal data class FreshPhotoQueue(
    val pending: List<FreshPhoto> = emptyList(),
    val showing: FreshPhoto? = null,
    val showingUntil: Long = 0,
) {
  fun add(photo: FreshPhoto): FreshPhotoQueue =
      if (pending.any { it.path == photo.path } || showing?.path == photo.path) this
      else copy(pending = (pending + photo).takeLast(MAX_PENDING))

  /** The photo whose turn is still running, if any. */
  fun current(now: Long): FreshPhoto? = showing?.takeIf { now < showingUntil }

  /**
   * A surface starts a new turn: the next fresh photo, or null for an ordinary library photo.
   * During quiet hours new photos wait (and nothing is used up).
   */
  fun takeTurn(now: Long, quiet: Boolean, turnMs: Long = TURN_MS): Pair<FreshPhoto?, FreshPhotoQueue> {
    if (quiet) return null to copy(showing = null, showingUntil = 0)
    val next = pending.firstOrNull() ?: return null to copy(showing = null, showingUntil = 0)
    return next to copy(pending = pending.drop(1), showing = next, showingUntil = now + turnMs)
  }

  /** Home joins a turn already running on the slideshow, otherwise starts the next one. */
  fun forHome(now: Long, quiet: Boolean): Pair<FreshPhoto?, FreshPhotoQueue> {
    if (quiet) return null to this
    current(now)?.let { return it to this }
    return if (pending.isEmpty()) null to this else takeTurn(now, quiet)
  }

  fun toJson(): String = JSONObject()
      .put("pending", JSONArray(pending.map(::photoJson)))
      .put("showing", showing?.let(::photoJson) ?: JSONObject.NULL)
      .put("until", showingUntil).toString()

  companion object {
    const val TURN_MS = 30_000L
    const val MAX_PENDING = 30

    private fun photoJson(p: FreshPhoto) = JSONObject().put("id", p.id).put("path", p.path).put("from", p.from).put("at", p.at)
    private fun photo(o: JSONObject) = FreshPhoto(o.getString("id"), o.getString("path"), o.getString("from"), o.getLong("at"))

    fun fromJson(text: String?): FreshPhotoQueue = runCatching {
      val root = JSONObject(text ?: return FreshPhotoQueue())
      val pending = root.getJSONArray("pending").let { a -> (0 until a.length()).map { photo(a.getJSONObject(it)) } }
      FreshPhotoQueue(pending, root.optJSONObject("showing")?.let(::photo), root.optLong("until"))
    }.getOrDefault(FreshPhotoQueue())
  }
}

/** "New from Rohit's Pixel · just now", in the amber badge the "years ago today" memory uses. */
internal object FreshPhotoCaption {
  private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
  private val day = DateTimeFormatter.ofPattern("MMM d", Locale.US)

  fun label(from: String, at: Long, now: Long, zone: ZoneId): String = "New from $from · ${relative(at, now, zone)}"

  fun relative(at: Long, now: Long, zone: ZoneId): String {
    val age = now - at
    if (age < 90_000) return "just now"
    if (age < 60 * 60_000) return "${(age + 30_000) / 60_000} min ago"
    val then = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then.toLocalDate(), today)
    return when (days) {
      0L -> "at ${then.format(clock)}"
      1L -> "${if (then.hour >= 19) "last night" else "yesterday"} ${then.format(clock)}"
      else -> "on ${then.format(day)}"
    }
  }
}

/** Where the slideshow shows a fresh photo: where it already is, or spliced in right after now. */
internal object FreshPhotoSplice {
  fun place(paths: List<String>, current: Int, fresh: String): Pair<Int, Boolean> {
    val existing = paths.indexOf(fresh)
    if (existing >= 0) return existing to false
    return (current + 1).coerceIn(0, paths.size) to true
  }
}
