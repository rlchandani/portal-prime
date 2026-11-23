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

  /** Extracted Recent topics kept (slice 4), newest first, on top of the [RECENT_KEEP] stand-ins. */
  const val TOPICS_KEEP = 5

  /** Kinds kept out of every note and suggestion (and so never learned for the household). */
  private val SENSITIVE_KINDS = setOf(MemoryFact.Kind.PLACE, MemoryFact.Kind.PERSON_NAME)

  /** A learning pass: the new profile and the facts that became usable in it (for the "Learned …" notice). */
  data class Result(val profile: MemoryProfile, val learned: List<MemoryFact>)

  fun learn(
      profile: MemoryProfile,
      observations: List<MemoryExtractor.Observation>,
      conversationId: String,
      portal: String,
      nowMs: Long,
      topics: List<RecentTopic> = emptyList(),
  ): Result {
    val current = refresh(profile, nowMs)
    if (current.paused || (observations.isEmpty() && topics.isEmpty())) return Result(current, emptyList())
    val byId = current.facts.associateBy { it.id }
    val touched = observations.mapNotNull { obs ->
      val existing = byId[obs.factId]
      if (existing?.state == State.SUPPRESSED || existing?.state == State.TOMBSTONE) return@mapNotNull null
      val evidence = Evidence(obs.source, nowMs, portal, conversationId, MemoryConfidence.weightOf(obs.source))
      val base = existing ?: MemoryFact(
          id = obs.factId, kind = obs.entry.kind, label = obs.entry.label, attrs = obs.entry.attrs,
          firstSeenMs = nowMs, lastSeenMs = nowMs, sensitive = obs.entry.kind in SENSITIVE_KINDS,
      )
      val duplicate = base.evidence.any { it.conversationId == conversationId && it.source == obs.source }
      val withEvidence = if (duplicate) base else base.copy(evidence = listOf(evidence) + base.evidence)
      MemoryConfidence.refreshed(withEvidence.copy(lastSeenMs = nowMs, label = obs.entry.label), nowMs)
    }
    val touchedIds = touched.map { it.id }.toSet()
    val learned = touched.filter { MemoryConfidence.usable(it) && byId[it.id]?.let(MemoryConfidence::usable) != true }
    val facts = current.facts.filter { it.id !in touchedIds } + touched
    val standIns = touched.map { RecentTopic(topic = it.id, gist = it.label, atMs = nowMs) } +
        current.recentTopics.filter { !it.extracted && it.topic !in touchedIds }
    val next = current.copy(facts = facts,
        recentTopics = standIns.take(RECENT_KEEP) + extractedTopics(current.recentTopics, topics)).normalized()
    return Result(next, learned.map { f -> next.facts.first { it.id == f.id } })
  }

  /**
   * This conversation's extracted topics ahead of the older ones. A topic talked about again keeps its [shownCount],
   * so it is still followed up at most once.
   */
  private fun extractedTopics(existing: List<RecentTopic>, incoming: List<RecentTopic>): List<RecentTopic> {
    val old = existing.filter { it.extracted }
    val byId = old.associateBy { it.topic }
    val fresh = incoming.filter { it.extracted }.distinctBy { it.topic }
        .map { t -> t.copy(shownCount = byId[t.topic]?.shownCount ?: 0) }
    val ids = fresh.map { it.topic }.toSet()
    return (fresh + old.filter { it.topic !in ids }).take(TOPICS_KEEP)
  }

  /** The topics a conversation note just offered, each marked shown once more (so it isn't offered again). */
  fun markShown(profile: MemoryProfile, topicIds: Set<String>): MemoryProfile {
    if (topicIds.isEmpty()) return profile
    return profile.copy(recentTopics = profile.recentTopics.map {
      if (it.topic in topicIds) it.copy(shownCount = it.shownCount + 1) else it
    }).normalized()
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
      MemoryFact(id = id, kind = kind, label = "", state = State.SUPPRESSED, firstSeenMs = nowMs, lastSeenMs = nowMs,
          stateMs = nowMs)
    }.filterNotNull()
    val ids = suppressed.map { it.id }.toSet()
    return profile.copy(
        facts = profile.facts.filter { it.id !in ids } + suppressed,
        recentTopics = topicsWithout(profile.recentTopics, ids.mapNotNull { existing[it] } + suppressed),
    ).normalized()
  }

  /**
   * Forget wins over a thread about the same thing: [topics] without the stand-in for any of [forgotten] and without
   * an extracted topic naming one (by its label or id).
   */
  fun topicsWithout(topics: List<RecentTopic>, forgotten: List<MemoryFact>): List<RecentTopic> {
    if (forgotten.isEmpty()) return topics
    val ids = forgotten.map { it.id }.toSet()
    val names = forgotten.flatMap { listOf(it.label, it.id.substringAfter(':')) }
        .map(MemoryText::tokens).filter { it.isNotEmpty() }
    fun mentions(t: RecentTopic): Boolean {
      val words = MemoryText.tokens("${t.topic.substringAfter(':').replace('-', ' ')} ${t.gist} ${t.openQuestion.orEmpty()}")
      return names.any { MemoryText.contains(words, it) }
    }
    return topics.filter { it.topic !in ids && !(it.extracted && mentions(it)) }
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
        lastSeenMs = nowMs, stateMs = nowMs) }
    return profile.copy(facts = profile.facts.filter { it.id != fact.id } + fact).normalized()
  }

  /** Keep a fact that is already known (the tick on a dashed row), by id. Unknown or forgotten ids change nothing. */
  fun keep(profile: MemoryProfile, factId: String, portal: String, nowMs: Long): MemoryProfile {
    val fact = profile.facts.firstOrNull { it.id == factId && it.state != State.SUPPRESSED } ?: return profile
    val evidence = Evidence(Evidence.Source.CONFIRM, nowMs, portal, null, 1.0)
    val kept = fact.copy(evidence = listOf(evidence) + fact.evidence, state = State.CONFIRMED, confidence = 1.0,
        lastSeenMs = nowMs, stateMs = nowMs)
    return profile.copy(facts = profile.facts.map { if (it.id == factId) kept else it }).normalized()
  }

  /** Learning off or on; [nowMs] is kept so the latest choice wins on every Portal. */
  fun setPaused(profile: MemoryProfile, paused: Boolean, nowMs: Long): MemoryProfile =
      if (profile.paused == paused) profile else profile.copy(paused = paused, pausedMs = nowMs)

  /**
   * "Forget all" for [scope]: nothing kept but the tombstone, so every Portal drops what was learned before [nowMs]
   * when it syncs ([MemorySync]). Learning stays on; the pause choice is reset too.
   */
  fun forgotAll(scope: MemoryScope, nowMs: Long): MemoryProfile =
      MemoryProfile(scope, pausedMs = nowMs, forgotAllMs = nowMs)

  /** The kind a fact id names (`team:…` → TEAM), for forgetting an id that was never stored. */
  fun kindOf(factId: String): MemoryFact.Kind? = when (factId.substringBefore(':', "")) {
    "team" -> MemoryFact.Kind.TEAM
    "ticker" -> MemoryFact.Kind.TICKER
    "crypto" -> MemoryFact.Kind.CRYPTO
    "news" -> MemoryFact.Kind.NEWS_TOPIC
    "music" -> MemoryFact.Kind.MUSIC_TASTE
    "place" -> MemoryFact.Kind.PLACE
    "preference" -> MemoryFact.Kind.PREFERENCE
    else -> null
  }
}
