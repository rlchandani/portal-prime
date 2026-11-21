/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Which screensaver styles honour the photo-fit setting ([ScreensaverConfig.Settings.fit]). Pure;
 * Settings shows the Fit row only for these.
 *  - Smart frame and Portrait pair: the native stage covers or contains by fit on the Portal+
 *    ([PhotoFrameStagePolicy.presentation]) and on compact windows ([CompactStagePlan.plan]).
 *  - Classic: photos crop or letterbox by fit on the Portal+ and videos follow it everywhere
 *    (compact Classic photos always pan whole).
 *  - Not: Whole photo and the Gallery styles never crop; Split glance always covers its card;
 *    Shuffle and the engine styles never read the setting.
 */
internal object PhotoFitPolicy {
  private val HONOURING = setOf(
      ScreensaverConfig.MODE_SMART, ScreensaverConfig.MODE_PORTRAITS, ScreensaverConfig.MODE_CLASSIC)

  fun honoursFit(mode: String): Boolean = mode in HONOURING
}
