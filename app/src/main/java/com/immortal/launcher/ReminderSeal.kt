/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Seals the reminders replica for the Portal↔Portal exchange, so private titles never cross the
 * LAN in the clear: AES-256-GCM under `HMAC-SHA256(householdKey, "hearthpane-reminders-seal|v1")`,
 * a random 96-bit IV, and AAD binding the exchange nonce and direction (a sealed request can't be
 * replayed as a reply, or into another exchange). Wire form: hex(`iv ‖ ciphertext+tag`). Pure JVM.
 */
internal object ReminderSeal {
  const val REQUEST = HouseholdSeal.REQUEST
  const val RESPONSE = HouseholdSeal.RESPONSE
  private val seal = HouseholdSeal("hearthpane-reminders-seal|v1", "hearthpane-reminders", 2 * 1024 * 1024)

  fun key(householdKey: ByteArray): ByteArray = seal.key(householdKey)

  fun seal(householdKey: ByteArray, nonce: String, direction: String, plain: String): String =
      seal.seal(householdKey, nonce, direction, plain)

  /** The plaintext, or null for anything malformed, tampered, sealed for another nonce/direction or key. */
  fun open(householdKey: ByteArray, nonce: String, direction: String, sealedHex: String?): String? =
      seal.open(householdKey, nonce, direction, sealedHex)
}
