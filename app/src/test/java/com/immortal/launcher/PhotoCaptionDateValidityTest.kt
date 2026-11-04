/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/** Actual EXIF read boundary, with literal dates/GPS and no image, provider or rendered UI. */
class PhotoCaptionDateValidityTest {
  @Test fun february30CannotManufactureACaptionOrMemoryBadge() = withDefaults("UTC") {
    val meta = PhotoCaption.read(metadata(original = "2023:02:30 12:00:00"))
    assertNull("Impossible capture date", meta.dateMillis)
    assertNull("No manufactured March 2 caption", PhotoCaption.formatDate(meta.dateMillis))
    assertNull("No manufactured March 2 memory badge",
        PhotoCaption.memoryYears(meta.dateMillis, now = 1898683200000L))
    assertTrue(meta.isEmpty)
  }

  @Test fun nonLeapFebruary29HasNoDate() =
      invalid("2023:02:29 12:00:00")

  @Test fun nonLeapCentury1900February29HasNoDate() =
      invalid("1900:02:29 12:00:00")

  @Test fun april31HasNoDate() =
      invalid("2023:04:31 12:00:00")

  @Test fun month13HasNoDate() =
      invalid("2023:13:01 12:00:00")

  @Test fun day0HasNoDate() =
      invalid("2023:03:00 12:00:00")

  @Test fun hour24HasNoDate() =
      invalid("2023:02:28 24:00:00")

  @Test fun impossibleOriginalDoesNotSubstituteValidFallback() = withDefaults("UTC") {
    val meta = PhotoCaption.read(metadata(
        original = "2023:02:30 12:00:00", fallback = "2023:02:28 12:00:00"))
    assertNull(meta.dateMillis)
    assertTrue(meta.isEmpty)
  }

  @Test fun invalidFebruary30RetainsExactGpsWithoutADate() = withDefaults("UTC") {
    val coordinates = doubleArrayOf(48.8566, 2.3522)
    val meta = PhotoCaption.read(metadata(original = "2023:02:30 12:00:00", gps = coordinates))
    assertGps(meta)
    assertNull(meta.dateMillis)
    assertFalse(meta.isEmpty)
  }

  @Test fun valid2024LeapFebruary29NoonUtcHasLiteralEpoch() =
      valid("2024:02:29 12:00:00", 1709208000000L)

  @Test fun valid2000LeapCenturyFebruary29NoonUtcHasLiteralEpoch() =
      valid("2000:02:29 12:00:00", 951825600000L)

  @Test fun valid2023February28NoonUtcHasLiteralEpoch() =
      valid("2023:02:28 12:00:00", 1677585600000L)

  @Test fun missingOriginalUsesValidFallback() = withDefaults("UTC") {
    val meta = PhotoCaption.read(metadata(fallback = "2023:02:28 12:00:00"))
    assertEquals(PhotoCaption.Meta(1677585600000L, null, null), meta)
    assertFalse(meta.isEmpty)
  }

  @Test fun absentDateAndGpsRemainEmpty() = withDefaults("UTC") {
    val meta = PhotoCaption.read(metadata())
    assertEquals(PhotoCaption.Meta(null, null, null), meta)
    assertFalse(meta.hasLocation)
    assertTrue(meta.isEmpty)
  }

  @Test fun validDateRetainsExactGps() = withDefaults("UTC") {
    val coordinates = doubleArrayOf(48.8566, 2.3522)
    val meta = PhotoCaption.read(metadata(original = "2024:02:29 12:00:00", gps = coordinates))
    assertGps(meta)
    assertEquals(1709208000000L, meta.dateMillis)
    assertFalse(meta.isEmpty)
  }

  @Test fun valid2020January1LosAngelesUsesCurrentZone() =
      valid("2020:01:01 00:30:00", 1577867400000L, zone = "America/Los_Angeles")

  private fun invalid(original: String) = withDefaults("UTC") {
    val meta = PhotoCaption.read(metadata(original = original))
    assertEquals(PhotoCaption.Meta(null, null, null), meta)
    assertTrue(meta.isEmpty)
  }

  private fun valid(original: String, expected: Long, zone: String = "UTC") = withDefaults(zone) {
    val meta = PhotoCaption.read(metadata(original = original))
    assertEquals(PhotoCaption.Meta(expected, null, null), meta)
    assertFalse(meta.isEmpty)
  }

  private fun assertGps(meta: PhotoCaption.Meta) {
    assertEquals(48.8566, meta.lat!!, 0.0)
    assertEquals(2.3522, meta.lng!!, 0.0)
    assertTrue(meta.hasLocation)
  }

  private fun metadata(
      original: String? = null, fallback: String? = null, gps: DoubleArray? = null,
  ): ExifInterface = Mockito.mockStatic(Log::class.java).use {
    val exif = Mockito.mock(ExifInterface::class.java)
    Mockito.`when`(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).thenReturn(original)
    Mockito.`when`(exif.getAttribute(ExifInterface.TAG_DATETIME)).thenReturn(fallback)
    Mockito.`when`(exif.getLatLong()).thenReturn(gps)
    exif
  }

  private inline fun withDefaults(zone: String, block: () -> Unit) {
    val previousZone = TimeZone.getDefault()
    val previousLocale = Locale.getDefault()
    val previousFormat = Locale.getDefault(Locale.Category.FORMAT)
    val previousDisplay = Locale.getDefault(Locale.Category.DISPLAY)
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone))
      Locale.setDefault(Locale.US)
      Locale.setDefault(Locale.Category.FORMAT, Locale.US)
      Locale.setDefault(Locale.Category.DISPLAY, Locale.US)
      block()
    } finally {
      TimeZone.setDefault(previousZone)
      Locale.setDefault(previousLocale)
      Locale.setDefault(Locale.Category.FORMAT, previousFormat)
      Locale.setDefault(Locale.Category.DISPLAY, previousDisplay)
    }
  }
}
