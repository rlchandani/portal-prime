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
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.mockito.Mockito

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class PhotoCaptionTimeZoneTest {
  private inline fun withDefaults(zone: String, block: () -> Unit) {
    val previousZone = TimeZone.getDefault()
    val previousLocale = Locale.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone))
      Locale.setDefault(Locale.US)
      block()
    } finally {
      TimeZone.setDefault(previousZone)
      Locale.setDefault(previousLocale)
    }
  }

  private fun metadata(original: String? = null, fallback: String? = null,
                       coordinates: DoubleArray? = null): ExifInterface =
      Mockito.mockStatic(Log::class.java).use {
        val exif = Mockito.mock(ExifInterface::class.java)
        Mockito.`when`(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)).thenReturn(original)
        Mockito.`when`(exif.getAttribute(ExifInterface.TAG_DATETIME)).thenReturn(fallback)
        Mockito.`when`(exif.getLatLong()).thenReturn(coordinates)
        exif
      }

  @Test
  fun a01_newReadsFollowEachCurrentDefaultTimeZone() = withDefaults("UTC") {
    // This first production access must initialize the object in UTC in the focused JVM.
    val exif = metadata(original = "2020:01:01 00:30:00")
    val utc = PhotoCaption.read(exif).dateMillis
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    val losAngeles = PhotoCaption.read(exif).dateMillis
    TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
    val kiritimati = PhotoCaption.read(exif).dateMillis
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    val utcAgain = PhotoCaption.read(exif).dateMillis
    assertEquals(
        listOf(1577838600000L, 1577867400000L, 1577788200000L, 1577838600000L),
        listOf(utc, losAngeles, kiritimati, utcAgain))
  }

  @Test
  fun a02_captionAndMemoryUseTheSameCurrentLocalDate() = withDefaults("America/Los_Angeles") {
    val meta = PhotoCaption.read(metadata(original = "2020:01:01 00:30:00"))
    val displayed = PhotoCaption.formatDate(meta.dateMillis)
    val years = PhotoCaption.memoryYears(meta.dateMillis, now = 1672603200000L)
    assertEquals(Pair("January 1, 2020", 3), Pair(displayed, years))
  }

  @Test
  fun a03_absentDateAndGpsRemainEmpty() = withDefaults("UTC") {
    val meta = PhotoCaption.read(metadata())
    assertNull(meta.dateMillis)
    assertNull(meta.lat)
    assertNull(meta.lng)
    assertFalse(meta.hasLocation)
    assertTrue(meta.isEmpty)
  }

  @Test
  fun a04_gpsOnlyMetadataRetainsLocationWithoutDate() = withDefaults("UTC") {
    val meta = PhotoCaption.read(metadata(coordinates = doubleArrayOf(48.8566, 2.3522)))
    assertNull(meta.dateMillis)
    assertEquals(48.8566, meta.lat!!, 0.0)
    assertEquals(2.3522, meta.lng!!, 0.0)
    assertTrue(meta.hasLocation)
    assertFalse(meta.isEmpty)
  }

  @Test
  fun a05_malformedOriginalDoesNotSubstituteAValidFallback() = withDefaults("UTC") {
    val meta = PhotoCaption.read(metadata(original = "not-a-date", fallback = "2020:01:01 00:30:00"))
    assertNull(meta.dateMillis)
    assertTrue(meta.isEmpty)
  }

  @Test
  fun a06_missingDatesAndMemoryLabelsRetainTheirContract() = withDefaults("UTC") {
    assertNull(PhotoCaption.formatDate(null))
    assertNull(PhotoCaption.memoryYears(null, now = 1672603200000L))
    assertEquals("1 year ago today", PhotoCaption.memoryLabel(1))
    assertEquals("3 years ago today", PhotoCaption.memoryLabel(3))
  }
}
