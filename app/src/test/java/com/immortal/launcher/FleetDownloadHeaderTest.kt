/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetDownloadHeaderTest {
  private fun header(name: String) = FleetFs.attachmentDisposition(name)

  private fun extended(value: String): String {
    val encoded = value.substringAfter("filename*=UTF-8''", missingDelimiterValue = "")
    assertTrue("A UTF-8 extended filename must be present", encoded.isNotEmpty())
    // RFC8187 treats '+' literally; application/x-www-form-urlencoded does not.
    return URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
  }

  private fun fallback(value: String): String =
      value.substringAfter("filename=\"").substringBefore("\";")

  @Test fun ordinaryNameRetainsAttachmentAndLegacyFallback() {
    assertTrue(header("normal.bin").startsWith("attachment; filename=\"normal.bin\""))
  }

  @Test fun unicodeNameSurvivesAsciiResponseEncoding() {
    val value = header("Family-家族-🎵.bin")
    assertTrue(value.all { it.code in 32..126 })
    assertEquals("Family-家族-🎵.bin",
        extended(value.toByteArray(Charsets.US_ASCII).toString(Charsets.US_ASCII)))
  }

  @Test fun quotedNameIsRetainedByExtendedParameter() {
    assertEquals("say \"hello\".bin", extended(header("say \"hello\".bin")))
    assertFalse(fallback(header("say \"hello\".bin")).contains('"'))
  }

  @Test fun backslashCannotEscapeFallbackQuote() {
    val value = header("folder\\")
    assertFalse(fallback(value).contains('\\'))
    assertEquals("folder\\", extended(value))
  }

  @Test fun crlfCannotCreateAnotherResponseHeader() {
    val value = header("header-\r\nX-Immortal-QA: injected.bin")
    assertFalse(value.contains('\r'))
    assertFalse(value.contains('\n'))
    assertEquals("header-__X-Immortal-QA: injected.bin", extended(value))
  }

  @Test fun asciiAndC1ControlsAreSanitized() {
    val controls = ((0..31) + (127..159)).map(Int::toChar).joinToString("")
    val value = header("a${controls}b.bin")
    assertTrue(value.all { it.code in 32..126 })
    assertEquals("a${"_".repeat(controls.length)}b.bin", extended(value))
  }

  @Test fun semicolonCannotAddDispositionParameters() {
    val value = header("photo; filename=evil.bin")
    assertEquals("photo; filename=evil.bin", extended(value))
    assertTrue(value.substringAfter("filename*=UTF-8''").contains("%3B"))
  }

  @Test fun spacePercentAsteriskAndApostropheUsePercentEncoding() {
    val value = header("100% 'photo'*.bin")
    assertEquals("100% 'photo'*.bin", extended(value))
    assertEquals("100%25%20%27photo%27%2A.bin", value.substringAfter("filename*=UTF-8''"))
  }

  @Test fun plusRemainsLiteralRatherThanTurningIntoSpace() {
    assertEquals("family+friends.bin", extended(header("family+friends.bin")))
  }

  @Test fun utf8OctetsUseUppercaseTwoDigitEscapes() {
    assertEquals("%C3%A9.bin", header("é.bin").substringAfter("filename*=UTF-8''"))
  }

  @Test fun emptyNameHasNonemptyDownloadFallback() {
    val value = header("")
    assertEquals("download", fallback(value))
    assertEquals("download", extended(value))
  }

  @Test fun exactlyOneFallbackAndOneExtendedParameter() {
    val value = header("document.bin")
    assertEquals(1, Regex("filename=\"").findAll(value).count())
    assertEquals(1, Regex("filename\\*=").findAll(value).count())
  }
}
