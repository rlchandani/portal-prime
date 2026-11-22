/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtSecureStore

/**
 * Where [HouseholdKey] keeps the household key, and its one-time move out of plain prefs (plan H1).
 *
 * - [sealed] is the home: AES-GCM under a non-exportable Keystore key, in `no_backup` (production:
 *   [com.immortal.launcher.youtube.YtKeystoreSecureStore]).
 * - [plain] is the legacy prefs entry (hex). It is still the way a key ARRIVES from a sealed
 *   settings backup restore, and the fallback when the Keystore can't seal. Whenever it holds a
 *   valid key that key wins (it is the newest write) and is moved into [sealed]; the plain copy is
 *   removed only after the sealed copy is safely written.
 *
 * No failure loses the key: a Keystore that can't seal keeps the plain entry (and keeps working);
 * one that can't open the sealed copy reads as "no key" for that call but keeps the file. Once
 * the sealed copy is the only one, its opened value is cached (so hot paths such as every bus
 * packet don't cost a Keystore call); every value is handed out as a copy. Nothing here logs key
 * material.
 */
internal class HouseholdKeyVault(
    private val plain: PlainSlot,
    private val sealed: YtSecureStore,
    private val log: (String) -> Unit,
) {
  /** The legacy SharedPreferences entry. [write] and [remove] report whether the commit landed. */
  interface PlainSlot {
    fun read(): String?
    fun write(hex: String): Boolean
    fun remove(): Boolean
  }

  @Volatile private var cached: ByteArray? = null

  @Synchronized
  fun get(): ByteArray? {
    plainKey()?.let { return migrate(it).copyOf() }
    cached?.let { return it.copyOf() }
    val text = try {
      sealed.load()
    } catch (e: Exception) {
      log("household key: sealed copy unreadable (${e.javaClass.simpleName}); kept")
      return null
    }
    val key = text?.let(HouseholdKey::fromHex)?.takeIf(HouseholdKey::isValidKey) ?: return null
    cached = key
    return key.copyOf()
  }

  /** Stores [key], replacing any previous one. False for invalid material or when nothing landed. */
  @Synchronized
  fun set(key: ByteArray): Boolean {
    if (!HouseholdKey.isValidKey(key)) return false
    val hex = HouseholdKey.toHex(key)
    val sealedOk = seal(hex)
    // A plain copy that can't be removed would shadow the new key, so overwrite it instead.
    val stored = if (sealedOk) plain.read() == null || plain.remove() || plain.write(hex)
        else plain.write(hex).also { ok -> if (ok) dropSealed() }
    // Only the sealed copy is cached: while the key is plain, the prefs entry stays the source of truth.
    cached = if (stored && sealedOk) key.copyOf() else null
    return stored
  }

  /**
   * After a backup restore rewrote the prefs file: a restored key is adopted (and sealed);
   * a restore without one leaves the Portal without a key, exactly as replacing the plain prefs
   * file did before the key moved.
   */
  @Synchronized
  fun adoptRestored() {
    if (plainKey() != null) {
      get()
      return
    }
    dropSealed()
    cached = null
  }

  private fun plainKey(): ByteArray? = plain.read()?.let(HouseholdKey::fromHex)?.takeIf(HouseholdKey::isValidKey)

  private fun migrate(key: ByteArray): ByteArray {
    cached = null
    if (!seal(HouseholdKey.toHex(key))) {
      log("household key: sealing unavailable; plain copy kept")
    } else if (plain.remove()) {
      cached = key.copyOf()
      log("household key: moved to sealed storage")
    }
    return key
  }

  private fun seal(hex: String): Boolean = try {
    sealed.save(hex)
    true
  } catch (e: Exception) {
    log("household key: seal failed (${e.javaClass.simpleName})")
    false
  }

  private fun dropSealed() {
    try {
      sealed.clear()
    } catch (e: Exception) {
      log("household key: could not drop sealed copy (${e.javaClass.simpleName})")
    }
  }
}
