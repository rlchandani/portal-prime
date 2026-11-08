/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.youtube.YtKeystoreSecureStore
import com.immortal.launcher.youtube.YtSecureStore
import java.io.File

/**
 * A guest-card credential. It never lives in SharedPreferences (fleet backups and settings
 * snapshots copy those): it is an AES-GCM file under `no_backup/guest`, keyed by a non-exportable
 * Keystore key, and [FleetSecretPaths] blocks that directory from the fleet file routes. Nothing
 * here logs the value; failures read as "not set".
 */
internal open class GuestSecret(private val secure: YtSecureStore, private val accepts: (String) -> Boolean) {
  fun read(): String = runCatching { secure.load() }.getOrNull().orEmpty()

  fun isSet(): Boolean = read().isNotEmpty()

  /** Stores [value]; false when it is invalid or storage failed. Blank clears. */
  fun write(value: String): Boolean {
    if (value.isEmpty()) return clear()
    if (!accepts(value)) return false
    return runCatching { secure.save(value) }.isSuccess
  }

  fun clear(): Boolean = runCatching { secure.clear() }.isSuccess

  /** Stores a value that passed an older, looser check ([GuestSecretsMigration] only). */
  internal fun writeMigrated(value: String): Boolean = runCatching { secure.save(value) }.isSuccess

  companion object {
    const val DIR = "guest"

    internal fun keystore(context: Context, file: String, alias: String): YtSecureStore =
        YtKeystoreSecureStore(File(File(context.applicationContext.noBackupFilesDir, DIR), file), alias)
  }
}

/** The guest Wi-Fi password. */
internal class GuestPasswordStore(secure: YtSecureStore) : GuestSecret(secure, ::isValid) {
  companion object {
    const val DIR = GuestSecret.DIR
    /** WPA passphrases are 8–63 characters, a raw key 64 hex digits; WEP keys are shorter. */
    const val MAX_LENGTH = 64

    fun isValid(password: String): Boolean =
        password.length in 1..MAX_LENGTH && password.none { it.isISOControl() }

    @Volatile private var instance: GuestPasswordStore? = null

    fun get(context: Context): GuestPasswordStore = instance ?: synchronized(this) {
      instance ?: GuestPasswordStore(keystore(context, "wifi.bin", "immortal.guest.wifi")).also { instance = it }
    }
  }
}

/** The door code ([GuestDoorCode]): hidden on the card until tapped, never exported. */
internal class GuestDoorCodeStore(secure: YtSecureStore) : GuestSecret(secure, GuestDoorCode::isValid) {
  companion object {
    @Volatile private var instance: GuestDoorCodeStore? = null

    fun get(context: Context): GuestDoorCodeStore = instance ?: synchronized(this) {
      instance ?: GuestDoorCodeStore(keystore(context, "door.bin", "immortal.guest.door")).also { instance = it }
    }
  }
}

/**
 * Moves the first card's plain-text door code out of SharedPreferences into [GuestDoorCodeStore],
 * deleting the plain copy only once the secret is safely written. Idempotent; runs off the main
 * thread before anything reads the door code.
 */
internal object GuestSecretsMigration {
  interface LegacySource {
    fun read(): String?
    fun drop(): Boolean
  }

  /** True when no plain copy remains. A newer secret always wins over the old value. */
  @Synchronized fun moveDoorCode(legacy: LegacySource, store: GuestSecret): Boolean {
    val old = legacy.read()?.trim() ?: return true
    val keep = old.isNotEmpty() && GuestDoorCode.isStorable(old) && !store.isSet()
    if (keep && !store.writeMigrated(old)) return false
    return legacy.drop()
  }

  fun run(context: Context): Boolean = moveDoorCode(object : LegacySource {
    override fun read() = GuestConfig.legacyDoorCode(context)
    override fun drop() = GuestConfig.dropLegacyDoorCode(context)
  }, GuestDoorCodeStore.get(context))
}
