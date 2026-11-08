/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HomeWidgetStore.HomeWidget

/**
 * What the simplified "Add a widget" picker offers (approved 2026-11-07): the everyday widgets
 * up front, everything else behind "More". Pure; nothing here touches storage.
 */
internal object HomeWidgetPicker {
  /** The everyday widgets: the approved seven in order, then Wi-Fi (the guest join QR). */
  val everyday: List<String> = listOf(
      HomeWidgetStore.KIND_WEATHER, HomeWidgetStore.KIND_CALENDAR, HomeWidgetStore.KIND_HA,
      HomeWidgetStore.KIND_TIMERS, HomeWidgetStore.KIND_TODO, HomeWidgetStore.KIND_ALARM,
      HomeWidgetStore.KIND_WORLD_CLOCK, HomeWidgetStore.KIND_WIFI)

  /**
   * Built-in kinds behind "More" (the retire-candidates). Options needs Advanced settings; Photo
   * memory is offered only while Home has none (it is the page hero).
   */
  fun more(advanced: Boolean, photoMemoryAllowed: Boolean): List<String> = listOfNotNull(
      HomeWidgetStore.KIND_ROUTINES, HomeWidgetStore.KIND_TODAY, HomeWidgetStore.KIND_QUICK_CONTROLS, HomeWidgetStore.KIND_CRYPTO,
      HomeWidgetStore.KIND_NEWS, HomeWidgetStore.KIND_STOCKS,
      HomeWidgetStore.KIND_OPTIONS.takeIf { advanced },
      HomeWidgetStore.KIND_PHOTO_MEMORY.takeIf { photoMemoryAllowed })

  fun photoMemoryAllowed(widgets: List<HomeWidget>): Boolean =
      widgets.none { it.kind == HomeWidgetStore.KIND_PHOTO_MEMORY }

  /**
   * Kinds whose real widget is cheap to show as a preview: local data only, no network or polling.
   * Every other kind gets a static representative preview so the picker never starts fetches.
   */
  val livePreviewKinds: Set<String> = setOf(
      HomeWidgetStore.KIND_TIMERS, HomeWidgetStore.KIND_TODO, HomeWidgetStore.KIND_ALARM,
      HomeWidgetStore.KIND_WORLD_CLOCK, HomeWidgetStore.KIND_QUICK_CONTROLS)

  /** The shape a new widget of [kind] starts with: its natural size when it fits, else the first that does. */
  fun initialShape(kind: String, fitting: List<Pair<Int, Int>>): Pair<Int, Int>? {
    val natural = if (kind == HomeWidgetStore.KIND_PHOTO_MEMORY) 2 to 4 else 1 to 2
    return natural.takeIf { it in fitting } ?: fitting.firstOrNull()
  }

  fun label(kind: String): String = when (kind) {
    HomeWidgetStore.KIND_WEATHER -> "Weather"
    HomeWidgetStore.KIND_CALENDAR -> "Calendar"
    HomeWidgetStore.KIND_HA -> "Home Assistant"
    HomeWidgetStore.KIND_TIMERS -> "Timers"
    HomeWidgetStore.KIND_TODO -> "Household list"
    HomeWidgetStore.KIND_ALARM -> "Alarm"
    HomeWidgetStore.KIND_WORLD_CLOCK -> "World clock"
    HomeWidgetStore.KIND_TODAY -> "Today"
    HomeWidgetStore.KIND_QUICK_CONTROLS -> "Quick controls"
    HomeWidgetStore.KIND_CRYPTO -> "Crypto"
    HomeWidgetStore.KIND_NEWS -> "News"
    HomeWidgetStore.KIND_STOCKS -> "Stocks"
    HomeWidgetStore.KIND_OPTIONS -> "Options"
    HomeWidgetStore.KIND_PHOTO_MEMORY -> "Photo memory"
    HomeWidgetStore.KIND_ROUTINES -> "Routines"
    HomeWidgetStore.KIND_WIFI -> "Wi-Fi"
    else -> "Widget"
  }

  fun icon(kind: String): String = when (kind) {
    HomeWidgetStore.KIND_WEATHER -> "weather"
    HomeWidgetStore.KIND_CALENDAR, HomeWidgetStore.KIND_TODAY -> "calendar"
    HomeWidgetStore.KIND_HA -> "ha_widget"
    HomeWidgetStore.KIND_TIMERS -> "timer"
    HomeWidgetStore.KIND_TODO -> "notes"
    HomeWidgetStore.KIND_ALARM -> "alarm"
    HomeWidgetStore.KIND_WORLD_CLOCK -> "clock"
    HomeWidgetStore.KIND_QUICK_CONTROLS -> "tools"
    HomeWidgetStore.KIND_NEWS -> "globe"
    HomeWidgetStore.KIND_PHOTO_MEMORY -> "photo"
    HomeWidgetStore.KIND_ROUTINES -> "timer"
    HomeWidgetStore.KIND_WIFI -> "wifi"
    HomeWidgetStore.KIND_CRYPTO, HomeWidgetStore.KIND_STOCKS, HomeWidgetStore.KIND_OPTIONS -> "calculator"
    else -> "apps"
  }
}
