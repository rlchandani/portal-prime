/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Counts native first-frame admission and loads only; no URLs, script or page content. */
internal object WebAppOpeningDiagnostics {
  private val hardwareFrames = AtomicLong()
  private val softwareFrames = AtomicLong()
  private val creations = AtomicLong()
  private val earlyCreations = AtomicLong()
  private val loads = AtomicLong()
  private val finishes = AtomicLong()
  fun frameReady(hardware: Boolean) {
    if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS)
      (if (hardware) hardwareFrames else softwareFrames).incrementAndGet()
  }
  fun creationStarting() {
    if (!BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) return
    if (creations.incrementAndGet() > hardwareFrames.get() + softwareFrames.get())
      earlyCreations.incrementAndGet()
  }
  fun loadRequested() { if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) loads.incrementAndGet() }
  fun pageFinished() { if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) finishes.incrementAndGet() }
  fun snapshot() = JSONObject().put("hardwareFirstFrames", hardwareFrames.get())
      .put("softwareFirstFrames", softwareFrames.get()).put("creationStarts", creations.get())
      .put("creationBeforeFirstFrame", earlyCreations.get()).put("urlLoads", loads.get())
      .put("pageFinishes", finishes.get())
}
