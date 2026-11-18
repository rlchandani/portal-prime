/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.io.File

/**
 * This Portal's cached copy of the floor plan (`files/control_center_plan.json`), written
 * atomically. Home Assistant's copy is the shared one; [newest] decides which wins on connect.
 */
internal object CcPlanStore {
  private const val TAG = "HearthpaneControl"
  private const val FILE = "control_center_plan.json"

  fun load(context: Context): CcPlan? = runCatching {
    val f = File(context.filesDir, FILE)
    if (f.exists()) CcPlanJson.decodeString(f.readText()) else null
  }.onFailure { Log.w(TAG, "plan cache unreadable (${it.javaClass.simpleName})") }.getOrNull()

  fun save(context: Context, plan: CcPlan): Boolean = runCatching {
    val dir = context.filesDir
    val tmp = File(dir, "$FILE.tmp")
    tmp.writeText(CcPlanJson.encode(plan).toString())
    check(tmp.renameTo(File(dir, FILE))) { "rename failed" }
  }.onFailure { Log.w(TAG, "plan cache not saved (${it.javaClass.simpleName})") }.isSuccess

  /** The plan to show: the newer of the HA and local copies (HA wins a tie: it's the shared one). */
  fun newest(ha: CcPlan?, local: CcPlan?): CcPlan? = when {
    ha == null -> local
    local == null -> ha
    local.updatedAt > ha.updatedAt -> local
    else -> ha
  }
}
