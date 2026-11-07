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
import java.util.Locale

/** A note (or a link held as a note) shared from a phone. [from] is the phone's pairing name. */
internal data class HomeNote(
    val id: String,
    val from: String,
    val at: Long,
    val text: String? = null,
    val url: String? = null,
    val title: String? = null,
)

/** A note that left Home: dismissed here or on the other Portal, cleared overnight, or taken back. */
internal data class GoneNote(val note: HomeNote, val why: String, val at: Long)

/** Immutable Home-note state. Every operation returns a new value; nothing mutates in place. */
internal data class HomeNotes(
    val active: List<HomeNote> = emptyList(),
    val gone: List<GoneNote> = emptyList(),
    /** Link shares that opened (or were canceled) rather than becoming notes. */
    val links: Map<String, String> = emptyMap(),
) {
  val newest: HomeNote? get() = active.maxByOrNull { it.at }

  /** Idempotent: a re-sent note keeps one copy, and a note already gone stays gone. */
  fun add(note: HomeNote): HomeNotes {
    if (active.any { it.id == note.id } || gone.any { it.note.id == note.id }) return this
    return copy(active = (active + note).sortedBy { it.at }.takeLast(MAX_ACTIVE))
  }

  fun dismiss(id: String, now: Long) = leave(id, DISMISSED, now)
  fun retract(id: String, now: Long) = leave(id, RETRACTED, now)

  private fun leave(id: String, why: String, now: Long): HomeNotes {
    val note = active.firstOrNull { it.id == id } ?: return this
    return copy(active = active.filterNot { it.id == id },
        gone = (listOf(GoneNote(note, why, now)) + gone).take(MAX_GONE))
  }

  /** Undo after Dismiss, and "Put back on Home" from Tools. */
  fun undo(id: String): HomeNotes {
    val back = gone.firstOrNull { it.note.id == id } ?: return this
    return copy(active = (active + back.note).sortedBy { it.at }.takeLast(MAX_ACTIVE), gone = gone.filterNot { it.note.id == id })
  }

  /** Clears every note whose 4:00 AM has passed; the clear time is that 4:00 AM, not "now". */
  fun clearOvernight(now: Long, zone: ZoneId): HomeNotes {
    val due = active.filter { now >= HomeNoteTime.overnightAfter(it.at, zone) }
    if (due.isEmpty()) return this
    return due.fold(this) { acc, note -> acc.leave(note.id, CLEARED, HomeNoteTime.overnightAfter(note.at, zone)) }
  }

  fun withLink(id: String, state: String): HomeNotes = copy(links = (links + (id to state)).entries.toList().takeLast(MAX_GONE).associate { it.key to it.value })

  fun stateOf(id: String): String? = when {
    active.any { it.id == id } -> SHOWN
    else -> gone.firstOrNull { it.note.id == id }?.why ?: links[id]
  }

  /** Recent share ids and their state, newest first, for the phone's `GET /remote/share`. */
  fun recent(): List<Pair<String, String>> =
      active.map { it.id to SHOWN } + gone.map { it.note.id to it.why } + links.map { it.key to it.value }

  fun toJson(): String = JSONObject()
      .put("active", JSONArray(active.map(::noteJson)))
      .put("gone", JSONArray(gone.map { JSONObject().put("note", noteJson(it.note)).put("why", it.why).put("at", it.at) }))
      .put("links", JSONObject(links as Map<*, *>)).toString()

  companion object {
    const val MAX_ACTIVE = 6
    const val MAX_GONE = 12
    const val SHOWN = "shown"
    const val DISMISSED = "dismissed"
    const val CLEARED = "cleared"
    const val RETRACTED = "retracted"

    private fun noteJson(n: HomeNote) = JSONObject().put("id", n.id).put("from", n.from).put("at", n.at)
        .put("text", n.text ?: JSONObject.NULL).put("url", n.url ?: JSONObject.NULL).put("title", n.title ?: JSONObject.NULL)

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

    private fun note(o: JSONObject) = HomeNote(o.getString("id"), o.getString("from"), o.getLong("at"),
        o.str("text"), o.str("url"), o.str("title"))

    fun fromJson(text: String?): HomeNotes = runCatching {
      val root = JSONObject(text ?: return HomeNotes())
      val active = root.getJSONArray("active").let { a -> (0 until a.length()).map { note(a.getJSONObject(it)) } }
      val gone = root.getJSONArray("gone").let { a -> (0 until a.length()).map {
        val g = a.getJSONObject(it); GoneNote(note(g.getJSONObject("note")), g.getString("why"), g.getLong("at")) } }
      val links = root.optJSONObject("links")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }.orEmpty()
      HomeNotes(active, gone, links)
    }.getOrDefault(HomeNotes())
  }
}

internal object HomeNoteTime {
  private const val CLEAR_HOUR = 4
  private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

  /** The next 4:00 AM strictly after [at]: notes are about today. */
  fun overnightAfter(at: Long, zone: ZoneId): Long {
    val local = Instant.ofEpochMilli(at).atZone(zone)
    val sameDay = local.toLocalDate().atTime(CLEAR_HOUR, 0).atZone(zone)
    val next = if (sameDay.toInstant().toEpochMilli() > at) sameDay else sameDay.plusDays(1)
    return next.toInstant().toEpochMilli()
  }

  fun clock(at: Long, zone: ZoneId): String = Instant.ofEpochMilli(at).atZone(zone).format(clock)

  fun byline(note: HomeNote, zone: ZoneId): String = "From ${note.from} · ${clock(note.at, zone)}"
}

internal enum class HomeNotePlacement { SLOT, STRIP, HIDDEN }

/** First free slot on page 1; if there isn't one, the greeting-row strip. Customize hides notes. */
internal fun homeNotePlacement(hasNote: Boolean, freeSlotOnFirstPage: Boolean, customizing: Boolean): HomeNotePlacement = when {
  !hasNote || customizing -> HomeNotePlacement.HIDDEN
  freeSlotOnFirstPage -> HomeNotePlacement.SLOT
  else -> HomeNotePlacement.STRIP
}
