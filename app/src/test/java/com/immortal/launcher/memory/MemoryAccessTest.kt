/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryAccess.Reason
import com.immortal.launcher.memory.MemoryAccess.Role
import com.immortal.launcher.memory.MemoryAccess.Seen
import org.junit.Assert.assertEquals
import org.junit.Test

/** Whose memory a moment belongs to (plan §2.2) with the recommended defaults (D1 household on, D2 teens off). */
class MemoryAccessTest {
  private fun decide(seen: Seen, enabled: Boolean = true, guest: Boolean = false, kids: Boolean = false) =
      MemoryAccess.decide(MemoryAccess.Inputs(enabled, guest, kids, seen))

  private val alex = Seen.One("p1", "Alex Example", Role.ADULT, othersNear = false)

  @Test fun offUntilTheSwitchIsOn() = assertEquals(MemoryAccess.Off(Reason.DISABLED), decide(alex, enabled = false))

  @Test fun guestModeAndKidsModeLearnNothing() {
    assertEquals(MemoryAccess.Off(Reason.GUEST), decide(alex, guest = true))
    assertEquals(MemoryAccess.Off(Reason.CHILD), decide(Seen.Nobody, kids = true))
  }

  @Test fun aRecognisedChildAnywhereInViewTurnsItOff() {
    assertEquals(MemoryAccess.Off(Reason.CHILD), decide(Seen.One("k", "Kid", Role.KID, false)))
    assertEquals(MemoryAccess.Off(Reason.CHILD), decide(Seen.Several(listOf(Role.ADULT, Role.KID))))
  }

  @Test fun teensAreOffByDefault() {
    assertEquals(MemoryAccess.Off(Reason.TEEN), decide(Seen.One("t", "Teen", Role.TEEN, false)))
    assertEquals(MemoryAccess.Off(Reason.TEEN), decide(Seen.Several(listOf(Role.ADULT, Role.TEEN))))
  }

  @Test fun anAdultGetsTheirOwnScopeWithOnlyTheirFirstName() {
    assertEquals(MemoryAccess.On(MemoryScope.Person("p1"), "Alex", false), decide(alex))
    assertEquals(MemoryAccess.On(MemoryScope.Person("p1"), "Alex", true), decide(alex.copy(othersNear = true)))
  }

  @Test fun nobodyOrSeveralAdultsIsTheHousehold() {
    assertEquals(MemoryAccess.On(MemoryScope.Household, null, false), decide(Seen.Nobody))
    assertEquals(MemoryAccess.On(MemoryScope.Household, null, true), decide(Seen.Several(listOf(Role.ADULT, Role.ADULT))))
  }

  @Test fun anOddPersonIdFallsBackToTheHousehold() =
      assertEquals(MemoryScope.Household, (decide(alex.copy(personId = "bad id/..")) as MemoryAccess.On).scope)
}
