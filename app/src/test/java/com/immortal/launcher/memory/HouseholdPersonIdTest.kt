/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Slice 5's household-stable person id: the same person on every Portal, from nothing but their name. */
class HouseholdPersonIdTest {
  @Test fun theSameNameIsTheSamePersonOnEveryPortal() {
    assertEquals(HouseholdPersonId.of("Alex Doe"), HouseholdPersonId.of("  alex   DOE "))
    assertNotEquals(HouseholdPersonId.of("Alex"), HouseholdPersonId.of("Alexa"))
  }

  @Test fun theIdIsAValidScopeAndDoesNotSpellTheName() {
    val id = HouseholdPersonId.of("Alex")!!
    MemoryScope.Person(id) // valid scope id
    assertFalse(id.contains("alex", ignoreCase = true))
    assertEquals(21, id.length)
  }

  @Test fun noNameNoId() {
    assertNull(HouseholdPersonId.of(""))
    assertNull(HouseholdPersonId.of("   "))
  }

  @Test fun aNameSharedByTwoPeopleOnThisPortalIsNobodysId() {
    val people = listOf("p1" to "Alex", "p2" to "alex", "p3" to "Sam")
    assertNull(HouseholdPersonId.resolve("p1", people))
    assertEquals(HouseholdPersonId.of("Sam"), HouseholdPersonId.resolve("p3", people))
    assertNull(HouseholdPersonId.resolve("p9", people))
  }
}
