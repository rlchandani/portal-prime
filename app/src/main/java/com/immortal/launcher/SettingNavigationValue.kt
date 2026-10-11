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

internal sealed interface SettingNavigationValue {
  data object Loading : SettingNavigationValue
  data class Ready(val text: String) : SettingNavigationValue
  data object Unavailable : SettingNavigationValue
}

/** Service-backed registry labels must not query PackageManager during composition. */
internal suspend fun loadSettingNavigationValue(read: () -> String): SettingNavigationValue =
    withContext(Dispatchers.IO) {
      try { SettingNavigationValue.Ready(read()) }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { SettingNavigationValue.Unavailable }
    }
