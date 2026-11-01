/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class MusicPhotoPolicyTest {
  @Test fun companionNeverSelectsVideoOrALegacyWebRenderer() {
    for (mode in ScreensaverConfig.MODES) {
      val saved = ScreensaverConfig.Settings(mode = mode, includeVideo = true)
      val projected = musicPhotoSettings(saved)
      assertEquals(ScreensaverConfig.MODE_WHOLE, projected.mode)
      assertFalse(projected.includeVideo)
      assertEquals(mode, saved.mode)
      assertTrue(saved.includeVideo)
    }
  }

  @Test fun sourceAndPhotoChoicesArePreservedWithoutWritingTheSavedSettings() {
    val saved = ScreensaverConfig.Settings(source = "folder", folderPath = "/photos",
        shuffle = true, intervalSec = 42, keepPeopleInFrame = true)
    val result = musicPhotoSettings(saved)
    assertEquals(saved.copy(mode = ScreensaverConfig.MODE_WHOLE, includeVideo = false), result)
    assertEquals("/photos", result.folderPath)
    assertEquals("folder", result.source)
  }

  @Test fun restartKeyIgnoresSuppressedPresentationChoices() {
    val saved = ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_FOLDER,
        folderPath = "/photos")
    val changes = listOf(
        saved.copy(mode = ScreensaverConfig.MODE_CLASSIC),
        saved.copy(includeVideo = false),
        saved.copy(fit = ScreensaverConfig.FIT_FIT),
        saved.copy(photoMotion = false),
        saved.copy(keepPeopleInFrame = false))
    for ((index, changed) in changes.withIndex()) {
      assertEquals("suppressed choice $index", musicPhotoRestartKey(saved), musicPhotoRestartKey(changed))
    }
  }

  @Test fun restartKeyIgnoresNonPhotoSettings() {
    val saved = ScreensaverConfig.Settings()
    val changes = listOf(
        saved.copy(calendarUrl = "https://calendar.example/feed.ics"),
        saved.copy(calendarEnabled = false, calendarRange = CalendarFeed.RANGE_AGENDA,
            calendarSize = 2, calendarSide = ScreensaverConfig.CAL_SIDE_LEFT),
        saved.copy(facesEnabled = false, faceId = "flip", faceSizeIndex = 2),
        saved.copy(darkRoomClock = true, darkRoomLux = 10, nightClockBrightness = 8,
            nightClockTone = "cool"),
        saved.copy(welcomeEnabled = false),
        saved.copy(presenceMode = FrameMode.PRESENCE, idleSleepMin = 10,
            sleepTimerEnabled = true, sleepTimerMin = 60, pauseAudioOnSleep = false,
            closeAppOnSleep = false, overnightEnabled = true, overnightNightClock = true,
            overnightStartMin = 1200, overnightEndMin = 480),
        saved.copy(enabled = false, batterySaver = false, showNowPlaying = false,
            antiBurnIn = false, ambientDashboard = true, gestureWave = true,
            soundscape = ScreensaverConfig.SOUND_RAIN, soundscapeVolume = 80,
            dismissAppComponent = "example/.Main", dismissHaDashboard = "home"))
    for ((index, changed) in changes.withIndex()) {
      assertEquals("non-photo settings $index", musicPhotoRestartKey(saved), musicPhotoRestartKey(changed))
    }
  }

  @Test fun restartKeyTracksActiveSourceInputs() {
    val folder = ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_FOLDER,
        folderPath = "/photos")
    val album = ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_URL,
        albumUrl = "https://photos.example/album")
    val immich = ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_IMMICH,
        immichUrl = "https://immich.example", immichKey = "test-key", immichAlbumId = "album")
    val dav = ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_DAV,
        davUrl = "https://dav.example/photos", davUser = "test-user", davPass = "test-pass")
    val smb = ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_SMB,
        smbHost = "nas", smbShare = "photos", smbPath = "family",
        smbUser = "test-user", smbPass = "test-pass")
    val cases = listOf(
        folder to listOf(folder.copy(folderPath = "/other"), folder.copy(shuffle = true)),
        album to listOf(album.copy(albumUrl = "https://photos.example/other"), album.copy(shuffle = true)),
        immich to listOf(immich.copy(immichUrl = "https://other.example"),
            immich.copy(immichKey = "other-test-key"), immich.copy(immichAlbumId = null),
            immich.copy(shuffle = true)),
        dav to listOf(dav.copy(davUrl = "https://dav.example/other"),
            dav.copy(davUser = "other-user"), dav.copy(davPass = "other-pass"), dav.copy(shuffle = true)),
        smb to listOf(smb.copy(smbHost = "other-nas"), smb.copy(smbShare = "other-share"),
            smb.copy(smbPath = "other"), smb.copy(smbUser = "other-user"),
            smb.copy(smbPass = "other-pass"), smb.copy(shuffle = true)))
    for ((saved, changes) in cases) {
      assertNotEquals("source selection", musicPhotoRestartKey(saved),
          musicPhotoRestartKey(saved.copy(source = ScreensaverConfig.SOURCE_DEFAULT)))
      for ((index, changed) in changes.withIndex()) {
        assertNotEquals("${saved.source} input $index", musicPhotoRestartKey(saved), musicPhotoRestartKey(changed))
      }
    }
  }

  @Test fun restartKeyIgnoresInactiveSourcesAndUsesTheNativeFallback() {
    val saved = ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_FOLDER,
        folderPath = "/photos")
    val changed = saved.copy(albumUrl = "https://photos.example/album",
        immichUrl = "https://immich.example", immichKey = "test-key",
        immichAlbumId = "album", immichAlbumName = "Renamed",
        davUrl = "https://dav.example/photos", davUser = "test-user", davPass = "test-pass",
        smbHost = "nas", smbShare = "photos", smbPath = "family",
        smbUser = "test-user", smbPass = "test-pass", webUrl = "https://dashboard.example")
    assertEquals(musicPhotoRestartKey(saved), musicPhotoRestartKey(changed))

    val defaults = ScreensaverConfig.Settings()
    assertEquals(musicPhotoRestartKey(defaults), musicPhotoRestartKey(defaults.copy(shuffle = true)))
    assertEquals(musicPhotoRestartKey(defaults),
        musicPhotoRestartKey(defaults.copy(source = ScreensaverConfig.SOURCE_FOLDER)))
    assertEquals(musicPhotoRestartKey(defaults), musicPhotoRestartKey(defaults.copy(
        source = ScreensaverConfig.SOURCE_WEBURL, webUrl = "https://dashboard.example")))
    val immich = changed.copy(source = ScreensaverConfig.SOURCE_IMMICH)
    assertEquals(musicPhotoRestartKey(immich),
        musicPhotoRestartKey(immich.copy(immichAlbumName = "Display name only")))
  }

  @Test fun restartKeyTracksOnlyConsumedTimingAndCacheSettings() {
    val defaults = ScreensaverConfig.Settings()
    val album = defaults.copy(source = ScreensaverConfig.SOURCE_URL,
        albumUrl = "https://photos.example/album")
    val immich = defaults.copy(source = ScreensaverConfig.SOURCE_IMMICH,
        immichUrl = "https://immich.example", immichKey = "test-key")
    val dav = defaults.copy(source = ScreensaverConfig.SOURCE_DAV,
        davUrl = "https://dav.example/photos")
    val folder = defaults.copy(source = ScreensaverConfig.SOURCE_FOLDER, folderPath = "/photos")
    for (saved in listOf(defaults, album, immich, dav, folder)) {
      assertNotEquals("photo interval", musicPhotoRestartKey(saved),
          musicPhotoRestartKey(saved.copy(intervalSec = 42)))
      assertNotEquals("cache cleanup toggle", musicPhotoRestartKey(saved),
          musicPhotoRestartKey(saved.copy(cacheEnabled = true)))
      assertEquals("disabled cache budget", musicPhotoRestartKey(saved),
          musicPhotoRestartKey(saved.copy(cacheBudgetGb = 8)))
      if (saved == album) {
        assertNotEquals("shared album refresh", musicPhotoRestartKey(saved),
            musicPhotoRestartKey(saved.copy(albumRefreshMin = 15)))
      } else {
        assertEquals("unused album refresh", musicPhotoRestartKey(saved),
            musicPhotoRestartKey(saved.copy(albumRefreshMin = 15)))
      }
    }
    for (saved in listOf(immich, dav)) {
      val cached = saved.copy(cacheEnabled = true)
      assertNotEquals("active cache budget", musicPhotoRestartKey(cached),
          musicPhotoRestartKey(cached.copy(cacheBudgetGb = 8)))
    }
    for (saved in listOf(defaults, album, folder)) {
      val cached = saved.copy(cacheEnabled = true)
      assertEquals("unused cache budget", musicPhotoRestartKey(cached),
          musicPhotoRestartKey(cached.copy(cacheBudgetGb = 8)))
    }
  }
}
