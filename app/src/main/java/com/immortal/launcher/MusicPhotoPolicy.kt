/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Presentation projection only; no preferences are written and sources stay unchanged. */
internal fun musicPhotoSettings(saved: ScreensaverConfig.Settings) =
    saved.copy(mode = ScreensaverConfig.MODE_WHOLE, includeVideo = false)
