/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ScreensaverConfig.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mode selection and every start-time overlay decision, on fake preferences. */
class PhotoFrameStartPlanTest {
  private val dream = PhotoFrameHost()
  private fun plan(s: Settings = Settings(), host: PhotoFrameHost = dream, prefs: FakePhotoFramePrefs = FakePhotoFramePrefs(), tts: Boolean = false) =
      PhotoFrameStartPlan.plan(s, host, prefs, tts)

  @Test fun theOperatorPinHoldsModeAndIntervalButMusicPhotosIgnoreIt() {
    val household = Settings(mode = "kenBurns", intervalSec = 45)
    val pin = ScreensaverTestSource.Pin(ScreensaverConfig.MODE_WHOLE, 9)
    val pinned = PhotoFrameStartPlan.settings(household, dream) { pin }
    assertEquals(ScreensaverConfig.MODE_WHOLE to 9, pinned.mode to pinned.intervalSec)
    assertEquals(household, PhotoFrameStartPlan.settings(household, dream) { null })
    var asked = false
    val music = PhotoFrameStartPlan.settings(household, PhotoFrameHost(photosOnly = true)) { asked = true; pin }
    assertFalse("music photos never read the pin", asked)
    assertEquals(ScreensaverConfig.MODE_WHOLE to 45, music.mode to music.intervalSec)
    assertFalse(music.includeVideo)
  }

  @Test fun theTestSourceReplacesEverySourceAndKeepsTheHouseholdCalendarAndDashboardOff() {
    val folder = Settings(mode = ScreensaverConfig.MODE_CLASSIC, source = ScreensaverConfig.SOURCE_FOLDER, folderPath = "/sdcard/DCIM",
        ambientDashboard = true)
    val p = plan(folder, prefs = FakePhotoFramePrefs(testSource = true))
    assertTrue(p.testSource)
    assertEquals(PhotoFrameSource.DefaultFeed, p.source)
    assertFalse(p.dashboard)
    assertEquals(PhotoFrameFeedRoute.WEB, p.feedRoute)
    assertEquals(PhotoFrameFeedRoute.TEST_SCENES, plan(folder.copy(mode = "cinema"), prefs = FakePhotoFramePrefs(testSource = true)).feedRoute)
    assertEquals(PhotoFrameFeedRoute.ENGINE, plan(folder.copy(mode = "cinema", source = ScreensaverConfig.SOURCE_DEFAULT)).feedRoute)
    assertTrue(plan(folder).dashboard)
  }

  @Test fun musicPhotosNeverConsultTheTestSourceOrDrawOverlays() {
    val prefs = FakePhotoFramePrefs(testSource = true, forcedNight = true)
    val p = plan(Settings(darkRoomClock = true, soundscape = "rain", gestureWave = true, welcomeEnabled = true),
        PhotoFrameHost(photosOnly = true, showWelcome = true), prefs, tts = true)
    assertEquals(emptyList<String>(), prefs.reads)
    assertFalse(p.testSource || p.forcedNight || p.darkRoomSensor || p.calendar || p.galleryObserver || p.weather)
    assertFalse(p.soundscape || p.gestureWave || p.speakWelcome || p.welcomeOverlay || p.dashboard)
    assertEquals(PhotoFrameOverlay.NONE, p.overlay)
  }

  @Test fun aForcedNightTakesTheLightSensorsPlace() {
    val dark = Settings(darkRoomClock = true)
    assertTrue(plan(dark).darkRoomSensor)
    val forced = plan(dark, prefs = FakePhotoFramePrefs(testSource = true, forcedNight = true))
    assertTrue(forced.forcedNight)
    assertFalse(forced.darkRoomSensor)
    assertFalse("forced night needs the test source", plan(dark, prefs = FakePhotoFramePrefs(forcedNight = true)).forcedNight)
    assertFalse(plan(Settings()).darkRoomSensor)
  }

  @Test fun aWebPageNeedsAFullFrameWithoutAFaceOverride() {
    val web = Settings(mode = ScreensaverConfig.MODE_SMART, source = ScreensaverConfig.SOURCE_WEBURL, webUrl = "https://kiosk.test")
    val p = plan(web)
    assertEquals(PhotoFrameSource.WebPage("https://kiosk.test"), p.source)
    assertFalse("a web page is never the native stage", p.nativePhotoMode)
    assertTrue(plan(web, PhotoFrameHost(faceOverride = true)).source !is PhotoFrameSource.WebPage)
    assertTrue(plan(web.copy(source = ScreensaverConfig.SOURCE_DEFAULT)).nativePhotoMode)
  }

  @Test fun theOverlayIsSplitGlanceOnlyOnItsModeAndNotUnderTheOvernightPhoto() {
    val split = Settings(mode = ScreensaverConfig.MODE_SPLIT_GLANCE)
    assertEquals(PhotoFrameOverlay.SPLIT, plan(split).overlay)
    assertEquals(PhotoFrameOverlay.FACE, plan(split, PhotoFrameHost(overnightNightPhoto = true)).overlay)
    assertEquals(PhotoFrameOverlay.FACE, plan(Settings()).overlay)
    assertTrue(PhotoFrameStartPlan.splitGlance(split, dream))
    assertFalse(PhotoFrameStartPlan.splitGlance(split, PhotoFrameHost(photosOnly = true)))
    assertFalse("split glance has no dashboard", plan(split.copy(ambientDashboard = true)).dashboard)
  }

  @Test fun theOvernightNightPhotoIsQuietAndCardless() {
    val busy = Settings(mode = ScreensaverConfig.MODE_CLASSIC, soundscape = "rain", ambientDashboard = true)
    val p = plan(busy, PhotoFrameHost(overnightNightPhoto = true))
    assertTrue(p.nightPhoto)
    assertFalse(p.soundscape || p.dashboard)
    assertTrue(plan(busy).soundscape)
    assertFalse(plan(busy.copy(soundscape = ScreensaverConfig.SOUND_OFF)).soundscape)
  }

  @Test fun theWelcomeShowsOnPresenceStartsOutsideEngineStylesAndSpeaksOnlyWhenAsked() {
    val s = Settings(mode = ScreensaverConfig.MODE_CLASSIC, welcomeEnabled = true)
    val presence = PhotoFrameHost(showWelcome = true)
    assertTrue(plan(s, presence).welcomeOverlay)
    assertFalse(plan(s, presence).speakWelcome)
    assertTrue(plan(s, presence, tts = true).speakWelcome)
    assertFalse("only a presence start welcomes", plan(s, dream, tts = true).welcomeOverlay)
    assertFalse("engine styles have their own clock", plan(s.copy(mode = "framed"), presence).welcomeOverlay)
    assertTrue("the voice doesn't depend on the style", plan(s.copy(mode = "framed"), presence, tts = true).speakWelcome)
    assertFalse(plan(s.copy(welcomeEnabled = false), presence, tts = true).speakWelcome)
  }

  @Test fun engineStylesSkipTheDashboardAndCachePurgingFollowsTheSetting() {
    assertTrue(plan(Settings(mode = "framed")).engineMode)
    assertFalse(plan(Settings(mode = "framed", ambientDashboard = true)).dashboard)
    assertFalse(plan(Settings(mode = ScreensaverConfig.MODE_SMART)).engineMode)
    assertTrue(plan(Settings(cacheEnabled = false)).purgeCache)
    assertFalse(plan(Settings(cacheEnabled = true)).purgeCache)
    assertTrue(plan(Settings(gestureWave = true)).gestureWave)
  }
}
