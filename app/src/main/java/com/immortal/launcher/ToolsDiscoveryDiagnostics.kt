/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Work counts only: no location, forecast content, credentials or force-work controls. */
internal object ToolsDiscoveryDiagnostics {
  private class Counts {
    val starts = AtomicLong()
    val finished = AtomicLong()
    val usable = AtomicLong()
    val hits = AtomicLong()
    fun snapshot() = JSONObject().put("starts", starts.get()).put("finished", finished.get())
        .put("usable", usable.get()).put("cacheHits", hits.get())
  }
  private val iss = Counts()
  private val aurora = Counts()
  private fun counts(id: String) = if (id == "iss") iss else aurora
  fun started(id: String) { if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) counts(id).starts.incrementAndGet() }
  fun finished(id: String, usable: Boolean) {
    if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) {
      if (usable) counts(id).usable.incrementAndGet()
      counts(id).finished.incrementAndGet()
    }
  }
  fun cacheHit(id: String) { if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) counts(id).hits.incrementAndGet() }
  fun snapshot() = JSONObject().put("iss", iss.snapshot()).put("aurora", aurora.snapshot())
}
