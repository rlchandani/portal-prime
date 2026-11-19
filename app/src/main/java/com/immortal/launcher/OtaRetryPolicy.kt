/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Backoff state after the install confirmation for [versionCode] didn't go through. */
internal data class OtaConfirmRetry(
    val versionCode: Long,
    val failures: Int,
    val nextAtElapsedMs: Long,
)

/**
 * Retry schedule for [OtaError.CONFIRM_FAILED]. Unlike an APK Android rejected, a confirmation
 * that never went through (dialog covered, dismissed early, timed out) is worth retrying soon —
 * the old path blocked the version until the next 6-hour fetch. Retries still pass
 * [OtaIdlePolicy], so they only happen while the Portal is idle; the cap keeps a persistently
 * failing device to about one screen wake an hour.
 */
internal object OtaRetryPolicy {
  private const val MINUTE_MS = 60_000L
  val BACKOFF_MS = listOf(2 * MINUTE_MS, 5 * MINUTE_MS, 15 * MINUTE_MS, 30 * MINUTE_MS, 60 * MINUTE_MS)

  fun backoffMs(failures: Int): Long = BACKOFF_MS[(failures - 1).coerceIn(0, BACKOFF_MS.lastIndex)]

  /** The next retry after another confirmation failure; a different version starts over. */
  fun afterFailure(previous: OtaConfirmRetry?, versionCode: Long, nowElapsedMs: Long): OtaConfirmRetry {
    val failures = if (previous?.versionCode == versionCode) previous.failures + 1 else 1
    return OtaConfirmRetry(versionCode, failures, nowElapsedMs + backoffMs(failures))
  }

  /** Whether an automatic install of [versionCode] may run now. */
  fun due(retry: OtaConfirmRetry?, versionCode: Long, nowElapsedMs: Long): Boolean =
      retry == null || retry.versionCode != versionCode || nowElapsedMs >= retry.nextAtElapsedMs
}
