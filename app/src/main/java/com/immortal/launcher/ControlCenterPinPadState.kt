/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * PIN pad entry (pure reducer, unit tested). Digits fill up to [length]; the full code is handed
 * back once for checking ([busy] until the answer). A wrong code clears the dots, shakes them
 * ([shakes] counts failures so the UI can replay the shake) and shows [error]. After
 * [MAX_TRIES] wrong codes in a row the pad rests for [LOCKOUT_MS] before accepting digits again.
 */
internal data class CcPinEntry(
    val length: Int,
    val digits: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val shakes: Int = 0,
    val failures: Int = 0,
    val lockedUntil: Long = 0L,
) {
  fun locked(now: Long): Boolean = now < lockedUntil

  /** A digit (0-9) or [DELETE]. Returns the new entry and the code to check, when complete. */
  fun press(key: Char, now: Long): Pair<CcPinEntry, String?> {
    if (busy || locked(now)) return this to null
    if (key == DELETE) return copy(digits = digits.dropLast(1)) to null
    if (!key.isDigit() || digits.length >= length) return this to null
    val next = copy(digits = digits + key, error = null)
    return if (next.digits.length == length) next.copy(busy = true) to next.digits else next to null
  }

  /** The code was refused: clear, shake, maybe rest. */
  fun failed(now: Long, message: String = WRONG): CcPinEntry {
    val f = failures + 1
    val rest = f >= MAX_TRIES
    return copy(digits = "", busy = false, shakes = shakes + 1, failures = if (rest) 0 else f,
        lockedUntil = if (rest) now + LOCKOUT_MS else lockedUntil,
        error = if (rest) "Too many tries — wait 30 seconds" else message)
  }

  /** The check couldn't run (not connected): clear without counting a failure. */
  fun unchecked(message: String): CcPinEntry = copy(digits = "", busy = false, error = message)

  companion object {
    const val DELETE = '<'
    const val WRONG = "Wrong code"
    const val MAX_TRIES = 5
    const val LOCKOUT_MS = 30_000L
  }
}
