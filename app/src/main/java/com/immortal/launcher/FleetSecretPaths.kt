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
 * them: the YouTube sign-in store (encrypted tokens) under the app's `no_backup/youtube`, and the
 * guest Wi-Fi password ([GuestPasswordStore]) under `no_backup/guest`.
 * Checked on canonical paths, so `..` and symlinks can't reach it, and without touching the
 * Android context (the read route is context-free by design).
 */
internal object FleetSecretPaths {
  private val protectedStores = listOf(listOf("no_backup", "youtube"), listOf("no_backup", GuestPasswordStore.DIR))

  fun isBlocked(target: File): Boolean {
    val resolved = runCatching { target.canonicalFile }.getOrNull() ?: return true
    val parts = resolved.path.split(File.separatorChar).filter { it.isNotEmpty() }
    return protectedStores.any { store -> parts.windowed(store.size).any { it == store } }
  }
}
