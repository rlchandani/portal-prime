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
 * them.
 *
 * [isBlocked] (list, read and write): the YouTube sign-in store (encrypted tokens) under the
 * app's `no_backup/youtube`, the guest Wi-Fi password ([GuestPasswordStore]) under
 * `no_backup/guest`, Grandma mode's auto-answer rules ([AutoAnswerRepository]) under
 * `no_backup/hearthpane_grandma`, Calling's video messages ([CallMessageStore]) under
 * `no_backup/hearthpane_vmsg` and `no_backup/hearthpane_vmsg_out`, the phone outbox, and Who's
 * there's face templates ([WhosThereStore]) under `no_backup/whos_there`.
 *
 * [isReadBlocked] (`/fs/read`) refuses more: the app's whole private data directory
 * (`shared_prefs`, `databases`, `files`, `no_backup`, `app_*`, `cache`), which holds the household
 * key, the Home Assistant and music tokens, the phone sessions and every other credential; the
 * fleet manifest on external storage (it carries the fleet token); and the `/proc` entries that
 * reach another file or the process memory by a side door (`fd`, `map_files`, `mem`, `root`,
 * `cwd`, ...). Everything the fleet tools read stays readable: `/sdcard`, the installed APK under
 * `/data/app`, and `/proc/self/maps` and `cmdline`. Listing and writing are unchanged, so
 * `fleet-restore.sh --prefs` can still push an older plaintext snapshot back. Preferences leave
 * the Portal only sealed to the operator's USB-confirmed backup key ([FleetBackupRoutes]).
 *
 * Checked on canonical paths, so `..` and symlinks can't reach a store, and without touching the
 * Android context (the read route is context-free by design).
 */
internal object FleetSecretPaths {
  private val protectedStores = listOf(listOf("no_backup", "youtube"), listOf("no_backup", GuestPasswordStore.DIR),
      listOf("no_backup", AutoAnswerRepository.DIR),
      // Video messages (sealed, with their wrapped key) and the sender's temp take.
      listOf("no_backup", CallMessageStore.DIR), listOf("no_backup", CallMessageStore.OUT_DIR),
      // Messages waiting for a paired phone to collect them (transit-sealed).
      listOf("no_backup", CallPhoneOutbox.DIR),
      // Who's there templates and people (sealed) plus the audit file.
      listOf("no_backup", WhosThereStore.DIR))

  /**
   * Roots of an app's private data directory, as canonical path parts. `*` is one path part
   * (the package, a user id or a volume). `/data/data` is a symlink to `/data/user/0`, listed
   * anyway in case a device mounts it differently; `data_mirror` is the Android 11+ alias.
   */
  private val privateDataRoots = listOf(
      listOf("data", "data", "*"),
      listOf("data", "user", "*", "*"),
      listOf("data", "user_de", "*", "*"),
      listOf("data_mirror", "data_ce", "*", "*", "*"),
      listOf("data_mirror", "data_de", "*", "*", "*"))

  /** The fleet manifest the provisioning kit reads over adb; it holds the fleet token. */
  private val fleetManifest = listOf("Android", "data", "*", "files", "fleet")

  /** `/proc/<pid>/…` entries that lead to another file or to process memory. */
  private val procSideDoors = setOf("fd", "fdinfo", "map_files", "mem", "pagemap", "root", "cwd", "exe", "environ")

  fun isBlocked(target: File): Boolean {
    val parts = canonicalParts(target) ?: return true
    return protectedStores.any { store -> parts.windowed(store.size).any { it == store } }
  }

  /** True when `/fs/read` must refuse [target] (see the class comment). */
  fun isReadBlocked(target: File, canonicalize: (File) -> File = File::getCanonicalFile): Boolean {
    if (isBlocked(target)) return true
    val resolved = runCatching { canonicalize(target) }.getOrNull() ?: return true
    return isReadBlockedParts(partsOf(target.absoluteFile), partsOf(resolved))
  }

  /** The read rule on path parts: [raw] as requested (absolute) and [resolved] canonical. */
  internal fun isReadBlockedParts(raw: List<String>, resolved: List<String>): Boolean =
      isProcSideDoor(raw) || isProcSideDoor(resolved) ||
          privateDataRoots.any { startsWith(resolved, it) } || containsPattern(resolved, fleetManifest)

  private fun isProcSideDoor(parts: List<String>): Boolean =
      parts.firstOrNull() == "proc" && parts.drop(2).any { it in procSideDoors }

  private fun canonicalParts(target: File): List<String>? =
      runCatching { target.canonicalFile }.getOrNull()?.let(::partsOf)

  private fun partsOf(file: File): List<String> =
      file.path.split(File.separatorChar).filter { it.isNotEmpty() }

  private fun matches(parts: List<String>, pattern: List<String>): Boolean =
      parts.size == pattern.size && parts.indices.all { pattern[it] == "*" || pattern[it] == parts[it] }

  private fun startsWith(parts: List<String>, pattern: List<String>): Boolean =
      parts.size >= pattern.size && matches(parts.take(pattern.size), pattern)

  private fun containsPattern(parts: List<String>, pattern: List<String>): Boolean =
      parts.windowed(pattern.size).any { matches(it, pattern) }
}
