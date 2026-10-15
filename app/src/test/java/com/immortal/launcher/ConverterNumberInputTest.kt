/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.text.DecimalFormatSymbols
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Exercise the converter's actual validation boundary, including the active device locale. */
class ConverterNumberInputTest {
  @Test fun usAcceptsGroupedResultsIncludingSignedFractions() = withLocale(Locale.US) {
    assertValue("1,000", 1000.0)
    assertValue("1,234.5", 1234.5)
    assertValue("-1,234.5", -1234.5)
    assertValue("+1,234.5", 1234.5)
    assertValue("12,345,678.9", 12345678.9)
  }

  @Test fun germanAcceptsLocalizedFractionsAndGrouping() = withLocale(Locale.GERMANY) {
    assertValue("1.234,5", 1234.5)
    assertValue("-1.234,5", -1234.5)
    assertValue("1234,5", 1234.5)
    assertValue(",5", 0.5)
  }

  @Test fun germanGroupingTakesPrecedenceOverStandardDecimalSyntax() = withLocale(Locale.GERMANY) {
    assertValue("1.234", 1234.0)
    assertValue("-1.234", -1234.0)
    assertValue("1.000", 1000.0)
  }

  @Test fun frenchUsesTheRuntimeGroupingSymbolAndDecimalComma() = withLocale(Locale.FRANCE) {
    // JDK and Android locale data may choose NBSP or narrow NBSP; use the actual symbol.
    val grouping = DecimalFormatSymbols.getInstance(Locale.FRANCE).groupingSeparator
    assertValue("1${grouping}234,5", 1234.5)
    assertValue("-1${grouping}234,5", -1234.5)
    assertValue("12${grouping}345${grouping}678,9", 12345678.9)
    assertValue("1234,5", 1234.5)
    assertRejected("1${grouping}23,5", "1${grouping}234,5${grouping}6")
  }

  @Test fun acceptsUnicodeDecimalDigitsInLocalizedNumbers() = withLocale(Locale.US) {
    assertValue("١,٢٣٤.٥", 1234.5)
    assertValue("-١,٢٣٤.٥", -1234.5)
    assertValue("१२३.५", 123.5)
    assertValue("１２３.５", 123.5)
  }

  @Test fun acceptsArabicDigitsWithLocalizedSeparators() = withLocale(Locale.forLanguageTag("ar-EG")) {
    val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
    assertValue("١${symbols.groupingSeparator}٢٣٤${symbols.decimalSeparator}٥", 1234.5)
    assertValue("١٢٣٤${symbols.decimalSeparator}٥", 1234.5)
  }

  @Test fun acceptsTheLocaleMinusSign() = withLocale(Locale.forLanguageTag("sv-SE")) {
    val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
    assertValue("${symbols.minusSign}12${symbols.decimalSeparator}5", -12.5)
    assertValue("${symbols.minusSign}0${symbols.decimalSeparator}0", -0.0)
  }

  @Test fun usRejectsMalformedGroupingInsteadOfSilentlyRemovingCommas() = withLocale(Locale.US) {
    assertRejected(
        "1,2", "1,23,456", "1234,567", "1,,234", ",123", "123,", "1,234.5,6",
    )
  }

  @Test fun retainsPlainFiniteValuesAndStandardScientificNotation() = withLocale(Locale.US) {
    assertValue("0", 0.0)
    assertValue("-0.0", -0.0)
    assertValue("+1.5", 1.5)
    assertValue(".5", 0.5)
    assertValue("1e3", 1000.0)
    assertValue("-1.25E-3", -0.00125)
    assertValue("  \t1,234.5\n ", 1234.5)
    assertValue("  \t.5\n ", 0.5)
    assertValue("0001234.5", 1234.5)
    assertValue("0".repeat(4096) + "1", 1.0)
  }

  @Test fun standardDecimalAndScientificFallbackRemainUsableInCommaLocales() = withLocale(Locale.GERMANY) {
    assertValue(".5", 0.5)
    assertValue("+1.5", 1.5)
    assertValue("1e3", 1000.0)
    // This is not canonical German grouping, so the existing standard decimal syntax applies.
    assertValue("1.23", 1.23)
    assertRejected("1.23.456", "1.234,5.6")
  }

  @Test fun preservesNegativeZeroAfterLocalizedParsing() = withLocale(Locale.GERMANY) {
    assertValue("-0", -0.0)
    assertValue("-0,0", -0.0)
    assertValue("-0,000", -0.0)
  }

  @Test fun rejectsTrailingTokensExtraDecimalsAndUnsupportedSeparators() = withLocale(Locale.US) {
    assertRejected(
        "", " ", "1,234kg", "123abc", "1.2.3", "1,234.5.6", "1 234", "1\t234",
        "1_234", "--1", "+-1", ".", ",", "1,234e3",
    )
  }

  @Test fun rejectsNonfiniteValuesAndOverflowInEitherSyntax() = withLocale(Locale.US) {
    assertRejected("NaN", "Infinity", "+Infinity", "-Infinity", "∞", "1e309", "-1e309")
    assertRejected("9".repeat(400), "999," + "999,".repeat(132) + "999")
  }

  @Test fun rejectsOverflowWithLocalizedDecimalComma() = withLocale(Locale.GERMANY) {
    assertRejected("9".repeat(400) + ",5", "999." + "999.".repeat(132) + "999,5")
  }

  private fun assertValue(input: String, expected: Double) {
    val actual = ApprovedToolsEverydayValidation.converterValue(input)
    assertNotNull("Expected a converter value for '$input'", actual)
    assertEquals("Wrong converter value for '$input'", expected, actual!!, 0.0)
    if (expected == 0.0) {
      assertEquals("Wrong zero sign for '$input'", expected.toRawBits(), actual.toRawBits())
    }
  }

  private fun assertRejected(vararg inputs: String) {
    inputs.forEach { input ->
      assertNull("Expected rejection of '$input'", ApprovedToolsEverydayValidation.converterValue(input))
    }
  }

  private fun withLocale(locale: Locale, block: () -> Unit) {
    val previous = Locale.getDefault()
    val previousDisplay = Locale.getDefault(Locale.Category.DISPLAY)
    val previousFormat = Locale.getDefault(Locale.Category.FORMAT)
    try {
      Locale.setDefault(locale)
      block()
    } finally {
      Locale.setDefault(previous)
      Locale.setDefault(Locale.Category.DISPLAY, previousDisplay)
      Locale.setDefault(Locale.Category.FORMAT, previousFormat)
    }
  }
}
