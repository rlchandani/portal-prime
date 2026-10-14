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

/** Last completed results remain visible while the next permission check runs or fails. */
internal data class DeviceHealthStatus(
    val snapshot: DevicePermissions.Snapshot? = null,
    val checking: Boolean = true,
    val failed: Boolean = false,
) {
  val canRemoveAdmin: Boolean get() = snapshot?.adminActive == true && !checking && !failed
  fun beginCheck(): DeviceHealthStatus = copy(checking = true, failed = false)
  fun received(result: DeviceHealthLoad): DeviceHealthStatus = when (result) {
    DeviceHealthLoad.Checking -> beginCheck()
    is DeviceHealthLoad.Ready -> DeviceHealthStatus(result.snapshot, checking = false)
    DeviceHealthLoad.Unavailable -> copy(checking = false, failed = true)
  }
}
