/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/**
 * Shared builder for the digital clock UI. Used by both the
 * [DigitalClockDreamService] (the actual screensaver) and the
 * [DigitalClockPreviewActivity] (in-app preview). Returns a root view
 * plus a [Controller] that the caller uses to update the time.
 *
 * Every face is drawn by one [DigitalDreamFaceView]. The caller owns the update loop: call
 * [update] each minute (each second when seconds are shown); the view redraws only on change,
 * except the analog sweep, which paces itself.
 */
object DigitalClockView {

  class Controller internal constructor(val root: View, internal val face: DigitalDreamFaceView)

  fun build(context: Context, settings: DigitalClockConfig.Settings): Controller {
    val root = FrameLayout(context)
    val face = DigitalDreamFaceView(context, settings)
    root.addView(face, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT))
    return Controller(root, face)
  }

  @Suppress("UNUSED_PARAMETER") // kept for the dream and preview call sites
  fun update(controller: Controller, settings: DigitalClockConfig.Settings) {
    controller.face.update()
  }
}
