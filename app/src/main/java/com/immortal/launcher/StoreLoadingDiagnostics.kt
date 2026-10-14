/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Aggregate work counters only; no package names, credentials or notification data. */
internal object StoreLoadingDiagnostics {
  private val uiReads = AtomicLong()
  private val updateReads = AtomicLong()
  private val snapshotScans = AtomicLong()
  private val liveScans = AtomicLong()
  private val completedScans = AtomicLong()

  fun uiRead() { if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) uiReads.incrementAndGet() }
  fun updateRead() { if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) updateReads.incrementAndGet() }
  fun started(snapshot: Boolean) {
    if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS)
      (if (snapshot) snapshotScans else liveScans).incrementAndGet()
  }
  fun completed() { if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) completedScans.incrementAndGet() }

  fun handle(req: FleetHttpServer.Request): FleetHttpServer.Response {
    if (!BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) return FleetHttpServer.Response(403, "{}")
    if (req.method != "GET") return FleetHttpServer.Response(405, "{}")
    return FleetHttpServer.Response(200, JSONObject()
        .put("uiVersionReads", uiReads.get()).put("updateVersionReads", updateReads.get())
        .put("snapshotUpdateScans", snapshotScans.get()).put("liveUpdateScans", liveScans.get())
        .put("completedUpdateScans", completedScans.get()).toString())
  }
}
