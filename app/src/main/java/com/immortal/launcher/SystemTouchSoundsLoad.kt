/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SystemTouchSoundsSnapshot(val canWrite: Boolean, val enabled: Boolean)

/** System settings and AudioService calls may block on Binder. */
internal suspend fun loadSystemTouchSounds(query: () -> SystemTouchSoundsSnapshot): SystemTouchSoundsSnapshot? =
    withContext(Dispatchers.IO) {
      try { query() } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }
    }

/** Independent of display writes: a slow sound-cache load cannot hold up brightness or volume. */
internal object SystemSoundsWrites {
  @Volatile private var instance: LatestSystemWrites<Unit, Boolean>? = null
  fun queue(context: android.content.Context): LatestSystemWrites<Unit, Boolean> = instance ?: synchronized(this) {
    instance ?: run {
      val application = context.applicationContext
      LatestSystemWrites<Unit, Boolean>(keyOf = { Unit },
          onFailure = { android.util.Log.w("SystemSoundsWrites", "Touch sound write failed", it) }) { enabled ->
        check(SystemSounds.setTouchSounds(application, enabled))
      }.also { instance = it }
    }
  }
}
