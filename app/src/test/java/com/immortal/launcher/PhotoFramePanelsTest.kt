/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure parts of the frame's panels: the welcome greeting and the calendar's labels and scale. */
class PhotoFramePanelsTest {
  @Test fun theGreetingFollowsTheHourAndAddsTheName() {
    val cfg = WelcomeConfig.Settings()
    assertEquals(cfg.greetingNight, PhotoFrameWelcome.greeting(cfg, 4))
    assertEquals(cfg.greetingMorning, PhotoFrameWelcome.greeting(cfg, 5))
    assertEquals(cfg.greetingMorning, PhotoFrameWelcome.greeting(cfg, 11))
    assertEquals(cfg.greetingAfternoon, PhotoFrameWelcome.greeting(cfg, 12))
    assertEquals(cfg.greetingEvening, PhotoFrameWelcome.greeting(cfg, 17))
    assertEquals(cfg.greetingNight, PhotoFrameWelcome.greeting(cfg, 22))
    assertEquals("${cfg.greetingEvening}, Sam", PhotoFrameWelcome.greeting(cfg.copy(userName = "Sam"), 20))
  }

  @Test fun theCalendarScalesBySizeAndNamesItsRange() {
    assertEquals(0.82f, PhotoFrameCalendarPanel.scaleFor(0))
    assertEquals(1f, PhotoFrameCalendarPanel.scaleFor(1))
    assertEquals(1.22f, PhotoFrameCalendarPanel.scaleFor(2))
    assertEquals("Today", PhotoFrameCalendarPanel.rangeLabel(CalendarFeed.RANGE_DAY))
    assertEquals("Next 3 days", PhotoFrameCalendarPanel.rangeLabel(CalendarFeed.RANGE_3DAY))
    assertEquals("This week", PhotoFrameCalendarPanel.rangeLabel(CalendarFeed.RANGE_WEEK))
    assertEquals("Upcoming", PhotoFrameCalendarPanel.rangeLabel(CalendarFeed.RANGE_AGENDA))
  }
}
