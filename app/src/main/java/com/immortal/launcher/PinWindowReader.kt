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
    recyclePinWindows(windows)
  }
}

/**
 * Select a foreground application using window metadata before requesting any roots.
 * A root lookup can wait on another process, so inactive/occluded roots are only read by
 * the full snapshot above when a pinned session actually needs their identities.
 */
@Suppress("DEPRECATION") // Portal API 29 pools windows and nodes.
internal fun readPinAppWindow(service: AccessibilityService): PinAppWindow? {
  val windows = try {
    service.windows
  } catch (_: RuntimeException) {
    return null
  } ?: return null

  try {
    val candidates = windows.mapNotNull { window ->
      try {
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@mapNotNull null
        val id = window.id.takeIf { it >= 0 } ?: return@mapNotNull null
        val focused = window.isFocused
        val active = window.isActive
        if (!focused && !active) return@mapNotNull null
        ForegroundCandidate(window, id, focused, active)
      } catch (_: RuntimeException) {
        null // Reject stale metadata before acquiring this window's root.
      }
    }
    for (candidate in candidates) {
      if (candidate.focused) candidate.read()?.let { return it }
    }
    for (candidate in candidates) {
      // A failed focused root is not retried merely because its window is also active.
      if (!candidate.focused && candidate.active) candidate.read()?.let { return it }
    }
    return null
  } finally {
    recyclePinWindows(windows)
  }
}

private class ForegroundCandidate(
    val window: AccessibilityWindowInfo,
    val id: Int,
    val focused: Boolean,
    val active: Boolean,
) {
  @Suppress("DEPRECATION")
  fun read(): PinAppWindow? = try {
    val root = window.root
    if (root == null) null else try {
      val pkg = root.packageName?.toString()?.takeIf { it.isNotBlank() }
      pkg?.let { PinAppWindow(it, id, focused, active) }
    } finally {
      try {
        root.recycle()
      } catch (_: RuntimeException) {
        // Node release was attempted once; the owning window is still released by the caller.
      }
    }
  } catch (_: RuntimeException) {
    null // An unavailable root/package must not prevent another candidate or active fallback.
  }
}

/** Keep exception-isolated cleanup outside the caller's inlined early-return control flow. */
@Suppress("DEPRECATION")
private fun recyclePinWindows(windows: List<AccessibilityWindowInfo>) {
  for (window in windows) {
    try {
      window.recycle()
    } catch (_: RuntimeException) {
      // Release every owner, including skipped windows and those after a stale-object failure.
    }
  }
}
