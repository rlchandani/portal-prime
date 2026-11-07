/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.security.MessageDigest

/**
 * The household's shared secret for signing Portal↔Portal [HouseholdMessage]s. The phone (paired
 * with every Portal) generates it and provisions it over the authenticated companion channel
 * (`/remote/household`); Portals never exchange pairing tokens. Stored in app-private prefs and
 * never returned: read-back exposes only [fingerprint]. Not a user setting (excluded from the
 * settings registry like other credentials).
 */
internal object HouseholdKey {
  private const val PREFS = "immortal_household"
  private const val KEY = "k"
  const val SIZE = 32

  fun isValidKey(key: ByteArray?): Boolean = key != null && key.size == SIZE

  /** The current key, or null until the phone has provisioned one. */
  fun get(context: Context): ByteArray? =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
          ?.let(::fromHex)?.takeIf(::isValidKey)

  /** Stores [key] (replacing any previous one). Returns false for invalid key material. */
  fun set(context: Context, key: ByteArray): Boolean {
    if (!isValidKey(key)) return false
    return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toHex(key)).commit()
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
