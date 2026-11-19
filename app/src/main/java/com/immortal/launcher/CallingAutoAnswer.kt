/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Immutable

/**
 * Grandma mode's auto-answer rules for this Portal. Set only on this Portal's own screen (never
 * from a phone, the phone remote, the fleet agent or another Portal): see [CallAutoAnswerStore].
 */
@Immutable
internal data class AutoAnswerRules(
    val grandmaMode: Boolean = false,
    /** Off by default (owner-approved Decisions #1). */
    val enabled: Boolean = false,
    /** Device ids (rooms in this home, paired phones) chosen on this Portal. */
    val allowed: Set<String> = emptySet(),
) {
  companion object { const val MAX_ALLOWED = 8 }
}

/**
 * When a ring may answer by itself. Pure and JVM-tested; quiet hours are checked again by
 * [CallPolicy.decide], which tests quiet before auto-answer.
 */
internal object CallAutoAnswer {
  /** "Not now" (or Decline) on an auto-answer silences auto-answer for that caller this long. */
  const val SNOOZE_AFTER_DECLINE_MS = 30 * 60_000L

  fun eligible(rules: AutoAnswerRules, callerId: String, kind: CallKind, quiet: Boolean, lastDeclinedMs: Long?, nowMs: Long): Boolean =
      rules.grandmaMode && rules.enabled && kind == CallKind.CALL && !quiet && callerId in rules.allowed &&
          (lastDeclinedMs == null || nowMs - lastDeclinedMs >= SNOOZE_AFTER_DECLINE_MS)

  /** The last time a call from [callerId] was declined on this Portal, from the local history. */
  fun lastDeclinedMs(history: List<CallHistoryEntry>, callerId: String): Long? = history
      .filter { it.peerId == callerId && it.direction == CallDirection.INCOMING && it.reason == CallEndReason.DECLINED }
      .maxOfOrNull { it.atMs }
}
