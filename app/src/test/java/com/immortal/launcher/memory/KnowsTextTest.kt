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

/** The screen's words from the mock once memory follows the person between Portals (slice 5). */
class KnowsTextTest {
  @Test fun forgetSaysItReachesEveryPortal() {
    assertEquals(KnowsText.Toast("Forgot AMD", "Removed from every Portal"), KnowsText.forgot("AMD"))
    assertEquals(KnowsText.Toast("Forgot everything about Alex", "On all Portals · conversation history too"),
        KnowsText.forgotAll(household = false, firstName = "Alex"))
    assertEquals("Forgot everything about the household", KnowsText.forgotAll(true, null).text)
  }

  @Test fun pauseSaysWhatItKeeps() {
    assertEquals(KnowsText.Toast("Learning paused", "Jarvis keeps what it knows but learns nothing new"),
        KnowsText.learning(false))
    assertEquals("Learning on", KnowsText.learning(true).text)
  }

  @Test fun theBannerSpeaksOfTheseLearnedPortals() {
    assertEquals("Learned automatically from what Alex says to Jarvis and opens on these Portals. Kept on your " +
        "Portals, only for you. Dashed items are still being learned.", KnowsText.banner(false, "Alex"))
    assertTrue(KnowsText.banner(true, null).contains("Shared by everyone in the household"))
    assertEquals("Jarvis learns as you talk and use your Portals. There's nothing to set up.", KnowsText.EMPTY)
  }

  @Test fun aFactLastSeenOnAnotherPortalSaysWhere() {
    val scope = MemoryScope.Person(HouseholdPersonId.of("Alex")!!)
    val p = MemoryLearner.learn(MemoryProfile(scope), MemoryFixtures.observe("l1", "Keep an eye on AMD"), "l1",
        "Living Area", NOW - DAY).profile
    val there = KnowsRows.rows(p, NOW, ZoneOffset.UTC, here = "Kitchen").single().detail
    assertTrue(there, there.endsWith(" · Living Area"))
    val here = KnowsRows.rows(p, NOW, ZoneOffset.UTC, here = "Living Area").single().detail
    assertFalse(here, here.contains("Living Area"))
    assertEquals(here, KnowsRows.rows(p, NOW, ZoneOffset.UTC).single().detail)
  }
}
