/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestPolicyTest {
  private val saved = ScreensaverConfig.Settings(
      source = ScreensaverConfig.SOURCE_IMMICH, immichUrl = "https://photos.local", immichKey = "k",
      calendarUrl = "https://example.com/family.ics")

  @After fun reset() = GuestMode.set(GuestModeState.OFF)

  private fun guestsOn() = GuestMode.set(GuestModeState(active = true, untilMs = null))

  @Test fun offLeavesTheScreensaverUntouched() = assertSame(saved, GuestPolicy.screensaver(saved))

  @Test fun onSwapsPersonalPhotosForSceneryAndDropsTheCalendar() {
    guestsOn()
    val shown = GuestPolicy.screensaver(saved)
    assertEquals(ScreensaverConfig.SOURCE_DEFAULT, shown.source)
    assertFalse(shown.usesImmich)
    assertFalse(shown.usesCalendar)
    assertNull(shown.calendarUrl)
    assertEquals(PhotoFrameSource.DefaultFeed, PhotoFrameSource.from(shown))
  }

  @Test fun everyPersonalSourceFallsBackToTheBuiltInFeed() {
    guestsOn()
    listOf(
        saved.copy(source = ScreensaverConfig.SOURCE_FOLDER, folderPath = "/sdcard/DCIM"),
        saved.copy(source = ScreensaverConfig.SOURCE_URL, albumUrl = "https://share/album"),
        saved.copy(source = ScreensaverConfig.SOURCE_SMB, smbHost = "nas", smbShare = "photos"),
        saved.copy(source = ScreensaverConfig.SOURCE_DAV, davUrl = "https://dav"),
        saved.copy(source = ScreensaverConfig.SOURCE_WEBURL, webUrl = "https://family.example"),
    ).forEach { assertEquals(it.source, PhotoFrameSource.DefaultFeed, PhotoFrameSource.from(GuestPolicy.screensaver(it))) }
  }

  @Test fun personalHomeWidgetsAreHiddenAndNeutralOnesStay() {
    guestsOn()
    listOf(HomeWidgetStore.KIND_PHOTO_MEMORY, HomeWidgetStore.KIND_CALENDAR, HomeWidgetStore.KIND_TODAY,
        HomeWidgetStore.KIND_TODO).forEach { assertTrue(it, GuestPolicy.hidesHomeWidget(it)) }
    listOf(HomeWidgetStore.KIND_WEATHER, HomeWidgetStore.KIND_WORLD_CLOCK, HomeWidgetStore.KIND_TIMERS,
        HomeWidgetStore.KIND_QUICK_CONTROLS).forEach { assertFalse(it, GuestPolicy.hidesHomeWidget(it)) }
  }

  @Test fun wifiWidgetStaysVisibleForGuests_itIsMeantForThem() {
    guestsOn()
    assertNull(GuestPolicy.homeWidgetContent(HomeWidgetStore.KIND_WIFI))
    assertFalse(GuestPolicy.hidesHomeWidget(HomeWidgetStore.KIND_WIFI))
  }

  @Test fun nothingIsHiddenWhenGuestModeIsOff() {
    assertFalse(GuestPolicy.hidesHomeWidget(HomeWidgetStore.KIND_PHOTO_MEMORY))
    assertFalse(GuestPolicy.hidesTool("notes"))
  }

  @Test fun notesToolIsHiddenForGuests() {
    guestsOn()
    assertTrue(GuestPolicy.hidesTool("notes"))
    assertFalse(GuestPolicy.hidesTool("lamp"))
    assertFalse(GuestPolicy.hidesTool("guests"))
  }

  @Test fun anExpiredScheduleShowsEverythingAgain() {
    GuestMode.set(GuestModeState(active = true, untilMs = 1_000L))
    assertSame(saved, GuestPolicy.screensaver(saved, nowMs = 2_000L))
    assertFalse(GuestPolicy.hidesHomeWidget(HomeWidgetStore.KIND_CALENDAR, nowMs = 2_000L))
  }
}
