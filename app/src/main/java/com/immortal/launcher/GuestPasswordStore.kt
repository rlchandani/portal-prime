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
 * The guest Wi-Fi password. It is a credential, so it never lives in SharedPreferences (fleet
 * backups and settings snapshots copy those): it is an AES-GCM file under `no_backup/guest`,
 * keyed by a non-exportable Keystore key, and [FleetSecretPaths] blocks it from the fleet file
 * routes. Nothing here logs the value; failures read as "no password".
 */
internal class GuestPasswordStore(private val secure: YtSecureStore) {
  fun read(): String = runCatching { secure.load() }.getOrNull().orEmpty()

  fun isSet(): Boolean = read().isNotEmpty()

  /** Stores [password]; false when it is invalid or storage failed. Blank clears. */
  fun write(password: String): Boolean {
    if (password.isEmpty()) return clear()
    if (!isValid(password)) return false
    return runCatching { secure.save(password) }.isSuccess
  }

  fun clear(): Boolean = runCatching { secure.clear() }.isSuccess

  companion object {
    const val DIR = "guest"
    private const val FILE = "wifi.bin"
    private const val ALIAS = "immortal.guest.wifi"
    /** WPA passphrases are 8–63 characters, a raw key 64 hex digits; WEP keys are shorter. */
    const val MAX_LENGTH = 64

    fun isValid(password: String): Boolean =
        password.length in 1..MAX_LENGTH && password.none { it.isISOControl() }

    @Volatile private var instance: GuestPasswordStore? = null

    fun get(context: Context): GuestPasswordStore =
        instance ?: synchronized(this) {
          instance ?: GuestPasswordStore(
              YtKeystoreSecureStore(File(File(context.applicationContext.noBackupFilesDir, DIR), FILE), ALIAS))
              .also { instance = it }
        }
  }
}
