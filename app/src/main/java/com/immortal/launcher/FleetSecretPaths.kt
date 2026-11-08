/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File

/**
 * Paths the authenticated fleet file routes must refuse, even though the app itself can read
 * them: the YouTube sign-in store (encrypted tokens) under the app's `no_backup/youtube`.
 * Checked on canonical paths, so `..` and symlinks can't reach it, and without touching the
 * Android context (the read route is context-free by design).
 */
internal object FleetSecretPaths {
  private val protectedSegments = listOf("no_backup", "youtube")

  fun isBlocked(target: File): Boolean {
    val resolved = runCatching { target.canonicalFile }.getOrNull() ?: return true
    val parts = resolved.path.split(File.separatorChar).filter { it.isNotEmpty() }
    return parts.windowed(protectedSegments.size).any { it == protectedSegments }
  }
}
