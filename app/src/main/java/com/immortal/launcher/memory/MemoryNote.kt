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

  /** The note's lines, or empty when there is nothing to say (or nobody to say it about). */
  fun lines(inputs: Inputs): List<String> {
    val access = inputs.access as? MemoryAccess.On ?: return emptyList()
    val shared = access.othersNear || access.scope is MemoryScope.Household
    val budget = if (shared) SHARED_BUDGET else BUDGET
    val facts = inputs.profile.facts
        .filter { MemoryConfidence.usable(it) && !it.sensitive && it.label.isNotBlank() }
        .sortedByDescending { score(it, inputs) }
    if (facts.isEmpty()) return emptyList()
    val recent = if (shared) null else recentLine(inputs, facts.map { it.id }.toSet())
    val chosen = mutableListOf<MemoryFact>()
    var best = render(access, chosen, recent = null)
    for (fact in facts) {
      val attempt = render(access, chosen + fact, recent = null)
      if (attempt.sumOf { it.length + 1 } <= budget) {
        chosen += fact
        best = attempt
      }
    }
    val withRecent = recent?.let { render(access, chosen, it) }
    return if (withRecent != null && withRecent.sumOf { it.length + 1 } <= budget) withRecent else best
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
    if (facts.isEmpty()) return emptyList()
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

  private fun recentLine(inputs: Inputs, usable: Set<String>): String? {
    val topics = inputs.profile.recentTopics.filter { it.topic in usable && it.gist.isNotBlank() }
        .take(MemoryLearner.RECENT_KEEP)
    if (topics.isEmpty()) return null
    return topics.joinToString("; ", "Recently talked about: ", ".") { t ->
      val day = Instant.ofEpochMilli(t.atMs).atZone(inputs.zone).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
      "${t.gist} ($day)"
    }
  }

  private const val MUSIC_SCREEN = "music"
  private val KIND_LINES = listOf(
      Kind.TEAM to "Follows", Kind.TICKER to "Watches", Kind.CRYPTO to "Crypto", Kind.NEWS_TOPIC to "News",
      Kind.MUSIC_TASTE to "Music",
  )
}
