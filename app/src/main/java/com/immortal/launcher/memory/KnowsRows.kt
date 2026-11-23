/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.Kind
import com.immortal.launcher.memory.MemoryFact.State
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What the "What Jarvis knows" screen shows for a profile (plan §4.3, mock screen 2): one row per learned item with
 * its provenance ("Learned from 2 conversations"), when it was last heard, a 1–4 confidence meter, and whether it is
 * still being learned (dashed, with a tick to keep it). Forgotten keys are never shown. Pure.
 */
internal object KnowsRows {
  enum class Category(val title: String) { EVERYTHING("Everything"), TEAMS("Teams"), MARKETS("Markets"),
    NEWS("News"), MUSIC("Music"), PLACES("Places") }

  data class Row(
      val id: String,
      val title: String,
      /** A short badge for the icon box ("GSW", "NVDA"), or null for a glyph. */
      val badge: String?,
      val category: Category,
      val bars: Int,
      val tentative: Boolean,
      /** First learned today: gets the "new" tag. */
      val fresh: Boolean,
      val detail: String,
  )

  fun rows(profile: MemoryProfile, nowMs: Long, zone: ZoneId): List<Row> = profile.facts
      .filter { it.state != State.SUPPRESSED && it.state != State.TOMBSTONE && it.label.isNotBlank() }
      .sortedWith(compareByDescending<MemoryFact> { MemoryConfidence.usable(it) }.thenByDescending { it.confidence }
          .thenBy { it.label })
      .map { fact ->
        val tentative = fact.state == State.TENTATIVE
        val fresh = sameDay(fact.firstSeenMs, nowMs, zone)
        Row(
            id = fact.id, title = fact.label, badge = badge(fact), category = categoryOf(fact.kind),
            bars = MemoryConfidence.bars(fact), tentative = tentative, fresh = fresh && fact.state != State.CONFIRMED,
            detail = "${provenance(fact, nowMs, zone)} · ${seen(fact, nowMs, zone, tentative)}",
        )
      }

  fun count(rows: List<Row>, category: Category): Int =
      if (category == Category.EVERYTHING) rows.size else rows.count { it.category == category }

  fun categoryOf(kind: Kind): Category = when (kind) {
    Kind.TEAM -> Category.TEAMS
    Kind.TICKER, Kind.CRYPTO -> Category.MARKETS
    Kind.MUSIC_TASTE -> Category.MUSIC
    Kind.PLACE -> Category.PLACES
    // Slice 4's preferences have no tab of their own in the mock: they show under Everything only.
    Kind.PREFERENCE -> Category.EVERYTHING
    else -> Category.NEWS
  }

  private fun badge(fact: MemoryFact): String? = when (fact.kind) {
    Kind.TICKER -> fact.id.substringAfter(':').takeUnless { it.startsWith("^") }?.take(BADGE_MAX)
    Kind.TEAM -> fact.label.split(' ').filter { it.isNotEmpty() }.let { words ->
      if (words.size == 1) words.single().take(3).uppercase() else words.joinToString("") { it.take(1) }.take(3)
    }
    else -> null
  }

  private fun provenance(fact: MemoryFact, nowMs: Long, zone: ZoneId): String {
    if (fact.state == State.CONFIRMED) return "You confirmed it"
    val conversations = MemoryConfidence.conversations(fact)
    val onlyTools = fact.evidence.isNotEmpty() && fact.evidence.all { it.source == Evidence.Source.TOOL_CALL }
    return when {
      fact.kind == Kind.MUSIC_TASTE -> "From what you asked Jarvis to play"
      conversations <= 1 && sameDay(fact.lastSeenMs, nowMs, zone) -> "Learned from today's chat"
      onlyTools -> "From $conversations search${if (conversations == 1) "" else "es"}"
      conversations == 1 -> "Learned from 1 conversation"
      else -> "Learned from $conversations conversations"
    }
  }

  private fun seen(fact: MemoryFact, nowMs: Long, zone: ZoneId, tentative: Boolean): String {
    if (tentative) return if (sameDay(fact.firstSeenMs, nowMs, zone)) "new today · still learning" else "still learning"
    val last = Instant.ofEpochMilli(fact.lastSeenMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(last, today)
    return when {
      days <= 0 -> "last mentioned today"
      days == 1L -> "last mentioned yesterday"
      days < WEEK -> "last mentioned ${last.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)}"
      else -> "last mentioned $days days ago"
    }
  }

  private fun sameDay(a: Long, b: Long, zone: ZoneId) =
      Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

  private const val BADGE_MAX = 5
  private const val WEEK = 7
}
