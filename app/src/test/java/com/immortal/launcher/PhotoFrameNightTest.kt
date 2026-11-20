/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.PhotoFrameNight.Ambient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dark-room switching and night-settings broadcasts. */
class PhotoFrameNightTest {
  private val photo = ScreensaverConfig.NIGHT_TONE_PHOTO

  @Test fun aDarkRoomBecomesTheNightPhotoOnlyInThePhotoToneOnTheNativeStage() {
    assertEquals(Ambient.NIGHT_PHOTO, PhotoFrameNight.ambient(true, false, false, photo, nativePhotoMode = true))
    assertEquals(Ambient.QUIET_CLOCK, PhotoFrameNight.ambient(true, false, false, photo, nativePhotoMode = false))
    assertEquals(Ambient.QUIET_CLOCK, PhotoFrameNight.ambient(true, false, false, "warm", nativePhotoMode = true))
    assertEquals(Ambient.QUIET_CLOCK, PhotoFrameNight.ambient(false, true, false, "red", nativePhotoMode = true))
  }

  @Test fun noChangeOrAStoppedFrameDoesNothing() {
    assertEquals(Ambient.NONE, PhotoFrameNight.ambient(true, true, false, "warm", true))
    assertEquals(Ambient.NONE, PhotoFrameNight.ambient(false, false, false, photo, true))
    assertEquals(Ambient.NONE, PhotoFrameNight.ambient(true, false, stopped = true, tone = "warm", nativePhotoMode = true))
  }

  @Test fun aBroadcastReplacesOnlyTheNightControlsAndSaysWhatMoved() {
    val session = ScreensaverConfig.Settings(mode = ScreensaverConfig.MODE_WHOLE, intervalSec = 9, darkRoomClock = true, darkRoomLux = 3)
    val fresh = ScreensaverConfig.Settings(mode = ScreensaverConfig.MODE_CLASSIC, intervalSec = 60, darkRoomClock = true,
        darkRoomLux = 8, nightClockBrightness = 1, nightClockTone = "red")
    val c = PhotoFrameNight.change(session, fresh)
    assertEquals(ScreensaverConfig.MODE_WHOLE to 9, c.settings.mode to c.settings.intervalSec)
    assertEquals(listOf(true, 8, 1, "red"), listOf(c.settings.darkRoomClock, c.settings.darkRoomLux, c.settings.nightClockBrightness,
        c.settings.nightClockTone))
    assertTrue(c.sensorChanged && c.brightnessChanged && c.toneChanged)
    val same = PhotoFrameNight.change(session, session)
    assertFalse(same.sensorChanged || same.brightnessChanged || same.toneChanged)
    assertTrue(PhotoFrameNight.change(session, session.copy(darkRoomClock = false)).sensorChanged)
  }

  @Test fun theQuietClockDrawsWhileANightOrItsDissolveShows() {
    assertTrue(PhotoFrameNight.quietClockActive(hostVisible = true, nightShowing = true, coversPhotos = false, stopped = false))
    assertTrue(PhotoFrameNight.quietClockActive(true, nightShowing = false, coversPhotos = true, stopped = false))
    assertFalse(PhotoFrameNight.quietClockActive(true, false, false, false))
    assertFalse(PhotoFrameNight.quietClockActive(false, true, true, false))
    assertFalse(PhotoFrameNight.quietClockActive(true, true, true, stopped = true))
    assertTrue(PhotoFrameNight.photosActive(hostVisible = true, coversPhotos = false))
    assertFalse(PhotoFrameNight.photosActive(true, coversPhotos = true))
    assertFalse(PhotoFrameNight.photosActive(false, false))
  }

  @Test fun theLogSaysWhichWayItWent() {
    assertTrue(PhotoFrameNight.quietClockLog(true).startsWith("dark room"))
    assertTrue(PhotoFrameNight.quietClockLog(false).startsWith("light returned"))
    assertTrue(PhotoFrameNight.nightPhotoLog(true).contains("warm dim"))
    assertTrue(PhotoFrameNight.nightPhotoLog(false).contains("day frame"))
  }
}
