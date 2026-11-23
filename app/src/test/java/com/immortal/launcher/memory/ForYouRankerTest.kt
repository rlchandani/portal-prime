/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFixtures.DAY
import com.immortal.launcher.memory.MemoryFixtures.NOW
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "For you right now" habit ranking (plan §3.6): lift, support, decay, fatigue and the hard suppressions. */
class ForYouRankerTest {
  private val zone = ZoneOffset.UTC
  private val house = MemoryScope.Household
  private val alex = MemoryScope.Person("p1")
  private val hour = 3_600_000L

  /** [surface] opened at 15:00 UTC on each of [daysAgo] (NOW is Monday 15:00). */
  private fun UsageCounts.opened(surface: String, vararg daysAgo: Int, at: Long = 0, scope: MemoryScope = house) =
      // Oldest first, as time passes: recording prunes anything "in the future" of the moment recorded.
      daysAgo.sortedDescending().fold(this) { c, d -> c.record(scope, surface, NOW - d * DAY + at, zone) }

  private fun rank(counts: UsageCounts, feedback: ForYouFeedback = ForYouFeedback(), scope: MemoryScope = house,
      now: Long = NOW) = ForYouRanker.rank(counts, feedback, scope, now, zone)

  // Weekdays only (NOW is a Monday; ages 1, 2, 8, 9 are weekends): Mon, Fri, Thu this week; Mon, Fri the week before.
  private val weekdayHabit = UsageCounts().opened("screen:music", 0, 3, 4, 7, 10)

  @Test fun aRepeatedOpenAtTheSameHourOverTwoWeeksIsSuggested() {
    val ranked = rank(weekdayHabit)
    assertEquals(listOf("screen:music"), ranked.map { it.surface })
    assertEquals(5, ranked.single().opens)
    assertTrue(ranked.single().score > ForYouRanker.MIN_SCORE)
  }

  @Test fun notEnoughOpensIsNothing() {
    assertTrue(rank(UsageCounts().opened("screen:music", 0, 7, 8)).isEmpty())
  }

  @Test fun opensInOnlyOneWeekAreNotAHabitYet() {
    assertTrue(rank(UsageCounts().opened("screen:music", 0, 3, 4, 5, 6)).isEmpty())
  }

  @Test fun somethingOpenedAllDayLongHasNoLiftAndIsNotSuggested() {
    val allDay = listOf(10, 7, 3, 0).fold(UsageCounts()) { c, d ->
      (0 until 24).fold(c) { acc, h -> acc.record(house, "app:com.example.tv", NOW - d * DAY + (h - 15) * hour, zone) }
    }
    assertEquals(4 * 24, allDay.buckets.size)
    assertTrue(rank(allDay).isEmpty())
  }

  @Test fun theHabitWindowIsAnHourEitherSide() {
    assertEquals(1, rank(weekdayHabit, now = NOW + hour).size)
    assertTrue(rank(weekdayHabit, now = NOW + 2 * hour).isEmpty())
    assertEquals(1, rank(weekdayHabit, now = NOW - hour).size)
  }

  @Test fun weekdayHabitsStayOnWeekdays() {
    // Saturday 15:00 (NOW is Monday; two days back).
    assertTrue(rank(weekdayHabit, now = NOW - 2 * DAY).isEmpty())
  }

  @Test fun olderOpensWeighLessThanRecentOnes() {
    assertEquals(0.5, ForYouRanker.decay(ForYouRanker.HALF_LIFE_DAYS.toLong()), 1e-9)
    assertEquals(1.0, ForYouRanker.decay(0), 1e-9)
    val recent = rank(UsageCounts().opened("app:a", 0, 3, 4, 5, 7)).single()
    val older = rank(UsageCounts().opened("app:b", 0, 7, 10, 11, 12)).single()
    assertTrue(recent.score > older.score)
  }

  @Test fun moreOpensRankHigherAndAtMostAFewComeBack() {
    val opens = listOf("screen:music" to listOf(0, 3, 4, 5, 6, 7, 10, 11), "screen:calls" to listOf(0, 3, 7, 10),
        "jarvis" to listOf(0, 3, 4, 7, 10, 11))
    // In time order across surfaces, as they happen.
    val counts = opens.flatMap { (surface, days) -> days.map { surface to it } }.sortedByDescending { it.second }
        .fold(UsageCounts()) { c, (surface, d) -> c.record(house, surface, NOW - d * DAY, zone) }
    val ranked = rank(counts)
    assertEquals(listOf("screen:music", "jarvis", "screen:calls"), ranked.map { it.surface })
  }

  @Test fun onlyTheAskingScopesOwnCountsAreUsed() {
    val counts = UsageCounts().opened("screen:music", 0, 3, 4, 7, 10, scope = alex)
    assertTrue(rank(counts).isEmpty())
    assertEquals(1, rank(counts, scope = alex).size)
  }

  @Test fun homeSettingsAndHearthpanesOwnPackageAreNeverSuggested() {
    assertFalse(ForYouRanker.eligible("screen:home"))
    assertFalse(ForYouRanker.eligible("screen:settings"))
    assertFalse(ForYouRanker.eligible("screen:control_center"))
    assertFalse(ForYouRanker.eligible("app:com.immortal.launcher"))
    assertTrue(ForYouRanker.eligible("app:com.spotify.music"))
    assertTrue(ForYouRanker.eligible("screen:music"))
    assertTrue(ForYouRanker.eligible("screen:calls"))
    assertTrue(ForYouRanker.eligible("jarvis"))
    val counts = UsageCounts().opened("screen:home", 0, 3, 4, 7, 10)
    assertTrue(rank(counts).isEmpty())
  }

  @Test fun dontSuggestThisIsNeverSuggestedAgain() {
    val never = ForYouFeedback().with(house, "screen:music", ForYouFeedback.Kind.NEVER, NOW - 30 * DAY)
    assertTrue(rank(weekdayHabit, never).isEmpty())
    assertEquals(1, rank(weekdayHabit, never.allowAgain(house, "screen:music")).size)
  }

  @Test fun notNowHidesItForTheRestOfTheWindow() {
    val notNow = ForYouFeedback().with(house, "screen:music", ForYouFeedback.Kind.NOT_NOW, NOW)
    assertTrue(rank(weekdayHabit, notNow).isEmpty())
    assertTrue(rank(weekdayHabit, notNow, now = NOW + hour).isEmpty())
  }

  @Test fun unacceptedImpressionsFadeASuggestionAway() {
    val base = rank(weekdayHabit).single().score
    val shown = ForYouFeedback().with(house, "screen:music", ForYouFeedback.Kind.SHOWN, NOW - DAY)
    val once = rank(weekdayHabit, shown).single().score
    assertEquals(base * ForYouFeedback.FATIGUE, once, 1e-9)
    val ignored = (1..20).fold(ForYouFeedback()) { f, d ->
      f.with(house, "screen:music", ForYouFeedback.Kind.SHOWN, NOW - d * hour * 7)
    }
    assertTrue(rank(weekdayHabit, ignored).isEmpty())
  }

  @Test fun anAcceptedImpressionDoesNotTire() {
    val accepted = ForYouFeedback()
        .with(house, "screen:music", ForYouFeedback.Kind.SHOWN, NOW - DAY)
        .with(house, "screen:music", ForYouFeedback.Kind.OPENED, NOW - DAY + 60_000)
    assertEquals(rank(weekdayHabit).single().score, rank(weekdayHabit, accepted).single().score, 1e-9)
  }

  @Test fun showLessOftenHalvesTheScore() {
    val less = ForYouFeedback().with(house, "screen:music", ForYouFeedback.Kind.LESS_OFTEN, NOW - 10 * DAY)
    assertEquals(rank(weekdayHabit).single().score * ForYouFeedback.LESS_OFTEN, rank(weekdayHabit, less).single().score,
        1e-9)
  }
}
