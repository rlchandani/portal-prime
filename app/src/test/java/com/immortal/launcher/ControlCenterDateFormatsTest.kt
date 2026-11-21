/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/** [CcDateFormats] reuses formatters but formats exactly like a fresh [SimpleDateFormat]. */
class ControlCenterDateFormatsTest {
  private lateinit var locale: Locale
  private lateinit var zone: TimeZone
  private val ms = 1_795_000_000_000L

  @Before fun save() { locale = Locale.getDefault(); zone = TimeZone.getDefault() }
  @After fun restore() { Locale.setDefault(locale); TimeZone.setDefault(zone) }

  private fun fresh(pattern: String, z: TimeZone = TimeZone.getDefault()) =
      SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = z }.format(Date(ms))

  @Test fun formatsLikeAFreshFormatter() {
    Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    for (p in listOf("h:mm a", "EEEE", "EEE d MMM")) assertEquals(fresh(p), CcDateFormats.format(p, ms))
  }

  @Test fun followsTheDefaultZoneWhenItChanges() {
    Locale.setDefault(Locale.US)
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    val la = CcDateFormats.format("h:mm a", ms)
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    assertEquals(fresh("h:mm a"), CcDateFormats.format("h:mm a", ms))
    assertEquals(false, la == CcDateFormats.format("h:mm a", ms))
  }

  @Test fun anExplicitZoneWins() {
    Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    assertEquals(fresh("EEE d MMM h:mm a", tokyo), CcDateFormats.format("EEE d MMM h:mm a", ms, tokyo))
  }

  @Test fun followsTheLocaleWhenItChanges() {
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    Locale.setDefault(Locale.US); CcDateFormats.format("EEEE", ms)
    Locale.setDefault(Locale.FRANCE)
    assertEquals(fresh("EEEE"), CcDateFormats.format("EEEE", ms))
  }

  @Test fun reusesOneFormatterPerPatternAndLocale() {
    Locale.setDefault(Locale.US)
    assertSame(CcDateFormats.formatter("h:mm a"), CcDateFormats.formatter("h:mm a"))
  }

  @Test fun ccTimeIsLowercase() {
    Locale.setDefault(Locale.US); TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    assertEquals(fresh("h:mm a").lowercase(), ccTime(ms))
  }
}
