/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtSecureStore

/**
 * Where [HouseholdKey] keeps the household key, and its move out of plain prefs (plan H1).
 *
 * - [sealed] is the new home: AES-GCM under a non-exportable Keystore key, in `no_backup`
 *   (production: [com.immortal.launcher.youtube.YtKeystoreSecureStore]).
 * - [plain] is the legacy prefs entry (hex) that every older build reads.
 *
 * **Rollback-safe phase ([retirePlaintext] = false, today).** Older official builds are routinely
 * reinstalled (bench, OTA timing tests), so the plain entry is KEPT and written on every [set]:
 * an older build always finds the current key. Reads prefer the sealed copy when it agrees with
 * the plain entry. Because only an older build (or a backup restore) changes the plain entry
 * alone, the plain entry decides presence and wins a disagreement, and the sealed copy follows it.
 *
 * **Finalize phase ([retirePlaintext] = true, a later release:
 * [HouseholdKey.RETIRE_PLAINTEXT]).** A valid plain entry is moved into [sealed] and then
 * removed; from then on the sealed copy alone is the key. Older builds would no longer find it.
 *
 * In both phases no failure loses the key: a Keystore that can't seal keeps the plain entry (and
 * keeps working); one that can't open the sealed copy never deletes it. The opened value is cached
 * (checked against the plain entry, so hot paths such as every bus packet don't cost a Keystore
 * call) and handed out as a copy. Nothing here logs key material.
 */
internal class HouseholdKeyVault(
    private val plain: PlainSlot,
    private val sealed: YtSecureStore,
    private val log: (String) -> Unit,
    private val retirePlaintext: Boolean = HouseholdKey.RETIRE_PLAINTEXT,
) {
  /** The legacy SharedPreferences entry. [write] and [remove] report whether the commit landed. */
  interface PlainSlot {
    fun read(): String?
    fun write(hex: String): Boolean
    fun remove(): Boolean
  }

  @Volatile private var cached: ByteArray? = null

  @Synchronized
  fun get(): ByteArray? = (if (retirePlaintext) getFinal() else getDual())?.copyOf()

  /** Stores [key], replacing any previous one. False for invalid material or when it didn't land. */
  @Synchronized
  fun set(key: ByteArray): Boolean {
    if (!HouseholdKey.isValidKey(key)) return false
    val hex = HouseholdKey.toHex(key)
    val sealedOk = seal(hex)
    val stored = when {
      // Dual-write: the plain entry is what older builds (and this one's presence check) read.
      !retirePlaintext -> plain.write(hex).also { ok -> if (ok && !sealedOk) dropSealed() }
      // A plain copy that can't be removed would shadow the new key, so overwrite it instead.
      sealedOk -> plain.read() == null || plain.remove() || plain.write(hex)
      else -> plain.write(hex).also { ok -> if (ok) dropSealed() }
    }
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

  // --- rollback-safe phase ----------------------------------------------------------------

  private fun getDual(): ByteArray? {
    val text = plain.read()
    if (text == null) {
      // Nothing plain while dual-writing means an older build or a restore removed the key.
      if (cached != null || sealedExists()) dropSealed()
      cached = null
      return null
    }
    val plainKey = HouseholdKey.fromHex(text)?.takeIf(HouseholdKey::isValidKey) ?: return null // garbage, as before
    val sealedKey = cached ?: openSealed()
    if (sealedKey != null && sealedKey.contentEquals(plainKey)) {
      cached = sealedKey
      return sealedKey
    }
    // Missing, unreadable or stale (an older build set a new key): the plain entry wins; keep it.
    cached = if (seal(text)) plainKey.copyOf() else null
    return plainKey
  }

  private fun sealedExists(): Boolean = try {
    sealed.load() != null
  } catch (e: Exception) {
    true
  }

  // --- finalize phase ---------------------------------------------------------------------

  private fun getFinal(): ByteArray? {
    plainKey()?.let { return migrate(it) }
    cached?.let { return it }
    return openSealed()?.also { cached = it }
  }

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

  // --- shared -----------------------------------------------------------------------------

  private fun plainKey(): ByteArray? = plain.read()?.let(HouseholdKey::fromHex)?.takeIf(HouseholdKey::isValidKey)

  private fun openSealed(): ByteArray? {
    val text = try {
      sealed.load()
    } catch (e: Exception) {
      log("household key: sealed copy unreadable (${e.javaClass.simpleName}); kept")
      return null
    }
    return text?.let(HouseholdKey::fromHex)?.takeIf(HouseholdKey::isValidKey)
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
