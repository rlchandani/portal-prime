/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityWindowInfo

internal data class PinAppWindow(
    val packageName: String,
    val windowId: Int,
    val focused: Boolean,
    val active: Boolean,
)

/**
 * Copies metadata from all application windows, including those underneath an accessibility
 * overlay. Only the root's package name is read; no nodes or windows escape this snapshot.
 * Missing windows can reflect occlusion, so this is not a task or process liveness check.
 * Package eligibility belongs to the controller, including filtering Immortal itself.
 */
@Suppress("DEPRECATION") // Portal API 29 pools windows and nodes.
internal fun readPinAppWindows(service: AccessibilityService): List<PinAppWindow> {
  val windows = try {
    service.windows
  } catch (_: RuntimeException) {
    return emptyList()
  } ?: return emptyList()

  try {
    return windows.mapNotNull { window ->
      try {
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
        val root = window.root ?: return@mapNotNull null
        try {
          val packageName = root.packageName?.toString()?.takeIf { it.isNotBlank() }
              ?: return@mapNotNull null
          val windowId = window.id.takeIf { it >= 0 } ?: return@mapNotNull null
          PinAppWindow(packageName, windowId, window.isFocused, window.isActive)
        } finally {
          // A stale pooled object must not prevent releasing the remaining owners.
          try {
            root.recycle()
          } catch (_: RuntimeException) {
            // Release was attempted exactly once.
          }
        }
      } catch (_: RuntimeException) {
        null // This window became stale; other windows may still be usable.
      }
    }
  } finally {
    // Release the entire acquired list, including skipped windows and failed root lookups.
    for (window in windows) {
      try {
        window.recycle()
      } catch (_: RuntimeException) {
        // Continue releasing other windows after a stale-object failure.
      }
    }
  }
}

internal fun readPinAppWindow(service: AccessibilityService): PinAppWindow? {
  val windows = readPinAppWindows(service)
  return windows.firstOrNull { it.focused } ?: windows.firstOrNull { it.active }
}
