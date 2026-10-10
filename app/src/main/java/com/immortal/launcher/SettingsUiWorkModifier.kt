/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout

// Stable elements prevent the observer itself from invalidating measure on recomposition.
// The bounded cache retains fixed enum values only, never a capture, Context or UI instance.
private val settingsUiMeasurementModifiers: List<Modifier> by lazy {
  SettingsUiWorkStage.entries.map { stage ->
    if (stage.event) Modifier else Modifier.layout { measurable, constraints ->
      val span = FramePerformanceCapture.beginSettingsUiWork(stage)
      val child = try { measurable.measure(constraints) } finally { span?.finish() }
      layout(child.width, child.height) { child.placeRelative(0, 0) }
    }
  }
}

/** No measurement node when composition occurs outside an explicit capture. */
internal fun Modifier.settingsUiWorkMeasure(stage: SettingsUiWorkStage): Modifier {
  if (stage.event || !FramePerformanceCapture.settingsUiWorkEnabled()) return this
  return then(settingsUiMeasurementModifiers[stage.ordinal])
}
