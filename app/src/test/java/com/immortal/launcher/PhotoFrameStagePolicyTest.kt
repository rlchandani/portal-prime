/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.PhotoFrameGesture.Kind
import com.immortal.launcher.ScreensaverConfig.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Presentation, overlay, calendar, engine-command and touch decisions. */
class PhotoFrameStagePolicyTest {
  @Test fun wholePhotoNeitherFillsNorMovesNorFramesPeople() {
    val whole = PhotoFrameStagePolicy.presentation(Settings(mode = ScreensaverConfig.MODE_WHOLE, fit = ScreensaverConfig.FIT_FILL), false, false)
    assertFalse(whole.fill || whole.motion || whole.keepPeople)
    val smart = PhotoFrameStagePolicy.presentation(Settings(mode = ScreensaverConfig.MODE_SMART), false, false)
    assertTrue(smart.fill && smart.motion && smart.keepPeople)
    assertEquals(SlideshowStage.Feature.NONE, smart.feature)
    assertFalse(PhotoFrameStagePolicy.presentation(Settings(fit = ScreensaverConfig.FIT_FIT), false, false).fill)
    assertFalse(PhotoFrameStagePolicy.presentation(Settings(photoMotion = false), false, false).motion)
    assertFalse(PhotoFrameStagePolicy.presentation(Settings(keepPeopleInFrame = false), false, false).keepPeople)
  }

  @Test fun galleryModesFlagTheStageAndSkipPeopleFraming() {
    val print = PhotoFrameStagePolicy.presentation(Settings(mode = ScreensaverConfig.MODE_GALLERY_PRINT), false, false)
    assertTrue(print.galleryPrint && !print.gallerySpread && !print.keepPeople)
    val spread = PhotoFrameStagePolicy.presentation(Settings(mode = ScreensaverConfig.MODE_GALLERY_SPREAD), false, false)
    assertTrue(spread.gallerySpread && !spread.galleryPrint)
    assertEquals(SlideshowStyle.GALLERY_PRINT, PhotoFrameStagePolicy.reportedStyle(ScreensaverConfig.MODE_GALLERY_PRINT))
    assertEquals(SlideshowStyle.GALLERY_SPREAD, PhotoFrameStagePolicy.reportedStyle(ScreensaverConfig.MODE_GALLERY_SPREAD))
    assertNull(PhotoFrameStagePolicy.reportedStyle(ScreensaverConfig.MODE_SMART))
    assertTrue(PhotoFrameStagePolicy.pairs(ScreensaverConfig.MODE_PORTRAITS) && PhotoFrameStagePolicy.pairs(ScreensaverConfig.MODE_GALLERY_SPREAD))
    assertFalse(PhotoFrameStagePolicy.pairs(ScreensaverConfig.MODE_GALLERY_PRINT))
  }

  @Test fun theNightPhotoOutranksSplitGlanceAndHidesTheDayOverlays() {
    assertEquals(SlideshowStage.Feature.NIGHT_PHOTO, PhotoFrameStagePolicy.presentation(Settings(), nightPhoto = true, splitGlance = true).feature)
    assertEquals(SlideshowStage.Feature.SPLIT, PhotoFrameStagePolicy.presentation(Settings(), nightPhoto = false, splitGlance = true).feature)
    val night = PhotoFrameStagePolicy.nightPhotoOverlays(night = true, splitGlance = false)
    assertFalse(night.dayVisible || night.rerenderCalendar)
    assertTrue(night.faceFollows)
    val day = PhotoFrameStagePolicy.nightPhotoOverlays(night = false, splitGlance = true)
    assertTrue(day.dayVisible && day.rerenderCalendar)
    assertFalse("split glance's panel replaces the face", day.faceFollows)
    assertTrue(PhotoFrameStagePolicy.representForNightPhoto(true, false, 1))
    assertFalse(PhotoFrameStagePolicy.representForNightPhoto(true, false, 2))
    assertFalse(PhotoFrameStagePolicy.representForNightPhoto(false, false, 1))
    assertFalse(PhotoFrameStagePolicy.representForNightPhoto(true, true, 1))
  }

  @Test fun theCalendarRefetchesOnlyWhenLinkedAndNotUnderTheTestSource() {
    val linked = Settings(calendarUrl = "https://cal.test/a.ics")
    assertEquals("https://cal.test/a.ics", PhotoFrameStagePolicy.fetchCalendar(linked, testSource = false))
    assertNull(PhotoFrameStagePolicy.fetchCalendar(linked, testSource = true))
    assertNull(PhotoFrameStagePolicy.fetchCalendar(linked.copy(calendarEnabled = false), false))
    assertNull(PhotoFrameStagePolicy.fetchCalendar(Settings(calendarUrl = " "), false))
    assertTrue(PhotoFrameStagePolicy.calendarChanged(linked, linked.copy(calendarRange = CalendarFeed.RANGE_WEEK)))
    assertTrue(PhotoFrameStagePolicy.calendarChanged(linked, Settings()))
    assertFalse(PhotoFrameStagePolicy.calendarChanged(linked, linked.copy(mode = ScreensaverConfig.MODE_CLASSIC)))
  }

  @Test fun theEnginePlaysOnlyWhileActiveAndUnpaused() {
    assertEquals("window.immortalSlideshow?.resume()", PhotoFrameEngineScript.playback(hostActive = true, paused = false))
    assertEquals("window.immortalSlideshow?.pause()", PhotoFrameEngineScript.playback(hostActive = true, paused = true))
    assertEquals("window.immortalSlideshow?.pause()", PhotoFrameEngineScript.playback(hostActive = false, paused = false))
    assertEquals("file:///android_asset/screensaver/index.html?nativeClock=1&v=280&hostPaused=0", PhotoFrameEngineScript.pageUrl(280, true, false))
    assertEquals("file:///android_asset/screensaver/index.html?nativeClock=1&v=0&hostPaused=1", PhotoFrameEngineScript.pageUrl(0, false, false))
  }

  @Test fun swipesNavigateTapsExitAndEverythingElseIsIgnored() {
    assertEquals(Kind.NEXT, PhotoFrameGesture.classify(-200f, 10f))
    assertEquals(Kind.PREVIOUS, PhotoFrameGesture.classify(200f, -10f))
    assertEquals("not past the swipe distance", Kind.NONE, PhotoFrameGesture.classify(120f, 0f))
    assertEquals("too diagonal", Kind.NONE, PhotoFrameGesture.classify(150f, 100f))
    assertEquals(Kind.NEXT, PhotoFrameGesture.classify(-151f, 100f))
    assertEquals(Kind.TAP, PhotoFrameGesture.classify(47f, -47f))
    assertEquals(Kind.NONE, PhotoFrameGesture.classify(48f, 0f))
    assertTrue(PhotoFrameGesture.isTap(0f, 47.9f))
    assertFalse(PhotoFrameGesture.isTap(0f, 48f))
  }
}
