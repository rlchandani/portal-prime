/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Room names as the user says them, matched to the Portals Hearthpane knows. */
class RoomMatchTest {
  private val rooms = listOf("Living Area", "Kitchen", "Kids Room", "Office", "Office Mini")

  @Test fun caseArticlesAndPortalSuffixDontMatter() {
    assertEquals("Living Area", RoomMatch.find("the living area", rooms))
    assertEquals("Kitchen", RoomMatch.find("Kitchen Portal", rooms))
    assertEquals("Kids Room", RoomMatch.find("kids room", rooms))
    assertEquals("Kids Room", RoomMatch.find("kids", rooms))
  }

  @Test fun anExactNameWinsOverALongerOneAndAnAmbiguousPrefixMatchesNothing() {
    assertEquals("Office", RoomMatch.find("office", rooms))
    assertNull(RoomMatch.find("of", rooms))
    assertNull(RoomMatch.find("garage", rooms))
    assertNull(RoomMatch.find("  ", rooms))
  }

  @Test fun everyRoomWords() {
    for (w in listOf(null, "", "all", "everyone", "every room", "to everyone", "the whole house")) assertTrue("$w", RoomMatch.meansAll(w))
    assertFalse(RoomMatch.meansAll("kitchen"))
  }
}
