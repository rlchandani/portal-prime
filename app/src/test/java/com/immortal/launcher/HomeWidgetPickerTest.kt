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
  @Test fun everyday_isTheSevenApprovedKinds_inMockOrder() {
    assertEquals(listOf(HomeWidgetStore.KIND_WEATHER, HomeWidgetStore.KIND_CALENDAR, HomeWidgetStore.KIND_HA,
        HomeWidgetStore.KIND_TIMERS, HomeWidgetStore.KIND_TODO, HomeWidgetStore.KIND_ALARM,
        HomeWidgetStore.KIND_WORLD_CLOCK), HomeWidgetPicker.everyday)
  }

  @Test fun more_holdsTheRetireCandidates_withoutOptionsUnlessAdvanced() {
    val basic = HomeWidgetPicker.more(advanced = false, photoMemoryAllowed = true)
    // Picture routines (2026-11-07) is a new kind offered under More, ahead of the retire-candidates.
    assertEquals(listOf(HomeWidgetStore.KIND_ROUTINES, HomeWidgetStore.KIND_TODAY, HomeWidgetStore.KIND_QUICK_CONTROLS,
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
    val all = HomeWidgetPicker.everyday + HomeWidgetPicker.more(advanced = true, photoMemoryAllowed = true)
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

  @Test fun everyKind_hasALabelAndIcon() {
    (HomeWidgetPicker.everyday + HomeWidgetPicker.more(true, true)).forEach {
      assertNotEquals(it, "Widget", HomeWidgetPicker.label(it))
      assertTrue(it, HomeWidgetPicker.icon(it).isNotBlank())
    }
  }
}
