/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverConfigTest {

  private val legacyModes = listOf(
      "classic", "shuffle", "kenBurns", "diptych", "framed", "galleryWall", "push", "memories",
      "panorama", "polaroid", "mosaic", "filmstrip", "cinema", "heroSidebar")

  @Test
  fun freshInstallation_usesSmartFrame_withoutWritingPreferences() {
    val prefs = ScreensaverTestPrefs()
    assertEquals("smartFrame", ScreensaverConfig.Settings().mode)
    assertEquals("smartFrame", ScreensaverConfig.load(prefs.context).mode)
    assertEquals(0, prefs.writeCount)
    assertFalse(prefs.contains("mode"))
  }

  @Test
  fun savedLegacyStyle_isPreserved_withoutMigrationWrites() {
    legacyModes.forEach { mode ->
      val prefs = ScreensaverTestPrefs(mapOf("mode" to mode))
      assertEquals(mode, ScreensaverConfig.load(prefs.context).mode)
      assertEquals("loading $mode must not rewrite preferences", 0, prefs.writeCount)
      assertEquals(mode, prefs.getString("mode", null))
    }
  }

  @Test
  fun preferredAndLegacyStyles_roundTripThroughModeSetter() {
    (listOf("smartFrame", "portraitPair", "wholePhoto", "galleryPrint") + legacyModes).forEach { mode ->
      val prefs = ScreensaverTestPrefs()
      ScreensaverConfig.setMode(prefs.context, mode)
      assertEquals("selected style $mode must persist", mode, ScreensaverConfig.load(prefs.context).mode)
    }
  }

  @Test
  fun nativeStyles_doNotUseTheWebEngine_andLegacyRoutingStaysIntact() {
    listOf("smartFrame", "portraitPair", "wholePhoto", "galleryPrint", "classic").forEach {
      assertFalse("$it must use a native renderer", ScreensaverConfig.usesEngine(it))
      assertEquals(it != "classic", ScreensaverConfig.isNativeStage(it))
    }
    (legacyModes - "classic").forEach {
      assertTrue("$it must keep its existing engine", ScreensaverConfig.usesEngine(it))
      assertFalse("$it must keep its existing engine", ScreensaverConfig.isNativeStage(it))
    }
  }

  @Test
  fun clampInterval_keepsWithinBounds() {
    assertEquals(5, ScreensaverConfig.clampInterval(1))
    assertEquals(5, ScreensaverConfig.clampInterval(-10))
    assertEquals(30, ScreensaverConfig.clampInterval(30))
    assertEquals(600, ScreensaverConfig.clampInterval(99999))
  }

  @Test
  fun usesFolder_requiresFolderSourceAndPath() {
    assertTrue(
        ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_FOLDER, folderPath = "/storage/emulated/0/DCIM")
            .usesFolder)
    assertFalse(
        ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_FOLDER, folderPath = null)
            .usesFolder)
    assertFalse(
        ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_FOLDER, folderPath = "")
            .usesFolder)
    assertFalse(
        ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_DEFAULT, folderPath = "/storage/emulated/0/DCIM")
            .usesFolder)
  }

  @Test
  fun defaults_areSensible() {
    val s = ScreensaverConfig.Settings()
    assertEquals(ScreensaverConfig.SOURCE_DEFAULT, s.source)
    assertEquals(ScreensaverConfig.FIT_FILL, s.fit)
    assertEquals(30, s.intervalSec)
    assertEquals(60, s.albumRefreshMin)
    assertFalse(s.shuffle)
    assertTrue(s.includeVideo)
  }

  @Test
  fun usesUrl_requiresUrlSourceAndLink() {
    assertTrue(
        ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_URL,
                albumUrl = "https://www.icloud.com/sharedalbum/#B1abcDEF")
            .usesUrl)
    assertFalse(
        ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_URL, albumUrl = null).usesUrl)
    assertFalse(
        ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_URL, albumUrl = "").usesUrl)
    assertFalse(
        ScreensaverConfig.Settings(
                source = ScreensaverConfig.SOURCE_DEFAULT,
                albumUrl = "https://www.icloud.com/sharedalbum/#B1abcDEF")
            .usesUrl)
  }

  @Test
  fun clampAlbumRefresh_keepsWithinBounds() {
    assertEquals(15, ScreensaverConfig.clampAlbumRefresh(1))
    assertEquals(15, ScreensaverConfig.clampAlbumRefresh(-10))
    assertEquals(60, ScreensaverConfig.clampAlbumRefresh(60))
    assertEquals(24 * 60, ScreensaverConfig.clampAlbumRefresh(99999))
  }
}
