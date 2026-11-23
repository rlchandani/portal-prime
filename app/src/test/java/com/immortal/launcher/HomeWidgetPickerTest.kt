/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.HomeWidgetStore.HomeWidget
import org.junit.Assert.*
import org.junit.Test

/** Kind lists and draft defaults for the "Add a widget" picker (approved 2026-11-07). */
class HomeWidgetPickerTest {
  @Test fun everyday_isTheApprovedKinds_inMockOrder_plusWifi() {
    // Wi-Fi (2026-11-07) joins the everyday seven: a guest-facing join QR. Calls (2026-11-18): the
    // Calling mock's Home card with favourite rooms.
    assertEquals(listOf(HomeWidgetStore.KIND_WEATHER, HomeWidgetStore.KIND_CALENDAR, HomeWidgetStore.KIND_HA,
        HomeWidgetStore.KIND_TIMERS, HomeWidgetStore.KIND_TODO, HomeWidgetStore.KIND_ALARM,
        HomeWidgetStore.KIND_WORLD_CLOCK, HomeWidgetStore.KIND_WIFI, HomeWidgetStore.KIND_CALLS), HomeWidgetPicker.everyday)
  }

  @Test fun wifi_isRegisteredWithItsLabelIconAndRemoteKind() {
    assertEquals("wifi", HomeWidgetStore.KIND_WIFI)
    assertEquals("Wi-Fi", HomeWidgetPicker.label(HomeWidgetStore.KIND_WIFI))
    assertEquals("wifi", HomeWidgetPicker.icon(HomeWidgetStore.KIND_WIFI))
    assertTrue(HomeWidgetStore.KIND_WIFI in RemoteLayoutPolicy.kinds)
    assertFalse(HomeWidgetStore.KIND_WIFI in HomeWidgetPicker.more(advanced = true, photoMemoryAllowed = true))
    // The real widget decrypts the password for its QR; the picker shows a static preview instead.
    assertFalse(HomeWidgetStore.KIND_WIFI in HomeWidgetPicker.livePreviewKinds)
  }

  @Test fun more_holdsTheRetireCandidates_withoutOptionsUnlessAdvanced() {
    val basic = HomeWidgetPicker.more(advanced = false, photoMemoryAllowed = true)
    // Picture routines (2026-11-07) and Chores (2026-11-17) are new kinds offered under More, ahead of the retire-candidates.
    assertEquals(listOf(HomeWidgetStore.KIND_ROUTINES, HomeWidgetStore.KIND_CHORES, HomeWidgetStore.KIND_TODAY, HomeWidgetStore.KIND_QUICK_CONTROLS,
        HomeWidgetStore.KIND_CRYPTO, HomeWidgetStore.KIND_NEWS, HomeWidgetStore.KIND_STOCKS,
        HomeWidgetStore.KIND_PHOTO_MEMORY), basic)
    val advanced = HomeWidgetPicker.more(advanced = true, photoMemoryAllowed = true)
    assertTrue(HomeWidgetStore.KIND_OPTIONS in advanced)
    assertEquals(basic.size + 1, advanced.size)
  }

  @Test fun more_hidesPhotoMemoryWhenNotAllowed() {
    assertFalse(HomeWidgetStore.KIND_PHOTO_MEMORY in HomeWidgetPicker.more(true, photoMemoryAllowed = false))
  }

  @Test fun everydayAndMore_neverOverlap_andCoverEveryCustomKind() {
    val all = HomeWidgetPicker.everyday + HomeWidgetPicker.more(advanced = true, photoMemoryAllowed = true, sports = true)
    assertEquals(all.size, all.toSet().size)
    assertEquals(RemoteLayoutPolicy.kinds, all.toSet())
  }

  @Test fun photoMemory_isAllowedOnlyWhenHomeHasNone() {
    assertTrue(HomeWidgetPicker.photoMemoryAllowed(listOf(HomeWidget(kind = HomeWidgetStore.KIND_WEATHER, customId = "w"))))
    assertFalse(HomeWidgetPicker.photoMemoryAllowed(listOf(
        HomeWidget(kind = HomeWidgetStore.KIND_PHOTO_MEMORY, customId = "p"))))
  }

  @Test fun initialShape_isTheKindDefault_whenItFits_elseTheFirstThatFits() {
    val all = HomeSlots.SHAPES
    assertEquals(1 to 2, HomeWidgetPicker.initialShape(HomeWidgetStore.KIND_TIMERS, all))
    assertEquals(2 to 4, HomeWidgetPicker.initialShape(HomeWidgetStore.KIND_PHOTO_MEMORY, all))
    assertEquals(1 to 2, HomeWidgetPicker.initialShape(HomeWidgetStore.KIND_PHOTO_MEMORY, listOf(1 to 2, 1 to 4)))
    assertNull(HomeWidgetPicker.initialShape(HomeWidgetStore.KIND_TIMERS, emptyList()))
  }

  @Test fun livePreviews_areOnlyLocalDataKinds() {
    assertEquals(setOf(HomeWidgetStore.KIND_TIMERS, HomeWidgetStore.KIND_TODO, HomeWidgetStore.KIND_ALARM,
        HomeWidgetStore.KIND_WORLD_CLOCK, HomeWidgetStore.KIND_QUICK_CONTROLS), HomeWidgetPicker.livePreviewKinds)
    assertFalse(HomeWidgetStore.KIND_WEATHER in HomeWidgetPicker.livePreviewKinds)
  }

  @Test fun sports_isOfferedOnlyWithPersonalizationOn() {
    assertEquals(HomeWidgetPicker.more(true, true), HomeWidgetPicker.more(true, true, sports = false))
    assertFalse(HomeWidgetStore.KIND_SPORTS in HomeWidgetPicker.more(advanced = true, photoMemoryAllowed = true))
    val on = HomeWidgetPicker.more(advanced = false, photoMemoryAllowed = true, sports = true)
    assertEquals(on.indexOf(HomeWidgetStore.KIND_STOCKS) + 1, on.indexOf(HomeWidgetStore.KIND_SPORTS))
    assertTrue(HomeWidgetStore.KIND_SPORTS in RemoteLayoutPolicy.kinds)
  }

  @Test fun everyKind_hasALabelAndIcon() {
    (HomeWidgetPicker.everyday + HomeWidgetPicker.more(true, true, sports = true)).forEach {
      assertNotEquals(it, "Widget", HomeWidgetPicker.label(it))
      assertTrue(it, HomeWidgetPicker.icon(it).isNotBlank())
    }
  }
}
