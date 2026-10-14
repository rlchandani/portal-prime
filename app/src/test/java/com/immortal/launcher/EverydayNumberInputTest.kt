/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EverydayNumberInputTest {
  @Test fun numberRejectsBlankAndWhitespacePaddedInput() {
    listOf("", " ", "\t", "\n", " 1", "1 ", "1\n", "1 2").forEach { value ->
      assertNull(value, ApprovedToolsEverydayValidation.number(value, 0, 100))
    }
  }

  @Test fun numberRejectsSignsDecimalsAndOtherNumericSyntax() {
    listOf("+1", "-1", "-0", "+0", "1.0", ".5", "1e2", "0x10", "1_0", "ten").forEach { value ->
      assertNull(value, ApprovedToolsEverydayValidation.number(value, Int.MIN_VALUE, Int.MAX_VALUE))
    }
  }

  @Test fun numberAcceptsOnlyAsciiDigits() {
    listOf("\u0661", "\u06f1", "\uff11", "\u0967", "1\u0662", "\u00b2").forEach { value ->
      assertNull(value, ApprovedToolsEverydayValidation.number(value, 0, 100))
    }
    assertEquals(12, ApprovedToolsEverydayValidation.number("12", 0, 100))
  }

  @Test fun numberRejectsOverflowButAcceptsLargestRepresentableInteger() {
    assertEquals(Int.MAX_VALUE,
        ApprovedToolsEverydayValidation.number("2147483647", 0, Int.MAX_VALUE))
    listOf("2147483648", "4294967296", "999999999999999999999999999999").forEach { value ->
      assertNull(value, ApprovedToolsEverydayValidation.number(value, 0, Int.MAX_VALUE))
    }
  }

  @Test fun numberUsesInclusiveRangeAndAcceptsLeadingZeroPadding() {
    assertEquals(1, ApprovedToolsEverydayValidation.number("1", 1, 12))
    assertEquals(12, ApprovedToolsEverydayValidation.number("12", 1, 12))
    assertNull(ApprovedToolsEverydayValidation.number("0", 1, 12))
    assertNull(ApprovedToolsEverydayValidation.number("13", 1, 12))
    assertEquals(7, ApprovedToolsEverydayValidation.number("0007", 1, 12))
    assertEquals(0, ApprovedToolsEverydayValidation.number("0000", 0, 0))
    assertEquals(7, ApprovedToolsEverydayValidation.number("7", 7, 7))
    assertNull(ApprovedToolsEverydayValidation.number("6", 7, 7))
  }

  @Test fun stepNumberUsesValidInputAndReturnsUnpaddedSingleSteps() {
    assertEquals("8", ApprovedToolsEverydayValidation.stepNumber("007", 2, 1, 12, true))
    assertEquals("6", ApprovedToolsEverydayValidation.stepNumber("007", 11, 1, 12, false))
    assertEquals("1", ApprovedToolsEverydayValidation.stepNumber("0", 9, 0, 12, true))
  }

  @Test fun stepNumberClampsBothEndpointsByDefault() {
    assertEquals("12", ApprovedToolsEverydayValidation.stepNumber("12", 5, 1, 12, true))
    assertEquals("1", ApprovedToolsEverydayValidation.stepNumber("1", 5, 1, 12, false))
    assertEquals("12", ApprovedToolsEverydayValidation.stepNumber("11", 5, 1, 12, true))
    assertEquals("1", ApprovedToolsEverydayValidation.stepNumber("2", 5, 1, 12, false))
    assertEquals("7", ApprovedToolsEverydayValidation.stepNumber("7", 7, 7, 7, true))
    assertEquals("7", ApprovedToolsEverydayValidation.stepNumber("7", 7, 7, 7, false))
  }

  @Test fun stepNumberWrapsBothEndpointsAndStillStepsWithinRange() {
    assertEquals("1", ApprovedToolsEverydayValidation.stepNumber("12", 5, 1, 12, true, wrap = true))
    assertEquals("12", ApprovedToolsEverydayValidation.stepNumber("1", 5, 1, 12, false, wrap = true))
    assertEquals("8", ApprovedToolsEverydayValidation.stepNumber("7", 5, 1, 12, true, wrap = true))
    assertEquals("6", ApprovedToolsEverydayValidation.stepNumber("7", 5, 1, 12, false, wrap = true))
    assertEquals("7", ApprovedToolsEverydayValidation.stepNumber("7", 7, 7, 7, true, wrap = true))
    assertEquals("7", ApprovedToolsEverydayValidation.stepNumber("7", 7, 7, 7, false, wrap = true))
  }

  @Test fun stepNumberCoercesInvalidInputsFallbackBeforeStepping() {
    listOf("", " ", "-1", "+1", "1.5", "\uff11", "2147483648", "0", "13").forEach { value ->
      assertEquals(value, "6", ApprovedToolsEverydayValidation.stepNumber(value, 5, 1, 12, true))
      assertEquals(value, "4", ApprovedToolsEverydayValidation.stepNumber(value, 5, 1, 12, false))
    }
    assertEquals("2", ApprovedToolsEverydayValidation.stepNumber("", Int.MIN_VALUE, 1, 12, true))
    assertEquals("11", ApprovedToolsEverydayValidation.stepNumber("", Int.MAX_VALUE, 1, 12, false))
    assertEquals("1", ApprovedToolsEverydayValidation.stepNumber("", Int.MIN_VALUE, 1, 12, false))
    assertEquals("12", ApprovedToolsEverydayValidation.stepNumber("", Int.MAX_VALUE, 1, 12, true))
    assertEquals("12", ApprovedToolsEverydayValidation.stepNumber("", Int.MIN_VALUE, 1, 12, false, wrap = true))
    assertEquals("1", ApprovedToolsEverydayValidation.stepNumber("", Int.MAX_VALUE, 1, 12, true, wrap = true))
  }

  @Test fun stepNumberHandlesFullIntegerBoundariesWithoutOverflow() {
    assertEquals("2147483647", ApprovedToolsEverydayValidation.stepNumber(
        "2147483647", 0, Int.MIN_VALUE, Int.MAX_VALUE, true))
    assertEquals("2147483646", ApprovedToolsEverydayValidation.stepNumber(
        "2147483647", 0, Int.MIN_VALUE, Int.MAX_VALUE, false))
    assertEquals("-2147483648", ApprovedToolsEverydayValidation.stepNumber(
        "", Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, false))
    assertEquals("-2147483647", ApprovedToolsEverydayValidation.stepNumber(
        "", Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, true))
    assertEquals("-2147483648", ApprovedToolsEverydayValidation.stepNumber(
        "2147483647", 0, Int.MIN_VALUE, Int.MAX_VALUE, true, wrap = true))
    assertEquals("2147483647", ApprovedToolsEverydayValidation.stepNumber(
        "", Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, false, wrap = true))
    // Signed input is invalid even when it equals an endpoint; the fallback must win.
    assertEquals("1", ApprovedToolsEverydayValidation.stepNumber(
        "-2147483648", 0, Int.MIN_VALUE, Int.MAX_VALUE, true))
  }

  @Test fun countdownDraftAcceptsRealDatesAndRejectsImpossibleDates() {
    listOf(
        Triple("01", "01", "2000"), Triple("12", "31", "2100"),
        Triple("2", "29", "2000"), Triple("02", "29", "2024"),
    ).forEach { (month, day, year) ->
      assertNull("$month/$day/$year", ApprovedToolsEverydayValidation.countdownDraftError(
          "Trip", month, day, year, false))
    }
    listOf(
        Triple("4", "31", "2026"), Triple("2", "30", "2024"),
        Triple("2", "29", "2026"), Triple("2", "29", "2100"),
    ).forEach { (month, day, year) ->
      assertNotNull("$month/$day/$year", ApprovedToolsEverydayValidation.countdownDraftError(
          "Trip", month, day, year, false))
    }
  }

  @Test fun countdownDraftRejectsMalformedAndOutOfRangeMonthOrDay() {
    listOf("", " ", "+1", "-1", "1.0", "\uff11", "2147483648", "0", "13").forEach { month ->
      assertNotNull(month, ApprovedToolsEverydayValidation.countdownDraftError(
          "Trip", month, "1", "2026", false))
      assertNotNull(month, ApprovedToolsEverydayValidation.countdownDraftError(
          "Trip", month, "1", "", true))
    }
    listOf("", " ", "+1", "-1", "1.0", "\u0661", "2147483648", "0", "32").forEach { day ->
      assertNotNull(day, ApprovedToolsEverydayValidation.countdownDraftError(
          "Trip", "1", day, "2026", false))
      assertNotNull(day, ApprovedToolsEverydayValidation.countdownDraftError(
          "Trip", "1", day, "", true))
    }
  }

  @Test fun countdownDraftRequiresValidYearOnlyForNonYearlyEvents() {
    listOf("", " ", "+2024", "-2024", "2024.0", "\uff12\uff10\uff12\uff14", "2147483648", "0", "1999", "2101").forEach { year ->
      assertNull(year, ApprovedToolsEverydayValidation.countdownDraftError(
          "Birthday", "2", "29", year, true))
      assertNotNull(year, ApprovedToolsEverydayValidation.countdownDraftError(
          "Birthday", "1", "1", year, false))
    }
    assertNull(ApprovedToolsEverydayValidation.countdownDraftError(
        "Birthday", "2", "29", "2026", true))
    assertNull(ApprovedToolsEverydayValidation.countdownDraftError(
        "Birthday", "2", "29", "002024", false))
    assertNotNull(ApprovedToolsEverydayValidation.countdownDraftError(
        "Birthday", "2", "30", "", true))
    assertNotNull(ApprovedToolsEverydayValidation.countdownDraftError(
        "Birthday", "4", "31", "garbage", true))
  }

  @Test fun countdownDraftEnforcesExistingTrimmedNameLimits() {
    listOf("", " ", "\t\n").forEach { name ->
      assertEquals("Enter a name.", ApprovedToolsEverydayValidation.countdownDraftError(
          name, "1", "1", "2026", false))
    }
    assertNull(ApprovedToolsEverydayValidation.countdownDraftError(
        "  Birthday  ", "1", "1", "", true))
    assertNull(ApprovedToolsEverydayValidation.countdownDraftError(
        "  " + "x".repeat(80) + "  ", "1", "1", "2026", false))
    assertEquals("Use a name with 80 characters or fewer.",
        ApprovedToolsEverydayValidation.countdownDraftError(
            "  " + "x".repeat(81) + "  ", "1", "1", "2026", false))
    assertNotNull(ApprovedToolsEverydayValidation.countdownDraftError(
        "x".repeat(81), "1", "1", "ignored", true))
  }

  @Test fun numberTextSaverConvertsLegacyIntegerDraftsToStrings() {
    listOf(0, 7, 59, 12, 31, 2026).forEach { value ->
      assertEquals(value.toString(), EverydayNumberTextSaver.restore(value))
    }
  }

  @Test fun numberTextSaverPreservesExactStringDraftsAndRejectsUnsupportedTypes() {
    listOf("", " ", "0007", " 07 ", "-1", "1.5", "not a number").forEach { value ->
      assertEquals(value, value, EverydayNumberTextSaver.restore(value))
    }
    listOf<Any>(true, false, 0L, 2026L).forEach { value ->
      assertNull(value.toString(), EverydayNumberTextSaver.restore(value))
    }
  }
}
