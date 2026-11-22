/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyStore

/**
 * Calling stays in this home, on the LAN: the measurement-only cross-home code (linked homes,
 * in-person Portal pinning) is gone. A measurement build may have left its sealed vault, its
 * roster and their Keystore keys on a Portal; this deletes them once, off the main thread, then
 * leaves a marker so later starts skip the Keystore. A failed delete leaves no marker and is
 * retried at the next start.
 */
internal object RetiredHomeLinkCleanup {
  /** The retired stores' folders under `no_backup` (sealed linked homes; the pinned-Portal roster). */
  val DIRS = listOf("hearthpane_homelinks", "hearthpane_portal_roster")

  /** The retired Keystore aliases: the vault key, the roster key and this Portal's device key. */
  val ALIASES = listOf("hearthpane.calling.homelinks", "hearthpane.portal.roster", "hearthpane.portal.device.v1")

  const val MARKER = "hearthpane_homelink_retired"

  private const val TAG = "RetiredHomeLink"

  /** The Keystore operations [run] needs, so tests can stand in for `AndroidKeyStore`. */
  interface Keys {
    fun contains(alias: String): Boolean
    fun delete(alias: String)
  }

  fun install(context: Context) {
    val dir = context.applicationContext.noBackupFilesDir
    Thread({
      runCatching { run(dir, AndroidKeys()) }.onFailure { Log.w(TAG, "cleanup failed, retrying next start", it) }
    }, "hearthpane-homelink-cleanup").apply { isDaemon = true }.start()
  }

  /**
   * Deletes what's left in [noBackup] and [keys]; true once nothing remains (marker written). A
   * Keystore failure goes to [warn] (the log on a Portal) and leaves the marker unwritten.
   */
  fun run(noBackup: File, keys: Keys, warn: (String, Throwable) -> Unit = { m, t -> Log.w(TAG, m, t) }): Boolean {
    val marker = File(noBackup, MARKER)
    if (marker.exists()) return true
    val dirsGone = DIRS.map { File(noBackup, it) }.map { !it.exists() || it.deleteRecursively() }.all { it }
    // map, not all: one stuck key must not stop the others being deleted.
    val keysGone = ALIASES.map { alias ->
      runCatching { if (keys.contains(alias)) keys.delete(alias); !keys.contains(alias) }
          .onFailure { warn("could not delete a retired Keystore key", it) }
          .getOrDefault(false)
    }.all { it }
    if (!dirsGone || !keysGone) return false
    if (!noBackup.isDirectory && !noBackup.mkdirs()) return false
    return marker.createNewFile() || marker.exists()
  }

  private class AndroidKeys : Keys {
    private val store by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    override fun contains(alias: String) = store.containsAlias(alias)
    override fun delete(alias: String) = store.deleteEntry(alias)
  }
}
