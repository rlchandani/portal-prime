/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.math.BigInteger
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * Accept the current locale's decimal digits, decimal separator and canonical integer grouping.
 * Localized input takes precedence (German "1.234" is 1234); otherwise retain the converter's
 * standard finite Double syntax, including dot decimals and scientific notation. Interior spaces
 * are accepted only when they are the locale's exact grouping separator. Scientific notation and
 * formatter affixes such as bidi marks are not localized here.
 */
internal fun parseConverterNumber(input: String, locale: Locale = Locale.getDefault()): Double? {
  val text = input.trim()
  if (text.isEmpty()) return null
  return parseLocalizedConverterNumber(text, locale)
      ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }
}

private fun parseLocalizedConverterNumber(input: String, locale: Locale): Double? {
  val symbols = DecimalFormatSymbols.getInstance(locale)
  val negative = input.first() == '-' || input.first() == symbols.minusSign
  val unsigned = if (negative || input.first() == '+') input.substring(1) else input
  if (unsigned.isEmpty()) return null

  val decimalIndex = unsigned.indexOf(symbols.decimalSeparator)
  if (decimalIndex != unsigned.lastIndexOf(symbols.decimalSeparator)) return null
  val integer = if (decimalIndex < 0) unsigned else unsigned.substring(0, decimalIndex)
  val fraction = if (decimalIndex < 0) "" else unsigned.substring(decimalIndex + 1)
  val groupedInteger = normalizeConverterDigits(integer, symbols.groupingSeparator) ?: return null
  val integerDigits = groupedInteger.filter { it != symbols.groupingSeparator }
  val fractionDigits = normalizeConverterDigits(fraction) ?: return null
  if (integerDigits.isEmpty() && fractionDigits.isEmpty()) return null

  val normalized = buildString {
    if (negative) append('-')
    append(integerDigits.ifEmpty { "0" })
    if (decimalIndex >= 0) {
      append('.')
      append(fractionDigits)
    }
  }
  // Reject overflow before constructing a BigInteger for grouping validation. Double parsing also
  // preserves negative zero, whereas NumberFormat's integral parse can erase its sign.
  val value = normalized.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null

  if (symbols.groupingSeparator in groupedInteger) {
    // Java number parsing is lenient about grouping ("1,2" can become 12). Compare the entire
    // integer with the same locale formatter used for results, normalizing only decimal digits.
    // Strip leading zeroes before BigInteger; canonical grouped input still must match exactly.
    val significantDigits = integerDigits.trimStart('0').ifEmpty { "0" }
    val canonical = NumberFormat.getNumberInstance(locale).format(BigInteger(significantDigits))
    val canonicalInteger = normalizeConverterDigits(canonical, symbols.groupingSeparator) ?: return null
    if (groupedInteger != canonicalInteger) return null
  }
  return value
}

/** Normalize BMP Unicode decimal digits; every other character must be the allowed separator. */
private fun normalizeConverterDigits(text: String, grouping: Char? = null): String? {
  val normalized = StringBuilder(text.length)
  for (character in text) {
    val digit = Character.digit(character, 10)
    when {
      digit >= 0 -> normalized.append(('0'.code + digit).toChar())
      grouping != null && character == grouping -> normalized.append(character)
      else -> return null
    }
  }
  return normalized.toString()
}
