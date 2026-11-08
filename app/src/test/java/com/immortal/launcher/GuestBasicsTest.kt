/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestBasicsTest {
  @Test fun checkoutChoicesAreNoneAndThreeCommonTimes() =
      assertEquals(listOf("", "10:00", "11:00", "12:00"), GuestBasics.CHECKOUT_CHOICES)

  @Test fun checkoutParsesLooseTimesToCanonical24h() {
    assertEquals("11:00", GuestBasics.parseCheckout("11:00"))
    assertEquals("09:30", GuestBasics.parseCheckout("9:30"))
    assertEquals("11:00", GuestBasics.parseCheckout("11am"))
    assertEquals("13:15", GuestBasics.parseCheckout("1:15 PM"))
    assertEquals("12:00", GuestBasics.parseCheckout("12 pm"))
    assertEquals("00:30", GuestBasics.parseCheckout("12:30am"))
    assertEquals("", GuestBasics.parseCheckout("  "))
    assertNull(GuestBasics.parseCheckout("11:00 on Sunday"))
    assertNull(GuestBasics.parseCheckout("25:00"))
  }

  @Test fun checkoutIsValidOnlyWhenBlankOrCanonical() {
    assertTrue(GuestBasics.isValidCheckout(""))
    assertTrue(GuestBasics.isValidCheckout("23:59"))
    assertFalse(GuestBasics.isValidCheckout("9:30"))
    assertFalse(GuestBasics.isValidCheckout("noon"))
  }

  @Test fun checkoutDisplayFollowsTheClockSetting() {
    assertEquals("No check-out", GuestBasics.checkoutLabel("", use24 = true))
    assertEquals("11:00", GuestBasics.checkoutLabel("11:00", use24 = true))
    assertEquals("11:00 AM", GuestBasics.checkoutLabel("11:00", use24 = false))
    assertEquals("12:00 PM", GuestBasics.checkoutLabel("12:00", use24 = false))
    assertEquals("12:30 AM", GuestBasics.checkoutLabel("00:30", use24 = false))
    assertEquals("2:05 PM", GuestBasics.checkoutLabel("14:05", use24 = false))
  }

  @Test fun presetsHaveStableIdsLabelsAndIcons() {
    assertEquals(listOf("shoes_off", "quiet_hours", "snacks", "coffee", "pets", "no_smoking", "towels", "street_parking"),
        GuestBasics.PRESETS.map { it.id })
    assertTrue(GuestBasics.PRESETS.all { it.label.isNotBlank() && it.icon.isNotBlank() })
  }

  @Test fun presetIdsRoundTripAndDropUnknownOnes() {
    assertEquals(listOf("shoes_off", "pets"), GuestBasics.decodePresets("shoes_off, pets,bogus,,pets"))
    assertEquals("shoes_off,pets", GuestBasics.encodePresets(listOf("shoes_off", "pets", "bogus")))
    assertEquals("", GuestBasics.encodePresets(emptyList()))
  }

  @Test fun presetsStoreInCatalogOrderWhateverTheTapOrder() =
      assertEquals("quiet_hours,pets", GuestBasics.encodePresets(listOf("pets", "quiet_hours")))

  @Test fun customNotesRoundTripWithoutTheSeparator() {
    val stored = GuestBasics.encodeNotes(listOf("Bins out Tuesday", "Use the a|b door", " ", "Bins out Tuesday"))
    assertEquals("Bins out Tuesday|Use the a/b door", stored)
    assertEquals(listOf("Bins out Tuesday", "Use the a/b door"), GuestBasics.decodeNotes(stored))
    assertEquals(emptyList<String>(), GuestBasics.decodeNotes(""))
  }

  @Test fun customNotesAreCapped() {
    val many = (1..10).map { "Note $it" }
    assertEquals(GuestBasics.MAX_NOTES, GuestBasics.decodeNotes(GuestBasics.encodeNotes(many)).size)
    assertEquals(GuestBasics.MAX_NOTE_TYPED, GuestBasics.cleanNote("x".repeat(80)).length)
  }

  @Test fun customNotesValidation() {
    assertTrue(GuestBasics.isValidNotes(""))
    assertTrue(GuestBasics.isValidNotes("One|Two"))
    assertFalse(GuestBasics.isValidNotes("a\nb"))
    assertFalse(GuestBasics.isValidNotes("a||b"))
    assertFalse(GuestBasics.isValidNotes((1..7).joinToString("|") { "n$it" }))
    assertFalse(GuestBasics.isValidNotes("x".repeat(GuestConfig.MAX_TEXT + 1)))
  }

  @Test fun contactOptionsAndCardLines() {
    assertEquals(listOf("none", "call_portal", "text", "ask"), GuestBasics.CONTACTS.map { it.id })
    assertEquals("Need anything? Call us on the Portal.", GuestBasics.contactLine("call_portal", ""))
    assertEquals("Need anything? Text us.", GuestBasics.contactLine("text", ""))
    assertEquals("Need anything? Just ask.", GuestBasics.contactLine("ask", ""))
    assertNull(GuestBasics.contactLine("none", ""))
    assertEquals("Questions? Ask Sam.", GuestBasics.contactLine("none", "Sam"))
    assertEquals("Need anything? Text us. Questions? Ask Sam.", GuestBasics.contactLine("text", " Sam "))
  }

  @Test fun contactNameIsShortAndSingleLine() {
    assertTrue(GuestBasics.isValidContactName(""))
    assertTrue(GuestBasics.isValidContactName("Sam Lee"))
    assertFalse(GuestBasics.isValidContactName("x".repeat(GuestBasics.MAX_CONTACT_NAME + 1)))
    assertFalse(GuestBasics.isValidContactName("a\nb"))
  }

  @Test fun welcomeDefaultAndSamples() {
    assertEquals("Welcome! Make yourself at home.", GuestBasics.DEFAULT_WELCOME)
    assertEquals(GuestBasics.DEFAULT_WELCOME, GuestBasics.WELCOME_SAMPLES.first())
    assertEquals(4, GuestBasics.WELCOME_SAMPLES.size)
    assertTrue(GuestBasics.WELCOME_SAMPLES.all { it.length <= GuestBasics.MAX_WELCOME })
    assertEquals(GuestBasics.MAX_WELCOME, GuestBasics.cleanWelcome("y".repeat(200)).length)
    assertEquals("Hi there", GuestBasics.cleanWelcome("Hi\nthere"))
  }

  @Test fun doorCodeIsFourToEightDigits() {
    assertTrue(GuestDoorCode.isValid("4821"))
    assertTrue(GuestDoorCode.isValid("12345678"))
    assertFalse(GuestDoorCode.isValid("482"))
    assertFalse(GuestDoorCode.isValid("123456789"))
    assertFalse(GuestDoorCode.isValid("48a1"))
    assertFalse(GuestDoorCode.isValid("4821#"))
    assertFalse(GuestDoorCode.isValid("٤٨٢١"))
    assertEquals("48219999", GuestDoorCode.clean("48-21x9999999"))
  }

  @Test fun legacyDoorCodesAreKeptWhenSingleLine() {
    assertTrue(GuestDoorCode.isStorable("4821#"))
    assertTrue(GuestDoorCode.isStorable("4821"))
    assertFalse(GuestDoorCode.isStorable(""))
    assertFalse(GuestDoorCode.isStorable("a\nb"))
  }
}
