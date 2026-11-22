/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.State

/**
 * Every change to a profile (plan §3.2): learning from one conversation's observations, decay, Forget, Keep and
 * Pause. Each function takes a profile and returns a new one (never mutates), already [MemoryProfile.normalized].
 *
 * - **One vote per conversation:** a fact gets at most one piece of evidence per source per conversation, so saying
 *   "Warriors" ten times in one chat counts once; two separate chats are what promote it.
 * - **Forget sticks:** a SUPPRESSED fact keeps only its key and is never re-learned from casual mentions; only an
 *   explicit remember or a tick ([confirm]) brings it back.
 * - **Pause:** [learn] changes nothing while the profile is paused.
 * Pure: the clock is a parameter.
 */
internal object MemoryLearner {
  /** The stand-in for "Recent topics" in slice 1: the last few things talked about, by fact id. */
  const val RECENT_KEEP = 3

  /** A learning pass: the new profile and the facts that became usable in it (for the "Learned …" notice). */
  data class Result(val profile: MemoryProfile, val learned: List<MemoryFact>)

  fun learn(
      profile: MemoryProfile,
      observations: List<MemoryExtractor.Observation>,
      conversationId: String,
      portal: String,
      nowMs: Long,
  ): Result {
    val current = refresh(profile, nowMs)
    if (current.paused || observations.isEmpty()) return Result(current, emptyList())
    val byId = current.facts.associateBy { it.id }
    val touched = observations.mapNotNull { obs ->
      val existing = byId[obs.factId]
      if (existing?.state == State.SUPPRESSED || existing?.state == State.TOMBSTONE) return@mapNotNull null
      val evidence = Evidence(obs.source, nowMs, portal, conversationId, MemoryConfidence.weightOf(obs.source))
      val base = existing ?: MemoryFact(
          id = obs.factId, kind = obs.entry.kind, label = obs.entry.label, attrs = obs.entry.attrs,
          firstSeenMs = nowMs, lastSeenMs = nowMs,
      )
      val duplicate = base.evidence.any { it.conversationId == conversationId && it.source == obs.source }
      val withEvidence = if (duplicate) base else base.copy(evidence = listOf(evidence) + base.evidence)
      MemoryConfidence.refreshed(withEvidence.copy(lastSeenMs = nowMs, label = obs.entry.label), nowMs)
    }
    val touchedIds = touched.map { it.id }.toSet()
    val learned = touched.filter { MemoryConfidence.usable(it) && byId[it.id]?.let(MemoryConfidence::usable) != true }
    val facts = current.facts.filter { it.id !in touchedIds } + touched
    val topics = touched.map { RecentTopic(topic = it.id, gist = it.label, atMs = nowMs) } +
        current.recentTopics.filter { it.topic !in touchedIds }
    val next = current.copy(facts = facts, recentTopics = topics.take(RECENT_KEEP)).normalized()
    return Result(next, learned.map { f -> next.facts.first { it.id == f.id } })
  }

  /** Decay brought up to [nowMs]: confidence and state recomputed, long-unseen facts and old topics dropped. */
  fun refresh(profile: MemoryProfile, nowMs: Long): MemoryProfile = profile.copy(
      facts = profile.facts.filterNot { MemoryConfidence.expired(it, nowMs) }
          .map { MemoryConfidence.refreshed(it, nowMs) },
      recentTopics = profile.recentTopics.filter { nowMs - it.atMs <= RecentTopic.DROP_AFTER_MS },
  ).normalized()

  /** Forget [factIds]: each becomes a bare SUPPRESSED key (so it is never re-learned) and leaves Recent topics. */
  fun forget(profile: MemoryProfile, factIds: Set<String>, nowMs: Long): MemoryProfile {
    if (factIds.isEmpty()) return profile
    val existing = profile.facts.associateBy { it.id }
    val suppressed = factIds.map { id ->
      val kind = existing[id]?.kind ?: kindOf(id) ?: return@map null
      MemoryFact(id = id, kind = kind, label = "", state = State.SUPPRESSED, firstSeenMs = nowMs, lastSeenMs = nowMs)
    }.filterNotNull()
    val ids = suppressed.map { it.id }.toSet()
    return profile.copy(
        facts = profile.facts.filter { it.id !in ids } + suppressed,
        recentTopics = profile.recentTopics.filter { it.topic !in ids },
    ).normalized()
  }

  /**
   * Keep (the tick) or an explicit remember: the fact is CONFIRMED with full confidence, even if it was forgotten
   * before. [entry] supplies the label and attrs when the fact isn't known yet (or was only a suppressed key).
   */
  fun confirm(
      profile: MemoryProfile,
      entry: MemoryDictionary.Entry,
      source: Evidence.Source,
      conversationId: String?,
      portal: String,
      nowMs: Long,
  ): MemoryProfile {
    val existing = profile.facts.firstOrNull { it.id == entry.factId }?.takeIf { it.state != State.SUPPRESSED }
    val evidence = Evidence(source, nowMs, portal, conversationId, MemoryConfidence.weightOf(source))
    val fact = (existing ?: MemoryFact(
        id = entry.factId, kind = entry.kind, label = entry.label, attrs = entry.attrs,
        firstSeenMs = nowMs, lastSeenMs = nowMs,
    )).let { it.copy(evidence = listOf(evidence) + it.evidence, state = State.CONFIRMED, confidence = 1.0,
        lastSeenMs = nowMs) }
    return profile.copy(facts = profile.facts.filter { it.id != fact.id } + fact).normalized()
  }

  /** Keep a fact that is already known (the tick on a dashed row), by id. Unknown or forgotten ids change nothing. */
  fun keep(profile: MemoryProfile, factId: String, portal: String, nowMs: Long): MemoryProfile {
    val fact = profile.facts.firstOrNull { it.id == factId && it.state != State.SUPPRESSED } ?: return profile
    val evidence = Evidence(Evidence.Source.CONFIRM, nowMs, portal, null, 1.0)
    val kept = fact.copy(evidence = listOf(evidence) + fact.evidence, state = State.CONFIRMED, confidence = 1.0,
        lastSeenMs = nowMs)
    return profile.copy(facts = profile.facts.map { if (it.id == factId) kept else it }).normalized()
  }

  fun setPaused(profile: MemoryProfile, paused: Boolean): MemoryProfile = profile.copy(paused = paused)

  /** The kind a fact id names (`team:…` → TEAM), for forgetting an id that was never stored. */
  fun kindOf(factId: String): MemoryFact.Kind? = when (factId.substringBefore(':', "")) {
    "team" -> MemoryFact.Kind.TEAM
    "ticker" -> MemoryFact.Kind.TICKER
    "crypto" -> MemoryFact.Kind.CRYPTO
    "news" -> MemoryFact.Kind.NEWS_TOPIC
    "music" -> MemoryFact.Kind.MUSIC_TASTE
    else -> null
  }
}
