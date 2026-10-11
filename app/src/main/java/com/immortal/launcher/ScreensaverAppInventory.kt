/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap

internal data class ScreensaverAppInventory(
  val apps: List<AppEntry>, val haPackage: String?, val haIcon: ImageBitmap?,
)

/** Called on IO; the default reader shares Home's package-aware snapshot. */
internal fun loadScreensaverAppInventory(
  context: Context,
  loadInstalledApps: () -> List<AppEntry> = { loadApprovedInstalledApps(context) },
  findHaPackage: () -> String? = { ScreensaverDismiss.installedHaPackage(context) },
  loadHaIcon: (String) -> ImageBitmap? = { ScreensaverDismiss.appIcon(context, it) },
): ScreensaverAppInventory {
  val apps = loadInstalledApps()
  val haPackage = findHaPackage()
  val haIcon = haPackage?.let { pkg ->
    apps.firstOrNull { it.component.packageName == pkg }?.icon ?: loadHaIcon(pkg)
  }
  return ScreensaverAppInventory(apps, haPackage, haIcon)
}
