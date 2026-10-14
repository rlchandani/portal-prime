/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/** Diagnostic builds expose work counts only, never package names or usage history. */
internal object RecentAppPreparationDiagnostics {
  private val queries = AtomicLong()
  private val labelReads = AtomicLong()
  private val completedLoads = AtomicLong()
  private val latestItems = AtomicLong()

  fun query() { if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) queries.incrementAndGet() }
  fun labelRead() { if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) labelReads.incrementAndGet() }
  fun completed(items: Int) {
    if (BuildConfig.PORTAL_HOME_UI_DIAGNOSTICS) {
      latestItems.set(items.toLong())
      completedLoads.incrementAndGet()
    }
  }
  fun snapshot(): JSONObject = JSONObject().put("queries", queries.get())
      .put("labelReads", labelReads.get()).put("completedLoads", completedLoads.get())
      .put("latestItems", latestItems.get())
}
