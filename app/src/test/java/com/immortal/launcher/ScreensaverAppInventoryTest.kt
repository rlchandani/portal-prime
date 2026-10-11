/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ScreensaverAppInventoryTest {
  private val context = mock(Context::class.java)
  private fun app(pkg: String, label: String): AppEntry {
    val component = mock(ComponentName::class.java)
    `when`(component.packageName).thenReturn(pkg)
    return AppEntry(label, component, mock(ImageBitmap::class.java))
  }

  @Test fun twentyWarmPickerVisitsReuseThePreparedAppsAndHaArtworkWithoutNewScans() {
    val ha = app("io.homeassistant.companion.android", "Home Assistant")
    val browser = app("sample.browser", "Browser")
    val cache = HomePresentationInventoryCache<AppEntry>()
    var scans = 0
    val reader = { cache.load { scans++; listOf(browser, ha) } }
    val warmed = reader()
    var fallbackDecodes = 0
    repeat(20) {
      val result = loadScreensaverAppInventory(context, reader, { ha.component.packageName }) {
        fallbackDecodes++; mock(ImageBitmap::class.java)
      }
      assertSame(warmed, result.apps)
      assertSame(ha.icon, result.haIcon)
      assertSame(browser.component, result.apps.first().component)
      assertSame(browser.icon, result.apps.first().icon)
    }
    assertEquals(1, scans)
    assertEquals(0, fallbackDecodes)
  }

  @Test fun packageInvalidationReplacesRemovedAppsAndTheirIconIdentities() {
    val old = app("sample.old", "Old app")
    val replacement = app("sample.new", "New app")
    val cache = HomePresentationInventoryCache<AppEntry>()
    var installed = listOf(old)
    var scans = 0
    val reader = { cache.load { scans++; installed } }
    val before = loadScreensaverAppInventory(context, reader, { null }, { null })
    installed = listOf(replacement); cache.invalidate()
    val after = loadScreensaverAppInventory(context, reader, { null }, { null })
    assertEquals(listOf(replacement), after.apps)
    assertNotSame(before.apps.first().icon, after.apps.first().icon)
    assertEquals(2, scans)
  }

  @Test fun installedHaWithoutACuratedLauncherEntryRetainsItsSingleIconFallback() {
    val fallback = mock(ImageBitmap::class.java)
    val requested = mutableListOf<String>()
    val result = loadScreensaverAppInventory(context, { emptyList() }, { "installed.ha" }) {
      requested += it; fallback
    }
    assertEquals("installed.ha", result.haPackage)
    assertSame(fallback, result.haIcon)
    assertEquals(listOf("installed.ha"), requested)
  }

  @Test fun absentHaNeverRequestsFallbackArtwork() {
    var decodes = 0
    val result = loadScreensaverAppInventory(context, { emptyList() }, { null }) {
      decodes++; mock(ImageBitmap::class.java)
    }
    assertNull(result.haPackage); assertNull(result.haIcon)
    assertEquals(0, decodes)
  }

  @Test fun failedInventoryReadCannotPublishPartialChoicesOrStartHaLookups() {
    var lookups = 0
    try {
      loadScreensaverAppInventory(context, { throw IllegalStateException("inventory unavailable") },
        { lookups++; "installed.ha" }, { mock(ImageBitmap::class.java) })
      fail("Inventory failure must reach the UI error boundary")
    } catch (_: IllegalStateException) { }
    assertEquals(0, lookups)
  }
}
