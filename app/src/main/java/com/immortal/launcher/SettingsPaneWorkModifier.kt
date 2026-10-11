/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

/** Plain, main-owned attribution; changes do not invalidate Compose measurement. */
private class SettingsPaneWorkObservation(val pane: SettingsPaneWorkId, var active: Boolean)

/**
 * Retain one modifier element for the keyed pane's lifetime. SideEffect publishes committed
 * activation before layout without introducing measurement-observed snapshot state. The child
 * receives the same constraints and placement; no forced measure or altered retention policy.
 */
@Composable
internal fun rememberSettingsPaneWorkModifier(page: String, active: Boolean): Modifier {
  if (!BuildConfig.PORTAL_SETTINGS_PANE_DIAGNOSTICS) return Modifier
  val observation = remember(page) {
    val pane = when (page) {
      "appearance" -> SettingsPaneWorkId.APPEARANCE
      "touch" -> SettingsPaneWorkId.TOUCH
      "wallpaper" -> SettingsPaneWorkId.WALLPAPER
      "quickbar" -> SettingsPaneWorkId.QUICKBAR
      else -> SettingsPaneWorkId.OTHER
    }
    SettingsPaneWorkObservation(pane, active)
  }
  SideEffect { observation.active = active }
  return remember(observation) {
    Modifier.layout { measurable, constraints ->
      val span = FramePerformanceCapture.beginSettingsPaneWork(observation.pane, observation.active,
          constraints.minWidth, constraints.maxWidth, constraints.minHeight, constraints.maxHeight)
      val child = try { measurable.measure(constraints) } finally { span?.finish() }
      layout(child.width, child.height) { child.placeRelative(0, 0) }
    }
  }
}
