/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Embeddings, cosine thresholds and frame voting. Synthetic vectors only, no faces. */
class WhosThereMatchingTest {

  /** A unit vector at [angle] radians from axis 0 towards axis [axis] (so cosine = cos(angle)). */
  private fun vec(angle: Double, axis: Int = 1, base: Int = 0): Embedding {
    val a = FloatArray(128)
    a[base] = cos(angle).toFloat()
    a[axis] += sin(angle).toFloat()
    return Embedding.of(a)!!
  }

  private fun axis(i: Int): Embedding = Embedding.of(FloatArray(128).also { it[i] = 1f })!!

  private val maya = FaceTemplates("mayaaaaa", listOf(axis(0), vec(0.2, 5)))
  private val arjun = FaceTemplates("arjunnnn", listOf(axis(10)))

  @Test fun embeddingNormalisesAndRejectsBadVectors() {
    val e = Embedding.of(floatArrayOf(3f, 4f))!!
    assertEquals(0.6f, e[0], 1e-6f)
    assertEquals(0.8f, e[1], 1e-6f)
    assertEquals(1f, e.cosine(e), 1e-6f)
    assertNull(Embedding.of(floatArrayOf()))
    assertNull(Embedding.of(floatArrayOf(0f, 0f)))
    assertNull(Embedding.of(floatArrayOf(1f, Float.NaN)))
    assertNull(Embedding.of(floatArrayOf(Float.POSITIVE_INFINITY)))
  }

  @Test fun embeddingIsImmutableAndComparesByValue() {
    val raw = floatArrayOf(1f, 0f)
    val e = Embedding.of(raw)!!
    raw[0] = 0f
    assertEquals(1f, e[0], 0f)
    e.toArray()[0] = 9f
    assertEquals(1f, e[0], 0f)
    assertEquals(e, Embedding.of(floatArrayOf(2f, 0f)))
    assertEquals(e.hashCode(), Embedding.of(floatArrayOf(2f, 0f)).hashCode())
  }

  @Test(expected = IllegalArgumentException::class)
  fun cosineRejectsMismatchedSizes() {
    Embedding.of(floatArrayOf(1f, 0f))!!.cosine(Embedding.of(floatArrayOf(1f, 0f, 0f))!!)
  }

  @Test fun bestLookPerPersonIsUsed() {
    val s = WhosThereMatcher.scores(vec(0.2, 5), listOf(maya, arjun))
    assertEquals(1f, s.getValue("mayaaaaa"), 1e-5f)
    assertEquals(0f, s.getValue("arjunnnn"), 1e-5f)
  }

  @Test fun acceptAboveThresholdWithAClearMargin() {
    val m = WhosThereMatcher.match(vec(0.5), listOf(maya, arjun)) // cos 0.877
    assertTrue(m is FaceMatch.Accepted && m.personId == "mayaaaaa")
  }

  @Test fun theAskBandIsUnsure() {
    val angle = Math.acos(0.40) // between ask 0.36 and accept 0.45
    val m = WhosThereMatcher.match(vec(angle, axis = 50), listOf(maya, arjun))
    assertTrue(m.toString(), m is FaceMatch.Unsure && m.personId == "mayaaaaa")
  }

  @Test fun belowAskIsUnknown() {
    assertEquals(FaceMatch.Unknown, WhosThereMatcher.match(axis(60), listOf(maya, arjun)))
    assertEquals(FaceMatch.Unknown, WhosThereMatcher.match(axis(0), emptyList()))
    assertEquals(FaceMatch.Unknown, WhosThereMatcher.match(axis(0), listOf(FaceTemplates("emptyyyy", emptyList()))))
  }

  @Test fun lookAlikesWithoutAMarginAsk() {
    // Equidistant from both people: high score, no margin → ask, never guess.
    val a = FloatArray(128).also { it[0] = 1f; it[10] = 1f }
    val m = WhosThereMatcher.match(Embedding.of(a)!!, listOf(maya, arjun)) // 0.707 each
    assertTrue(m is FaceMatch.Unsure)
  }

  @Test fun thresholdsMustBeOrdered() {
    runCatching { MatchThresholds(accept = 0.3f, ask = 0.4f) }.let { assertTrue(it.isFailure) }
    runCatching { MatchThresholds(margin = -1f) }.let { assertTrue(it.isFailure) }
  }

  private val acceptMaya = FaceMatch.Accepted("mayaaaaa", 0.8f)
  private val acceptArjun = FaceMatch.Accepted("arjunnnn", 0.8f)
  private val unsureMaya = FaceMatch.Unsure("mayaaaaa", 0.4f)

  @Test fun oneLuckyFrameDoesNotSwitchAHome() {
    val o = WhosThereMatcher.vote(listOf(listOf(acceptMaya), listOf(FaceMatch.Unknown), listOf(FaceMatch.Unknown)))
    assertTrue(o.recognised.isEmpty())
    assertNull(o.unsure) // one leaning frame of three is not enough either
    assertEquals(1, o.faceCount)
    assertEquals(3, o.framesAnalysed)
  }

  @Test fun twoVotesRecognise() {
    val o = WhosThereMatcher.vote(listOf(listOf(acceptMaya), listOf(acceptMaya), listOf(unsureMaya)))
    assertEquals(listOf("mayaaaaa"), o.recognised)
    assertNull(o.unsure)
    assertEquals(0, o.others)
  }

  @Test fun fewFramesNeedOneVote() {
    assertEquals(1, WhosThereMatcher.minVotes(1))
    assertEquals(1, WhosThereMatcher.minVotes(2))
    assertEquals(2, WhosThereMatcher.minVotes(5))
    assertEquals(listOf("mayaaaaa"), WhosThereMatcher.vote(listOf(listOf(acceptMaya))).recognised)
  }

  @Test fun unsureAcrossFramesBecomesUnsure() {
    val o = WhosThereMatcher.vote(listOf(listOf(unsureMaya), listOf(acceptMaya), listOf(unsureMaya)))
    assertTrue(o.recognised.isEmpty())
    assertEquals("mayaaaaa", o.unsure)
  }

  @Test fun twoPeopleAndAStranger() {
    val f = listOf(acceptMaya, acceptArjun, FaceMatch.Unknown)
    val o = WhosThereMatcher.vote(listOf(f, f, f.take(2)))
    assertEquals(setOf("mayaaaaa", "arjunnnn"), o.recognised.toSet())
    assertEquals(3, o.faceCount)
    assertEquals(1, o.others)
    assertFalse(o.nobody)
  }

  @Test fun recognisedNeverExceedsFacesInAFrame() {
    // Alternating identities for a single face: at most one may be recognised.
    val o = WhosThereMatcher.vote(listOf(listOf(acceptMaya), listOf(acceptArjun), listOf(acceptMaya), listOf(acceptArjun)))
    assertEquals(1, o.recognised.size)
  }

  @Test fun noFacesIsNobody() {
    val o = WhosThereMatcher.vote(listOf(emptyList(), emptyList()))
    assertTrue(o.nobody)
    assertEquals(GlanceOutcome.EMPTY, o)
    assertTrue(WhosThereMatcher.vote(emptyList()).nobody)
  }

  @Test fun samePersonTwiceInAFrameCountsOnce() {
    val o = WhosThereMatcher.vote(listOf(listOf(acceptMaya, acceptMaya), listOf(FaceMatch.Unknown), listOf(FaceMatch.Unknown)))
    assertTrue(o.recognised.isEmpty())
  }

  @Test fun personColoursAndNames() {
    assertEquals(WhosThereColors.at(0), WhosThereColors.at(8))
    assertEquals(WhosThereColors.at(7), WhosThereColors.at(-1))
    assertEquals(2, WhosThereColors.firstFree(listOf(0, 1)))
    assertNotNull(WhosThereColors.firstFree(WhosThereColors.all.indices.toList()))
    assertEquals("Maya Sharma", WhosThereLimits.cleanName("  Maya \n  Sharma "))
    assertEquals(20, WhosThereLimits.cleanName("x".repeat(40)).length)
    assertEquals("Add a name", WhosThereLimits.validName("  ", emptyList()))
    assertNotNull(WhosThereLimits.validName("maya", listOf("Maya")))
    assertNull(WhosThereLimits.validName("Maya", listOf("Arjun")))
    val p = WhosTherePerson("mayaaaaa", "maya", WhosThereRole.ADULT, 0, 0L, pausedUntilMs = 100L)
    assertEquals("M", p.initial)
    assertTrue(p.pausedAt(50L))
    assertFalse(p.pausedAt(100L))
    assertEquals("?", p.copy(name = " ").initial)
    assertTrue(WhosThereRole.KID.parentManaged)
    assertFalse(WhosThereRole.TEEN.parentManaged)
    assertEquals(WhosThereRole.ADULT, WhosThereRole.parse("nope"))
    assertEquals(WhosThereRole.TEEN, WhosThereRole.parse("TEEN"))
    assertEquals(1.0, sqrt(1.0), 0.0)
  }
}
