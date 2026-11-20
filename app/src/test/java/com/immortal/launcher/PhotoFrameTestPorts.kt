/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context

/**
 * For suites that build a [PhotoFrameController] without its constructor (Unsafe allocation or a
 * CALLS_REAL_METHODS mock): installs the real Android ports and the pure classes on them, exactly
 * as the constructor does, so the controller's own code runs against the suite's [context].
 */
internal object PhotoFrameTestPorts {
  fun install(controller: PhotoFrameController, context: Context) {
    val ports = PhotoFrameAndroidPorts(context)
    set(controller, "ports", ports)
    set(controller, "clock", ports)
    set(controller, "prefs", ports)
    set(controller, "presence", ports)
    set(controller, "decoder", ports)
    set(controller, "decodePlan", PhotoFrameDecodePlan(ports))
    set(controller, "schedule", PhotoFrameSchedule(ports))
    set(controller, "webChain", PhotoFrameWebChain<android.graphics.Bitmap>(ports::wallMs, {}))
  }

  private fun set(controller: PhotoFrameController, name: String, value: Any) {
    PhotoFrameController::class.java.getDeclaredField(name).apply { isAccessible = true; set(controller, value) }
  }
}
