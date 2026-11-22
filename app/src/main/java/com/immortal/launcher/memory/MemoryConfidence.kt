/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.State
import kotlin.math.exp
import kotlin.math.pow

/**
 * How sure Jarvis is about a fact (plan §3.2), recomputed from its evidence on every read so decay needs no timer.
 *
 * `confidence = 1 − exp(−Σ wᵢ · 2^(−ageᵢ / halfLife(kind)))`. Promotion is by repetition, not by a single loud
 * number: a fact turns ACTIVE once it was heard in [ACTIVE_CONVERSATIONS] separate conversations, or once from an
 * intent phrase ("keep an eye on AMD") or an explicit remember, as long as its decayed confidence is still at least
 * [TENTATIVE_AT]; or whenever confidence alone reaches [ACTIVE_AT]. Below [TENTATIVE_AT] it is back to TENTATIVE.
 * CONFIRMED (a tick or "remember") and SUPPRESSED (Forget) are the person's word and never change here. Pure.
 */
internal object MemoryConfidence {
  const val TENTATIVE_AT = 0.30
  const val ACTIVE_AT = 0.60
  const val ACTIVE_CONVERSATIONS = 2

  /** A fact not seen for this long is dropped (plan §4.6); SUPPRESSED keys stay so Forget sticks. */
  const val DROP_UNSEEN_MS = 180 * MemoryFact.DAY_MS

  /** What one observation of [source] counts for. CONFIRM sets the state; its weight only keeps the meter full. */
  fun weightOf(source: Evidence.Source): Double = when (source) {
    Evidence.Source.TOOL_CALL -> 0.5
    Evidence.Source.INTENT_PHRASE -> 0.9
    Evidence.Source.UTTERANCE -> 0.3
    Evidence.Source.USAGE -> 0.15
    Evidence.Source.WIDGET -> 0.25
    Evidence.Source.CONFIRM, Evidence.Source.EXPLICIT -> 1.0
  }

  /** The decayed confidence of [fact] at [nowMs], 0..1. Folded evidence counts as casual mentions at the oldest age. */
  fun confidence(fact: MemoryFact, nowMs: Long): Double {
    val halfLife = fact.kind.halfLifeMs.toDouble()
    fun decayed(weight: Double, atMs: Long) = weight * 2.0.pow(-(nowMs - atMs).coerceAtLeast(0L) / halfLife)
    val live = fact.evidence.sumOf { decayed(it.weight, it.atMs) }
    val oldest = fact.evidence.minOfOrNull { it.atMs } ?: fact.firstSeenMs
    val folded = fact.foldedEvidence.coerceAtLeast(0) * decayed(weightOf(Evidence.Source.UTTERANCE), oldest)
    return (1 - exp(-(live + folded))).coerceIn(0.0, 1.0)
  }

  /** Separate conversations behind [fact] (evidence without a conversation counts once each). */
  fun conversations(fact: MemoryFact): Int =
      fact.evidence.map { it.conversationId ?: "@${it.atMs}" }.distinct().size + fact.foldedEvidence.coerceAtLeast(0)

  /** The state [fact] has at [nowMs]: the person's word if they gave it, else what the evidence supports. */
  fun stateOf(fact: MemoryFact, nowMs: Long): State {
    if (fact.state == State.CONFIRMED || fact.state == State.SUPPRESSED || fact.state == State.TOMBSTONE) {
      return fact.state
    }
    val c = confidence(fact, nowMs)
    val strong = fact.evidence.any {
      it.source == Evidence.Source.INTENT_PHRASE || it.source == Evidence.Source.EXPLICIT
    }
    val repeated = conversations(fact) >= ACTIVE_CONVERSATIONS
    return when {
      c >= ACTIVE_AT -> State.ACTIVE
      c >= TENTATIVE_AT && (strong || repeated) -> State.ACTIVE
      else -> State.TENTATIVE
    }
  }

  /** [fact] with its confidence and state brought up to [nowMs]. */
  fun refreshed(fact: MemoryFact, nowMs: Long): MemoryFact = when (fact.state) {
    State.SUPPRESSED, State.TOMBSTONE -> fact
    State.CONFIRMED -> fact.copy(confidence = 1.0)
    else -> fact.copy(confidence = confidence(fact, nowMs), state = stateOf(fact, nowMs))
  }

  /** Whether [fact] is gone at [nowMs]: unseen too long, unless it is a Forget that must keep holding. */
  fun expired(fact: MemoryFact, nowMs: Long): Boolean =
      fact.state != State.SUPPRESSED && nowMs - fact.lastSeenMs > DROP_UNSEEN_MS

  /** Whether [fact] may reach Jarvis's prompt or a suggestion: ACTIVE or CONFIRMED. */
  fun usable(fact: MemoryFact): Boolean = fact.state == State.ACTIVE || fact.state == State.CONFIRMED

  /** The 1–4 bar meter the "What Jarvis knows" screen draws (mock: conf 1..4). */
  fun bars(fact: MemoryFact): Int = when {
    fact.state == State.CONFIRMED -> 4
    fact.confidence >= 0.8 -> 4
    fact.confidence >= ACTIVE_AT -> 3
    fact.confidence >= TENTATIVE_AT || fact.state == State.ACTIVE -> 2
    else -> 1
  }
}
