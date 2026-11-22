/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import android.content.Context
import android.util.Log
import com.immortal.launcher.youtube.YtKeystoreSecureStore
import com.immortal.launcher.youtube.YtSecureStore
import java.io.File

/**
 * Personalization profiles at rest: one file per scope, `no_backup/memory/<scope>.bin`, each sealed
 * with AES-256-GCM under the non-exportable Keystore alias [ALIAS] (plan §3.2).
 *
 * - `no_backup`: never in Android backup. It is also not among the sealed settings backup's data
 *   files ([com.immortal.launcher.FleetBackupArchive.DATA_FILES], owner decision D10: memory is
 *   rebuildable), and [com.immortal.launcher.FleetSecretPaths] blocks it from every fleet file route.
 * - An unreadable file (key lost, corrupt, another scope's file) loads as an empty profile and is
 *   left in place until the next save or Forget; nothing here throws to the caller.
 * - Nothing is logged but scope tokens and exception classes; profile content never is.
 *
 * Slice 0: load/save/forget only. No writer calls [save] yet; debouncing is the slice 1 writer's job.
 */
internal class MemoryProfileStore(
    private val dir: File,
    private val secureFor: (File) -> YtSecureStore,
    private val log: (String) -> Unit,
) {
  /** The profile for [scope]; empty when there is none or it can't be read. */
  @Synchronized
  fun load(scope: MemoryScope): MemoryProfile {
    val text = try {
      secure(scope).load()
    } catch (e: Exception) {
      log("memory ${scope.token}: unreadable (${e.javaClass.simpleName}); starting empty")
      return MemoryProfile(scope)
    } ?: return MemoryProfile(scope)
    return try {
      MemoryProfileCodec.decode(text, scope)
    } catch (e: MemoryProfileCodec.FormatException) {
      log("memory ${scope.token}: bad format; starting empty")
      MemoryProfile(scope)
    }
  }

  /** Seals and writes [profile] (normalized). False when storage failed; the old file is then untouched. */
  @Synchronized
  fun save(profile: MemoryProfile): Boolean = try {
    secure(profile.scope).save(MemoryProfileCodec.encode(profile))
    true
  } catch (e: Exception) {
    log("memory ${profile.scope.token}: not saved (${e.javaClass.simpleName})")
    false
  }

  /** Deletes [scope]'s profile. True when nothing of it remains. */
  @Synchronized
  fun forget(scope: MemoryScope): Boolean {
    runCatching { secure(scope).clear() }
    return !file(scope).exists()
  }

  /** Forget all: every scope's profile, plus any stray file in the directory. True when it is empty. */
  @Synchronized
  fun forgetAll(): Boolean {
    dir.listFiles().orEmpty().forEach { it.delete() }
    return dir.listFiles().isNullOrEmpty()
  }

  /** The scopes that have a profile on disk. */
  @Synchronized
  fun scopes(): List<MemoryScope> = dir.listFiles().orEmpty()
      .filter { it.isFile && it.name.endsWith(SUFFIX) }
      .mapNotNull { MemoryScope.fromToken(it.name.removeSuffix(SUFFIX)) }
      .sortedBy { it.token }

  private fun file(scope: MemoryScope) = File(dir, scope.token + SUFFIX)

  private fun secure(scope: MemoryScope) = secureFor(file(scope))

  companion object {
    const val DIR = "memory"
    const val ALIAS = "hearthpane.memory"
    private const val SUFFIX = ".bin"
    private const val TAG = "HearthpaneMemory"

    @Volatile private var instance: MemoryProfileStore? = null

    fun get(context: Context): MemoryProfileStore = instance ?: synchronized(this) {
      instance ?: MemoryProfileStore(
          File(context.applicationContext.noBackupFilesDir, DIR),
          { file -> YtKeystoreSecureStore(file, ALIAS) },
          { Log.i(TAG, it) },
      ).also { instance = it }
    }
  }
}
