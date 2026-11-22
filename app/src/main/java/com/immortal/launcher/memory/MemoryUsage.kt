/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import android.content.Context

/**
 * The usage choke points' one call (plan §3.6, slice 1 shadow mode): an app launched from Apps or Home, or a
 * Hearthpane screen (Music, Calls, Control Center, Settings, Home) coming to the front. Counted silently per hour
 * for slice 2's suggestions; nothing is shown yet. Off with the switch, and never for guests or children.
 */
internal object MemoryUsage {
  fun app(context: Context, packageName: String) = JarvisMemory.recordUsage(context, surfaceForApp(packageName))

  fun screen(context: Context, screenId: String) = JarvisMemory.recordUsage(context, surfaceForScreen(screenId))

  fun surfaceForApp(packageName: String) = "app:" + packageName.lowercase().filter { it.isLetterOrDigit() || it in "._" }

  fun surfaceForScreen(screenId: String) = "screen:" + screenId.lowercase().filter { it.isLetterOrDigit() || it == '_' }
}
