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

internal sealed interface DeviceHealthLoad {
  data object Checking : DeviceHealthLoad
  data class Ready(val snapshot: DevicePermissions.Snapshot) : DeviceHealthLoad
  data object Unavailable : DeviceHealthLoad
}

/** Permission service queries may block on Binder; never perform them during composition. */
internal suspend fun loadDeviceHealth(query: () -> DevicePermissions.Snapshot): DeviceHealthLoad =
    withContext(Dispatchers.IO) {
      try {
        DeviceHealthLoad.Ready(query())
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        DeviceHealthLoad.Unavailable
      }
    }
