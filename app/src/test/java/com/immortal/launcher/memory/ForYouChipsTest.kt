/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the "For you right now" row shows: at most three chips, the best one marked, each explaining itself. */
class ForYouChipsTest {
  private fun s(surface: String, score: Double) = ForYouRanker.Suggestion(surface, score, opens = 5)

  private val apps = mapOf(
      "app:com.spotify.music" to ForYouChips.Target("app:com.spotify.music", "Spotify", null),
      "app:com.portal.assistant" to ForYouChips.Target("jarvis", "Jarvis", "jarvis"),
  )

  private fun resolve(surface: String) = ForYouChips.screenTarget(surface) ?: apps[surface]

  @Test fun atMostThreeAndOnlyTheFirstIsTheTopChip() {
    val chips = ForYouChips.build(
        listOf(s("screen:music", .5), s("screen:calls", .4), s("app:com.spotify.music", .3), s("jarvis", .2)),
        household = false, resolve = ::resolve)
    assertEquals(listOf("Music", "Calls", "Spotify"), chips.map { it.title })
    assertEquals(listOf(true, false, false), chips.map { it.top })
  }

  @Test fun theSameThingCountedTwoWaysIsOneChip() {
    val chips = ForYouChips.build(listOf(s("jarvis", .5), s("app:com.portal.assistant", .4)), false, ::resolve)
    assertEquals(1, chips.size)
    assertEquals("jarvis", chips.single().surface)
  }

  @Test fun anythingThatCannotBeOpenedAnyMoreIsSkipped() {
    val chips = ForYouChips.build(listOf(s("app:com.gone", .9), s("screen:music", .5)), false, ::resolve)
    assertEquals(listOf("screen:music"), chips.map { it.surface })
    assertTrue(chips.single().top)
  }

  @Test fun everyChipSaysWhyAndHouseholdChipsDoNotSayYou() {
    val mine = ForYouChips.build(listOf(s("screen:music", .5), s("app:com.spotify.music", .4)), false, ::resolve)
    assertEquals("You usually start music around now", mine[0].why)
    assertEquals("You usually open it around now", mine[1].why)
    val ours = ForYouChips.build(listOf(s("screen:music", .5), s("screen:calls", .4)), true, ::resolve)
    assertTrue(ours.all { it.why.isNotBlank() && !it.why.contains("You") })
  }

  @Test fun theScreensHaveFixedTargets() {
    assertEquals("Music", ForYouChips.screenTarget("screen:music")?.title)
    assertEquals("Calls", ForYouChips.screenTarget("screen:calls")?.title)
    assertEquals("Ask Jarvis", ForYouChips.screenTarget("jarvis")?.title)
    assertNull(ForYouChips.screenTarget("screen:settings"))
    assertNull(ForYouChips.screenTarget("app:com.spotify.music"))
  }

  @Test fun theRowSaysWhoItIsFor() {
    assertEquals("For you right now", ForYouChips.heading(household = false))
    assertEquals("Popular here right now", ForYouChips.heading(household = true))
  }

  @Test fun theLearningSwitchShowsTheRealSettingNotTheOperatorForce() {
    assertEquals(false, KnowsLearning.checked(settingOn = false, paused = false))
    assertEquals(false, KnowsLearning.checked(settingOn = true, paused = true))
    assertEquals(true, KnowsLearning.checked(settingOn = true, paused = false))
    assertEquals(KnowsLearning.Change(turnSettingOn = true, paused = false), KnowsLearning.change(on = true, settingOn = false))
    assertEquals(KnowsLearning.Change(turnSettingOn = false, paused = false), KnowsLearning.change(on = true, settingOn = true))
    assertEquals(KnowsLearning.Change(turnSettingOn = false, paused = true), KnowsLearning.change(on = false, settingOn = true))
  }
}
