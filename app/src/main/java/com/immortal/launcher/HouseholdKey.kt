/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import com.immortal.launcher.youtube.YtKeystoreSecureStore
import com.immortal.launcher.youtube.YtSecureStore
import java.io.File
import java.security.MessageDigest

/**
 * The household's shared secret for signing Portal↔Portal [HouseholdMessage]s. The phone (paired
 * with every Portal) generates it and provisions it over the authenticated companion channel
 * (`/remote/household`); Portals never exchange pairing tokens. Never returned: read-back exposes
 * only [fingerprint]. Not a user setting (excluded from the settings registry like other
 * credentials).
 *
 * At rest it is sealed with a non-exportable Android Keystore key under `no_backup/`[DIR]
 * ([HouseholdKeyVault]). The old plain prefs entry ([PREFS]/`k`) is migrated on first read and
 * remains the format sealed settings backups carry, so export and restore behave as before.
 */
internal object HouseholdKey {
  const val PREFS = "immortal_household"
  const val PLAIN_KEY = "k"
  const val DIR = "household"
  private const val FILE = "key.bin"
  private const val ALIAS = "immortal.household.key"
  private const val TAG = "HouseholdKey"
  const val SIZE = 32

  @Volatile private var holder: Pair<Context, HouseholdKeyVault>? = null

  fun isValidKey(key: ByteArray?): Boolean = key != null && key.size == SIZE

  /** The current key, or null until the phone has provisioned one. */
  fun get(context: Context): ByteArray? = vault(context).get()

  /** Stores [key] (replacing any previous one). Returns false for invalid key material. */
  fun set(context: Context, key: ByteArray): Boolean = vault(context).set(key)

  /** A sealed backup restore just rewrote [PREFS]: adopt (and seal) its key, or forget ours if it had none. */
  fun adoptRestored(context: Context) = vault(context).adoptRestored()

  /**
   * One vault per application context: production has exactly one, so the opened key is cached
   * for the process. JVM tests pass a fresh mock context each and get a fresh vault.
   */
  private fun vault(context: Context): HouseholdKeyVault {
    val app = context.applicationContext ?: context
    holder?.takeIf { it.first === app }?.let { return it.second }
    return synchronized(this) {
      holder?.takeIf { it.first === app }?.second ?: create(app).also { holder = app to it }
    }
  }

  private fun create(app: Context): HouseholdKeyVault {
    // Looked up per call (Android caches the instance), so a restore that replaces the file is seen.
    fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val plain = object : HouseholdKeyVault.PlainSlot {
      override fun read(): String? = prefs().getString(PLAIN_KEY, null)
      override fun write(hex: String): Boolean = prefs().edit().putString(PLAIN_KEY, hex).commit()
      override fun remove(): Boolean = prefs().edit().remove(PLAIN_KEY).commit()
    }
    // noBackupFilesDir is null only on a JVM test mock: no sealed home, so the plain entry stays (as before).
    val sealed = app.noBackupFilesDir?.let { YtKeystoreSecureStore(File(File(it, DIR), FILE), ALIAS) } ?: NoSealedStore
    return HouseholdKeyVault(plain, sealed) { runCatching { Log.i(TAG, it) } }
  }

  /** A sealed home that is never available: every seal fails, so the vault keeps the plain entry. */
  private object NoSealedStore : YtSecureStore {
    override fun load(): String? = null
    override fun save(text: String) = throw java.io.IOException("no sealed storage")
    override fun clear() = Unit
  }

  /** Short, non-secret identifier so the phone can tell whether a Portal holds the same key. */
  fun fingerprint(key: ByteArray): String =
      toHex(MessageDigest.getInstance("SHA-256").digest("immortal-household-fp".toByteArray() + key)).take(8)

  fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

  fun fromHex(text: String): ByteArray? {
    if (text.length % 2 != 0 || text.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return ByteArray(text.length / 2) { i -> text.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
  }
}
