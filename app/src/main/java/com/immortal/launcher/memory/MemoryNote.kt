/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.Kind
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.pow

/**
 * The few lines Jarvis is told at conversation start (plan §3.4): who it is talking with and what they care about,
 * ranked by `confidence × recency × relevance` and cut to a budget — [BUDGET] characters, [SHARED_BUDGET] when
 * others are near or for the household. Only ACTIVE or CONFIRMED, non-sensitive facts; nothing at all for an
 * [MemoryAccess.Off] moment. Jarvis puts its own heading and "use only when relevant" rule above these lines. Pure.
 */
internal object MemoryNote {
  const val BUDGET = 600
  const val SHARED_BUDGET = 300
  private const val RECENCY_HALF_LIFE_MS = 30 * MemoryFact.DAY_MS
  private const val MORNING_FROM = 5
  private const val MORNING_UNTIL = 11

  /** What the note is built from. [screen] is the Hearthpane screen in front, if any. */
  data class Inputs(
      val access: MemoryAccess,
      val profile: MemoryProfile,
      val nowMs: Long,
      val zone: ZoneId,
      val screen: String? = null,
  )

  /** The note's lines and the extracted Recent topics it offers (marked shown, so each is offered once). */
  data class Note(val lines: List<String>, val offeredTopics: Set<String>)

  /** The note's lines, or empty when there is nothing to say (or nobody to say it about). */
  fun lines(inputs: Inputs): List<String> = build(inputs).lines

  fun build(inputs: Inputs): Note {
    val access = inputs.access as? MemoryAccess.On ?: return EMPTY
    val shared = access.othersNear || access.scope is MemoryScope.Household
    val budget = if (shared) SHARED_BUDGET else BUDGET
    val facts = inputs.profile.facts
        .filter { MemoryConfidence.usable(it) && !it.sensitive && it.label.isNotBlank() }
        .filterNot { shared && it.kind == Kind.PREFERENCE } // personal, so only when they're alone with Jarvis
        .sortedByDescending { score(it, inputs) }
    val threads = if (shared) emptyList() else threads(inputs)
    if (facts.isEmpty() && threads.isEmpty()) return EMPTY
    val recent = when {
      shared -> null
      threads.isNotEmpty() -> threadLine(inputs, threads)
      else -> recentLine(inputs, facts.map { it.id }.toSet())
    }
    // An extracted thread is kept room for first (it is at most RECENT_MAX), then the facts fill what is left;
    // slice 1's stand-in only joins when it fits after the facts.
    if (threads.isNotEmpty() && recent != null) {
      fill(access, facts, recent.first, budget)?.let { return Note(it.first, recent.second) }
    }
    val (plain, chosen) = fill(access, facts, null, budget) ?: (emptyList<String>() to emptyList())
    val withStandIn = recent?.takeIf { threads.isEmpty() }?.let { render(access, chosen, it.first) }
    if (withStandIn != null && size(withStandIn) <= budget) return Note(withStandIn, emptySet())
    return Note(plain, emptySet())
  }

  private fun size(lines: List<String>) = lines.sumOf { it.length + 1 }

  /** The most facts (best first) that fit [budget] alongside [recent], or null when [recent] alone doesn't fit. */
  private fun fill(access: MemoryAccess.On, facts: List<MemoryFact>, recent: String?, budget: Int)
      : Pair<List<String>, List<MemoryFact>>? {
    val chosen = mutableListOf<MemoryFact>()
    var best = render(access, chosen, recent)
    if (size(best) > budget) return null
    for (fact in facts) {
      val attempt = render(access, chosen + fact, recent)
      if (size(attempt) <= budget) {
        chosen += fact
        best = attempt
      }
    }
    return best to chosen.toList()
  }

  private fun score(fact: MemoryFact, inputs: Inputs): Double {
    val age = (inputs.nowMs - fact.lastSeenMs).coerceAtLeast(0L).toDouble()
    val recency = 2.0.pow(-age / RECENCY_HALF_LIFE_MS)
    val hour = Instant.ofEpochMilli(inputs.nowMs).atZone(inputs.zone).hour
    val relevance = when {
      fact.kind == Kind.MUSIC_TASTE && inputs.screen == MUSIC_SCREEN -> 2.0
      fact.kind == Kind.TICKER && hour in MORNING_FROM until MORNING_UNTIL -> 1.5
      else -> 1.0
    }
    return fact.confidence.coerceAtLeast(MemoryConfidence.TENTATIVE_AT) * recency * relevance
  }

  private fun render(access: MemoryAccess.On, facts: List<MemoryFact>, recent: String?): List<String> {
    if (facts.isEmpty() && recent == null) return emptyList()
    val byKind = facts.groupBy { it.kind }
    val groups = KIND_LINES.mapNotNull { (kind, prefix) ->
      byKind[kind]?.joinToString(", ", "$prefix: ", ".") { display(it) }
    }
    val team = byKind[Kind.TEAM]?.firstOrNull()?.takeIf { !access.othersNear || access.scope is MemoryScope.Person }
    val referent = team?.let { "If they say \"my team\" or \"they\" with no other referent, assume the ${it.label}." }
    return listOfNotNull(who(access)) + groups + listOfNotNull(recent, referent)
  }

  private fun who(access: MemoryAccess.On): String = when {
    access.scope is MemoryScope.Household ->
      "Nobody is recognised, so this is what the household often talks about."
    access.firstName != null && access.othersNear -> "You're talking with ${access.firstName}; others are nearby."
    access.firstName != null -> "You're talking with ${access.firstName}."
    else -> "You're talking with a recognised member of the household."
  }

  private fun display(fact: MemoryFact): String = when (fact.kind) {
    Kind.TEAM -> fact.attrs[MemoryDictionary.ATTR_LEAGUE]?.let { "${fact.label} ($it)" } ?: fact.label
    Kind.TICKER -> fact.id.substringAfter(':').takeUnless { it.startsWith("^") } ?: fact.label
    else -> fact.label
  }

  /** Extracted topics not offered before, newest first (plan §3.9: followed up at most once). */
  private fun threads(inputs: Inputs): List<RecentTopic> = inputs.profile.recentTopics
      .filter { it.extracted && it.shownCount == 0 && it.gist.isNotBlank() }
      .sortedByDescending { it.atMs }.take(MAX_THREADS)

  /**
   * "Recently: Tue asked about NVIDIA earnings (open: when they report)." within [RECENT_MAX] characters: as many
   * threads as fit, the first one shortened (its open question, then its gist) if it alone is too long.
   */
  private fun threadLine(inputs: Inputs, threads: List<RecentTopic>): Pair<String, Set<String>>? {
    fun day(t: RecentTopic) =
        Instant.ofEpochMilli(t.atMs).atZone(inputs.zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
    fun part(t: RecentTopic, open: Boolean) = "${day(t)} ${t.gist.trimEnd('.')}" +
        (t.openQuestion?.takeIf { open }?.let { " (open: ${it.trimEnd('.', '?')})" } ?: "")
    fun line(parts: List<String>) = parts.joinToString("; ", THREAD_PREFIX, ".")
    val first = threads.first()
    val firstPart = listOf(part(first, open = true), part(first, open = false)).firstOrNull {
      line(listOf(it)).length <= RECENT_MAX
    } ?: part(first, open = false).take(RECENT_MAX - THREAD_PREFIX.length - 2).trimEnd() + "…"
    val parts = mutableListOf(firstPart)
    val offered = mutableSetOf(first.topic)
    for (t in threads.drop(1)) {
      val next = part(t, open = true)
      if (line(parts + next).length <= RECENT_MAX) {
        parts += next
        offered += t.topic
      }
    }
    return line(parts) to offered
  }

  private fun recentLine(inputs: Inputs, usable: Set<String>): Pair<String, Set<String>>? {
    val topics = inputs.profile.recentTopics.filter { it.topic in usable && it.gist.isNotBlank() }
        .take(MemoryLearner.RECENT_KEEP)
    if (topics.isEmpty()) return null
    return topics.joinToString("; ", "Recently talked about: ", ".") { t ->
      val day = Instant.ofEpochMilli(t.atMs).atZone(inputs.zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
      "${t.gist} ($day)"
    } to emptySet()
  }

  private const val MUSIC_SCREEN = "music"
  /** The "Recently:" line's cap (plan §3.9). */
  const val RECENT_MAX = 160
  const val THREAD_PREFIX = "Recently: "
  private const val MAX_THREADS = 2
  private val EMPTY = Note(emptyList(), emptySet())
  private val KIND_LINES = listOf(
      Kind.TEAM to "Follows", Kind.TICKER to "Watches", Kind.CRYPTO to "Crypto", Kind.NEWS_TOPIC to "News",
      Kind.MUSIC_TASTE to "Music", Kind.PREFERENCE to "Prefers",
  )
}
