/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Cosine matching against enrolled templates, with an explicit "not sure" band.
 *
 * SFace's published operating point is cosine 0.363 (OpenCV `FaceRecognizerSF`). We accept a
 * personal Home only above [MatchThresholds.accept], treat [MatchThresholds.ask]…accept as "not
 * sure" (shared view, or "Is this you?"), and require the best person to beat the runner-up by
 * [MatchThresholds.margin] so look-alikes ask instead of guessing. Everything below `ask` is an
 * unknown face: its embedding is dropped by the caller and never stored.
 */
internal data class MatchThresholds(
    val accept: Float = 0.45f,
    val ask: Float = 0.36f,
    val margin: Float = 0.06f,
) {
  init {
    require(ask in 0f..1f && accept in ask..1f && margin >= 0f) { "invalid thresholds" }
  }
}

/** One face's verdict. */
internal sealed interface FaceMatch {
  data class Accepted(val personId: String, val score: Float) : FaceMatch
  data class Unsure(val personId: String, val score: Float) : FaceMatch
  data object Unknown : FaceMatch
}

/** What a whole glance saw, after voting across frames. Holds ids and counts only. */
internal data class GlanceOutcome(
    /** People accepted in enough frames, most votes first. */
    val recognised: List<String>,
    /** A person who was close but not certain (only when nobody was recognised). */
    val unsure: String?,
    /** The most faces seen together in one frame. */
    val faceCount: Int,
    /** Frames that produced at least one embedding. */
    val framesAnalysed: Int,
) {
  val nobody: Boolean get() = faceCount == 0
  /** Faces in view that are not a recognised person (unknown, unsure or paused). */
  val others: Int get() = (faceCount - recognised.size).coerceAtLeast(0)

  companion object {
    val EMPTY = GlanceOutcome(emptyList(), null, 0, 0)
  }
}

internal object WhosThereMatcher {

  /** Best score per person: the closest of their looks. */
  fun scores(face: Embedding, templates: List<FaceTemplates>): Map<String, Float> =
      templates.filter { it.looks.isNotEmpty() }.associate { t ->
        t.personId to t.looks.maxOf { look -> if (look.size == face.size) look.cosine(face) else -1f }
      }

  fun match(face: Embedding, templates: List<FaceTemplates>, t: MatchThresholds = MatchThresholds()): FaceMatch {
    val ranked = scores(face, templates).entries.sortedByDescending { it.value }
    val best = ranked.firstOrNull() ?: return FaceMatch.Unknown
    val runnerUp = ranked.getOrNull(1)?.value ?: -1f
    val clear = best.value - runnerUp >= t.margin
    return when {
      best.value >= t.accept && clear -> FaceMatch.Accepted(best.key, best.value)
      best.value >= t.ask -> FaceMatch.Unsure(best.key, best.value)
      else -> FaceMatch.Unknown
    }
  }

  /**
   * Votes across frames. [frames] holds, per analysed frame, the verdict for every face in it.
   * A person counts as recognised when accepted in at least [minVotes] frames (two once we have
   * three or more frames, so one lucky frame can't switch someone's Home). A person accepted
   * once and unsure elsewhere, or only unsure, becomes [GlanceOutcome.unsure].
   */
  fun vote(frames: List<List<FaceMatch>>): GlanceOutcome {
    val analysed = frames.filter { it.isNotEmpty() }
    if (analysed.isEmpty()) return GlanceOutcome.EMPTY
    val minVotes = minVotes(analysed.size)
    val accepted = HashMap<String, Int>()
    val leaning = HashMap<String, Int>()
    analysed.forEach { faces ->
      // A person counts once per frame even if two faces matched them.
      faces.filterIsInstance<FaceMatch.Accepted>().map { it.personId }.toSet().forEach { accepted.merge(it, 1, Int::plus) }
      faces.mapNotNull { (it as? FaceMatch.Unsure)?.personId ?: (it as? FaceMatch.Accepted)?.personId }
          .toSet().forEach { leaning.merge(it, 1, Int::plus) }
    }
    val faceCount = analysed.maxOf { it.size }
    val recognised = accepted.filterValues { it >= minVotes }.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
        .take(faceCount)
    val unsure = if (recognised.isNotEmpty()) null
        else leaning.filterValues { it >= minVotes }.maxByOrNull { it.value }?.key
    return GlanceOutcome(recognised, unsure, faceCount, analysed.size)
  }

  fun minVotes(frames: Int): Int = if (frames >= 3) 2 else 1

  /** Votes needed for an early verdict (one face, the same person, these many frames running). */
  const val EARLY_VOTES = 3

  /**
   * A verdict safe to act on before the glance ends: the last [EARLY_VOTES] analysed frames each
   * hold exactly one face, accepted as the same person. Anything else waits for the full vote.
   */
  fun early(frames: List<List<FaceMatch>>): GlanceOutcome? {
    val recent = frames.filter { it.isNotEmpty() }.takeLast(EARLY_VOTES)
    if (recent.size < EARLY_VOTES || recent.any { it.size != 1 }) return null
    val ids = recent.map { (it.single() as? FaceMatch.Accepted)?.personId }
    val id = ids.first() ?: return null
    return if (ids.all { it == id }) GlanceOutcome(listOf(id), null, 1, frames.count { it.isNotEmpty() }) else null
  }
}
