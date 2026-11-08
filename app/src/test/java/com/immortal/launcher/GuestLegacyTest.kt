/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class GuestLegacyTest {
  private val base = GuestConfig.Settings(ssid = "Home", welcome = "Hi")

  @Test fun aTimeCheckoutBecomesTheChoice() {
    val out = GuestLegacy.fold(base, GuestLegacy.V1(checkout = "11am"))
    assertEquals("11:00", out.checkout)
    assertEquals("", out.customNotes)
  }

  @Test fun freeTextCheckoutIsKeptAsANote() {
    val out = GuestLegacy.fold(base, GuestLegacy.V1(checkout = "11:00 on Sunday"))
    assertEquals("", out.checkout)
    assertEquals(listOf("Check-out: 11:00 on Sunday"), GuestBasics.decodeNotes(out.customNotes))
  }

  @Test fun houseNoteAndEmergencyBecomeNotesAfterExistingOnes() {
    val out = GuestLegacy.fold(base.copy(customNotes = "Mine"),
        GuestLegacy.V1(houseNote = "Bins go out Tuesday", emergency = "Sam 555 0100"))
    assertEquals(listOf("Mine", "Bins go out Tuesday", "Emergency contact: Sam 555 0100"),
        GuestBasics.decodeNotes(out.customNotes))
  }

  @Test fun blankLegacyValuesChangeNothing() =
      assertEquals(base.copy(checkout = "10:00"), GuestLegacy.fold(base.copy(checkout = "10:00"), GuestLegacy.V1()))

  @Test fun foldingTwiceDoesNotDuplicate() {
    val legacy = GuestLegacy.V1(houseNote = "Bins")
    assertEquals(GuestLegacy.fold(base, legacy), GuestLegacy.fold(GuestLegacy.fold(base, legacy), legacy))
  }

  @Test fun everythingElseIsUntouched() {
    val full = base.copy(security = "wep", hiddenNetwork = true, hidePassword = false, goodToKnow = "pets")
    val out = GuestLegacy.fold(full, GuestLegacy.V1(houseNote = "x"))
    assertEquals(full.copy(customNotes = "x"), out)
  }
}
