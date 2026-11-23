/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

/**
 * The personalization profile (docs/plans/jarvis-personalization.md §3.2): what Jarvis has learned
 * about one person, or about the household when nobody is recognised. One profile per [MemoryScope],
 * one sealed file each ([MemoryProfileStore]). Pure data plus [normalized], which enforces the
 * plan's caps and privacy rules so no writer can store something the plan forbids.
 *
 * Written by [MemoryLearner] (slice 1). [paused] is the person's "Learning off": nothing new is learned, what is
 * known stays and is still used.
 */
internal data class MemoryProfile(
    val scope: MemoryScope,
    val facts: List<MemoryFact> = emptyList(),
    val recentTopics: List<RecentTopic> = emptyList(),
    val paused: Boolean = false,
) {
  /**
   * The profile with every rule applied: caps (facts, evidence per fact, recent topics), confidence
   * clamped to 0..1, one fact per id, SUPPRESSED kept as a key only, and the household scope
   * limited to low-sensitivity kinds with nothing marked [MemoryFact.sensitive].
   */
  fun normalized(): MemoryProfile {
    val allowed = facts.filter { scope !is MemoryScope.Household || (it.kind.householdAllowed && !it.sensitive) }
    val unique = allowed.groupBy { it.id }.values.map { same -> same.maxBy { it.lastSeenMs } }
    val kept = unique.sortedWith(KEEP_ORDER).take(MAX_FACTS).map(MemoryFact::normalized).sortedBy { it.id }
    val topics = recentTopics.sortedByDescending { it.atMs }.take(MAX_RECENT_TOPICS).map(RecentTopic::normalized)
    return copy(facts = kept, recentTopics = topics)
  }

  companion object {
    const val MAX_FACTS = 300
    /** Slice 1's [MemoryLearner.RECENT_KEEP] stand-ins plus up to [MemoryLearner.TOPICS_KEEP] extracted topics. */
    const val MAX_RECENT_TOPICS = 8

    /** Over the cap, SUPPRESSED facts are kept first (they stop re-learning), then the most recently seen. */
    private val KEEP_ORDER = compareByDescending<MemoryFact> { it.state == MemoryFact.State.SUPPRESSED }
        .thenByDescending { it.lastSeenMs }.thenBy { it.id }
  }
}

/** Whose profile: a recognised household person (by household-stable id) or the household. */
internal sealed interface MemoryScope {
  /** Stable, file-name-safe token: `household` or `person-<id>`. */
  val token: String

  data object Household : MemoryScope {
    override val token = "household"
  }

  data class Person(val householdPersonId: String) : MemoryScope {
    init {
      require(ID.matches(householdPersonId)) { "invalid person id" }
    }

    override val token get() = "$PERSON_PREFIX$householdPersonId"
  }

  companion object {
    private const val PERSON_PREFIX = "person-"
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    /** The scope for a [token], or null if it isn't one. */
    fun fromToken(token: String): MemoryScope? = when {
      token == Household.token -> Household
      token.startsWith(PERSON_PREFIX) -> token.removePrefix(PERSON_PREFIX).takeIf(ID::matches)?.let(::Person)
      else -> null
    }
  }
}

/**
 * One learned item. [id] is stable, `<kind>:<canonical>` (e.g. `team:nba.gsw`). [evidence] is the
 * provenance, newest first; entries past the cap are folded into [foldedEvidence].
 * [confidence] is recomputed on read from evidence and decay (slice 1); stored for display.
 */
internal data class MemoryFact(
    val id: String,
    val kind: Kind,
    val label: String,
    val attrs: Map<String, String> = emptyMap(),
    val evidence: List<Evidence> = emptyList(),
    val foldedEvidence: Int = 0,
    val confidence: Double = 0.0,
    val state: State = State.TENTATIVE,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** Never injected when others are near, never learned at household scope. */
    val sensitive: Boolean = false,
) {
  /** [halfLifeMs] drives decay; [householdAllowed] is plan §4.4's household-scope allowlist. */
  enum class Kind(val halfLifeMs: Long, val householdAllowed: Boolean) {
    TEAM(INTEREST_HALF_LIFE_MS, true),
    TICKER(INTEREST_HALF_LIFE_MS, true),
    CRYPTO(INTEREST_HALF_LIFE_MS, true),
    NEWS_TOPIC(INTEREST_HALF_LIFE_MS, true),
    MUSIC_TASTE(INTEREST_HALF_LIFE_MS, true),
    ROUTINE(ROUTINE_HALF_LIFE_MS, true),
    PLACE(INTEREST_HALF_LIFE_MS, false),
    PERSON_NAME(INTEREST_HALF_LIFE_MS, false),
    PREFERENCE(INTEREST_HALF_LIFE_MS, false),
    DISLIKE(INTEREST_HALF_LIFE_MS, false),
  }

  enum class State { TENTATIVE, ACTIVE, CONFIRMED, SUPPRESSED, TOMBSTONE }

  /** A SUPPRESSED fact keeps only its key, so it is never re-learned and holds nothing personal. */
  fun normalized(): MemoryFact {
    if (state == State.SUPPRESSED) {
      return copy(label = "", attrs = emptyMap(), evidence = emptyList(), foldedEvidence = 0, confidence = 0.0)
    }
    val newest = evidence.sortedByDescending { it.atMs }
    val overflow = (newest.size - MAX_EVIDENCE).coerceAtLeast(0)
    return copy(
        evidence = newest.take(MAX_EVIDENCE),
        foldedEvidence = foldedEvidence.coerceAtLeast(0) + overflow,
        confidence = confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
    )
  }

  companion object {
    const val MAX_EVIDENCE = 40
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val INTEREST_HALF_LIFE_MS = 90 * DAY_MS
    const val ROUTINE_HALF_LIFE_MS = 21 * DAY_MS
  }
}

/** One observation behind a fact: where it came from, when, on which Portal, and how much it counts. */
internal data class Evidence(
    val source: Source,
    val atMs: Long,
    val portal: String,
    val conversationId: String? = null,
    val weight: Double,
) {
  enum class Source { TOOL_CALL, UTTERANCE, INTENT_PHRASE, USAGE, WIDGET, CONFIRM, EXPLICIT }
}

/**
 * A conversation thread worth picking up again ("Recent topics"). [gist] is at most 140 characters. [topic] is a fact
 * id for slice 1's stand-in (the last things talked about), or `topic:<slug>` for one the slice 4 extractor gave.
 * [shownCount] is how many conversation notes offered it: Jarvis may follow a topic up once (plan §3.9), so a topic
 * already offered is not offered again.
 */
internal data class RecentTopic(
    val topic: String,
    val gist: String,
    val openQuestion: String? = null,
    val atMs: Long,
    val shownCount: Int = 0,
) {
  /** From the slice 4 extractor (its own thread), rather than slice 1's stand-in for a fact. */
  val extracted: Boolean get() = topic.startsWith(LlmExtractionCheck.TOPIC_PREFIX)

  fun normalized(): RecentTopic = copy(gist = gist.take(MAX_GIST), openQuestion = openQuestion?.take(MAX_QUESTION),
      shownCount = shownCount.coerceAtLeast(0))

  companion object {
    const val MAX_GIST = 140
    const val MAX_QUESTION = 100
    const val HALF_LIFE_MS = 7 * MemoryFact.DAY_MS
    const val DROP_AFTER_MS = 14 * MemoryFact.DAY_MS
  }
}
