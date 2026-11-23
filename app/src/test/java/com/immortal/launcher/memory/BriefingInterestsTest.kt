/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.BriefingInputs
import com.immortal.launcher.BriefingPart
import com.immortal.launcher.BriefingPersonalBuilder
import com.immortal.launcher.BriefingPersonalInputs
import com.immortal.launcher.BriefingSectionKind
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The personal briefing's "Your teams & markets" line. Pure. */
class BriefingInterestsTest {
  /** Monday 23 November 2026, 7 am in California (10 am in New York: the market is open). */
  private val monday = MemoryFixtures.NOW
  private val la = ZoneId.of("America/Los_Angeles")
  private val hour = 3_600_000L

  private fun side(name: String, score: String? = null) = SportsCard.Side(name, name.take(3).uppercase(), "", score)
  private fun game(phase: SportsCard.Phase, status: String, us: String? = null, them: String? = null) =
      SportsCard.Game("nba.gsw", phase, status, side("Warriors", us), side("Celtics", them), null, null)

  @Test fun teamsReadAsOneLine() {
    assertEquals("Your teams: Warriors vs Celtics, tonight at 5:30",
        BriefingInterests.teams(listOf(game(SportsCard.Phase.NEXT, "Tonight · 5:30"))))
    assertEquals("Your teams: Warriors vs Celtics, Sun at 5:30",
        BriefingInterests.teams(listOf(game(SportsCard.Phase.NEXT, "Sun · 5:30"))))
    assertEquals("Your teams: Warriors 78–81 Celtics, live",
        BriefingInterests.teams(listOf(game(SportsCard.Phase.LIVE, "Live · 3:12 - 3rd", "78", "81"))))
    assertEquals("Your teams: Warriors vs Celtics, live now",
        BriefingInterests.teams(listOf(game(SportsCard.Phase.LIVE, "Live · updating"))))
    val arsenal = SportsCard.Game("epl.ars", SportsCard.Phase.FINAL, "Final", side("Arsenal", "2"), side("Spurs", "1"), null, null)
    assertEquals("Your teams: Warriors vs Celtics, tonight at 5:30; Arsenal 2–1 Spurs, final",
        BriefingInterests.teams(listOf(game(SportsCard.Phase.NEXT, "Tonight · 5:30"), arsenal)))
    assertNull(BriefingInterests.teams(listOf(SportsCard.Game("x", SportsCard.Phase.NONE, "No games scheduled", side("A"), null, null, null))))
    assertNull(BriefingInterests.teams(emptyList()))
  }

  @Test fun marketsSayTodaysMovesOnceOpenElseWhenTheyOpen() {
    val moves = listOf(ForYouInterests.Move("NVDA", 2.44), ForYouInterests.Move("AMD", -1.06))
    assertEquals("Your markets: NVDA +2.4%, AMD −1.1%", BriefingInterests.markets(listOf("NVDA", "AMD"), moves, monday, la, false))
    assertEquals("Your markets: NVDA, AMD · open at 6:30 am",
        BriefingInterests.markets(listOf("NVDA", "AMD"), emptyList(), monday - 2 * hour, la, false))
    assertEquals("Your markets: NVDA, AMD · open at 6:30",
        BriefingInterests.markets(listOf("NVDA", "AMD"), emptyList(), monday - 2 * hour, la, true))
    assertEquals("Your markets: NVDA", BriefingInterests.markets(listOf("NVDA"), emptyList(), monday - 2 * 24 * hour, la, false))
    assertNull(BriefingInterests.markets(emptyList(), moves, monday, la, false))
  }

  @Test fun thePersonalBriefingGetsTheLineOnlyWhenThereIsOne() {
    val inputs = BriefingInputs(nowMs = monday, zone = TimeZone.getTimeZone(la))
    val plain = BriefingPersonalBuilder.build(inputs, BriefingPersonalInputs("Alex", hidePrivate = false, BriefingPart.Ready(null)))
    assertTrue(plain.sections.none { it.kind == BriefingSectionKind.INTERESTS })
    val lines = listOf("Your teams: Warriors vs Celtics, tonight at 5:30", "Your markets: NVDA +2.4%")
    val withLine = BriefingPersonalBuilder.build(inputs,
        BriefingPersonalInputs("Alex", hidePrivate = false, BriefingPart.Ready(null), interests = lines))
    val section = withLine.sections.single { it.kind == BriefingSectionKind.INTERESTS }
    assertEquals(BriefingInterests.HEADING, section.heading)
    assertEquals(lines, section.lines)
    assertEquals(plain.sections, withLine.sections - section)
  }
}
