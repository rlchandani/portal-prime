/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * A Home Assistant read that can fail in ways the screen words differently: no address/token yet,
 * the token was refused, no answer at all, or an answer that wasn't usable.
 */
sealed interface HaResult<out T> {
  data class Ok<T>(val value: T) : HaResult<T>
  data object NotConfigured : HaResult<Nothing>
  /** HTTP 401/403: the long-lived token expired or was removed. */
  data object Unauthorized : HaResult<Nothing>
  /** No HTTP response (offline, DNS, refused, timeout). */
  data object Unreachable : HaResult<Nothing>
  /** HA answered, but with an error status or a body we couldn't read. */
  data class Failed(val code: Int) : HaResult<Nothing>

  /** A non-[Ok] result re-typed (it carries no value). */
  @Suppress("UNCHECKED_CAST")
  fun <R> cast(): HaResult<R> = when (this) {
    is Ok -> error("cast() is only for failures")
    else -> this as HaResult<R>
  }

  companion object {
    private val AUTH_CODES = setOf(401, 403)

    /** Classify an [Http.Outcome]; [parse] returning null makes an otherwise good answer [Failed]. */
    fun <T> of(outcome: Http.Outcome<String>, parse: (String) -> T?): HaResult<T> {
      val body = outcome.value
      return when {
        outcome.ok && body != null -> parse(body)?.let { Ok(it) } ?: Failed(outcome.code)
        outcome.code in AUTH_CODES -> Unauthorized
        outcome.code == Http.NO_RESPONSE -> Unreachable
        else -> Failed(outcome.code)
      }
    }
  }
}
