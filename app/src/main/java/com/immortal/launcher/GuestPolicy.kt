/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * How each surface applies [GuestMode]: pure projections, so the photo frame, Home and Tools all
 * read one rule set. Nothing here writes preferences; turning Guest mode off restores everything.
 */
internal object GuestPolicy {
  /**
   * The screensaver / photo frame / music photos as a guest may see them: the household's photo
   * source becomes the built-in scenery feed and the calendar link is dropped.
   */
  fun screensaver(saved: ScreensaverConfig.Settings, nowMs: Long = System.currentTimeMillis()): ScreensaverConfig.Settings {
    val photos = GuestMode.hides(GuestContent.PHOTOS, nowMs)
    val calendar = GuestMode.hides(GuestContent.CALENDAR, nowMs)
    if (!photos && !calendar) return saved
    return saved.copy(
        source = if (photos) ScreensaverConfig.SOURCE_DEFAULT else saved.source,
        calendarUrl = if (calendar) null else saved.calendarUrl,
    )
  }

  /** Which household content a Home widget kind shows; null for neutral kinds (clock, weather…). */
  fun homeWidgetContent(kind: String): GuestContent? = when (kind) {
    HomeWidgetStore.KIND_PHOTO_MEMORY -> GuestContent.PHOTOS
    HomeWidgetStore.KIND_CALENDAR -> GuestContent.CALENDAR
    // Today shows the next calendar event and the list count alongside weather.
    HomeWidgetStore.KIND_TODAY -> GuestContent.CALENDAR
    HomeWidgetStore.KIND_TODO -> GuestContent.HOUSEHOLD_LIST
    else -> null
  }

  /** True when [kind] must render as the neutral guest card instead of its content. */
  fun hidesHomeWidget(kind: String, nowMs: Long = System.currentTimeMillis()): Boolean =
      homeWidgetContent(kind)?.let { GuestMode.hides(it, nowMs) } == true

  /** Which household content a Tools pane shows; null for neutral tools. */
  fun toolContent(id: String): GuestContent? = when (id) {
    "notes" -> GuestContent.NOTES
    else -> null
  }

  fun hidesTool(id: String, nowMs: Long = System.currentTimeMillis()): Boolean =
      toolContent(id)?.let { GuestMode.hides(it, nowMs) } == true
}
