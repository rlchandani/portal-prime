/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis.wake

/**
 * The gap between hearing "Hey Jarvis" and Jarvis's card owning the mic. Hearthpane must not reopen its own mic
 * in that gap, or the two would race for it. The card pausing this screen confirms the handoff; this screen
 * resuming afterwards (the card closed) ends it. A handoff the card never confirms (the launch was dropped)
 * expires after [EXPIRY_MS], so listening can't stay off for good. Immutable and pure.
 */
internal data class WakeHandoff(val sinceMs: Long? = null, val covered: Boolean = false) {
  fun woke(nowMs: Long): WakeHandoff = WakeHandoff(sinceMs = nowMs)

  fun hostPaused(): WakeHandoff = if (sinceMs != null) copy(covered = true) else this

  fun hostResumed(): WakeHandoff = if (covered) WakeHandoff() else this

  fun active(nowMs: Long): Boolean = sinceMs != null && (covered || nowMs - sinceMs < EXPIRY_MS)

  companion object {
    /** A card normally covers the screen within ~300 ms; 3 s means the launch didn't happen. */
    const val EXPIRY_MS = 3_000L
  }
}
