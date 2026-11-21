/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Persists one [OverlayHold] per owner ([slot]: "cc", "doorbell") as an [OverlayHoldRecord], so a
 * held critical overlay survives the process being replaced by an app update. Not a setting: it is
 * cleared as soon as the overlay is dismissed or its time is up. Main thread; writes are async.
 */
internal object OverlayHoldStore {
  private const val TAG = "HearthpaneOverlayHold"
  private const val PREFS = "overlay_holds"

  fun now(): Long = SystemClock.elapsedRealtime()

  fun save(context: Context, slot: String, held: OverlayHold.Held<JSONObject>?) {
    val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (held == null) { prefs.edit().remove(slot).apply(); return }
    prefs.edit().putString(slot, OverlayHoldRecord.encode(held, now(), bootCount(context)).toString()).apply()
  }

  fun load(context: Context, slot: String): OverlayHoldRecord.Restored? {
    val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val raw = prefs.getString(slot, null) ?: return null
    val restored = runCatching { OverlayHoldRecord.decode(JSONObject(raw), now(), bootCount(context)) }
        .onFailure { Log.w(TAG, "unreadable $slot hold; dropped", it) }.getOrNull()
    if (restored == null) prefs.edit().remove(slot).apply() // over, from another boot, or unreadable
    return restored
  }

  private fun bootCount(context: Context): Int =
      runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, OverlayHoldRecord.UNKNOWN_BOOT) }
          .getOrDefault(OverlayHoldRecord.UNKNOWN_BOOT)
}
