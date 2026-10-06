/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.Intent
import android.util.Log

/** Bounded recovery for Meta's cold bootstrap HOME redirect, distinct from a user Home press. */
internal class ColdPortalHomeRecovery(
    private val windowMs: Long = 30_000L,
    private val maxAttempts: Int = 3,
) {
  private var recoveredBridgeAt = 0L
  private var attempts = 0

  @Synchronized
  fun claim(
      bridgeAt: Long,
      now: Long,
      portalHomeHandoff: Boolean,
      isMainHome: Boolean,
      clearsTop: Boolean,
      resetsTask: Boolean,
      reordersTask: Boolean = false,
  ): Boolean {
    if (!portalHomeHandoff || bridgeAt <= 0L || now - bridgeAt !in 0L..windowMs ||
        !isMainHome || !clearsTop || resetsTask || reordersTask) return false
    if (recoveredBridgeAt != bridgeAt) {
      recoveredBridgeAt = bridgeAt
      attempts = 0
    }
    if (attempts >= maxAttempts) return false
    attempts++
    return true
  }
}

internal object PortalHomeHandoffRecovery {
  private val recovery = ColdPortalHomeRecovery()

  /** Recover before onResume ends the bridge and re-arms Immortal's screensaver. */
  fun recoverColdHome(activity: Activity, incoming: Intent?): Boolean {
    if (incoming == null) return false
    if (!recovery.claim(
        bridgeAt = DreamPolicy.bridgeAt,
        now = System.currentTimeMillis(),
        portalHomeHandoff = DreamPolicy.inStockHandoff && DreamPolicy.portalHomeHandoff,
        isMainHome = incoming.action == Intent.ACTION_MAIN &&
            incoming.hasCategory(Intent.CATEGORY_HOME),
        clearsTop = incoming.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0,
        resetsTask = incoming.flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED != 0,
        reordersTask = incoming.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0,
    )) return false

    Log.i("ImmortalPortalHome", "Recovering cold Meta Home bootstrap redirect")
    return PortalHomeBridge.reopenAfterBootstrap(activity)
  }
}
