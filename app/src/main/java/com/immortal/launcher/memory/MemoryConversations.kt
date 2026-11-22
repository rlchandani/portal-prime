/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

/**
 * Which Jarvis conversation is under way and whose it is, so a conversation's identity is frozen at its open
 * (plan §2.2) and "forget that" knows what the latest one touched. Jarvis runs one conversation at a time, so this
 * keeps the latest only. Immutable; each call returns the next value. Pure.
 */
internal data class MemoryConversations(
    val currentId: String? = null,
    val currentAccess: MemoryAccess? = null,
    val openedAtMs: Long = 0L,
    /** Fact ids the latest finished conversation added evidence to, and when it finished. */
    val lastTouched: Set<String> = emptySet(),
    val lastTouchedScope: MemoryScope? = null,
    val lastTouchedAtMs: Long = 0L,
) {
  fun opened(id: String, access: MemoryAccess, nowMs: Long) = copy(currentId = id, currentAccess = access, openedAtMs = nowMs)

  /** The access frozen when conversation [id] opened, if it is the current one and not stale. */
  fun accessFor(id: String, nowMs: Long): MemoryAccess? =
      currentAccess.takeIf { id == currentId && nowMs - openedAtMs in 0..MAX_CONVERSATION_MS }

  /** The access of the conversation under way now (for `hearthpane.memory` mid-conversation), if any. */
  fun liveAccess(nowMs: Long): MemoryAccess? = currentId?.let { accessFor(it, nowMs) }

  fun finished(id: String, scope: MemoryScope?, touched: Set<String>, nowMs: Long) = copy(
      currentId = currentId.takeIf { it != id }, currentAccess = currentAccess.takeIf { id != currentId },
      lastTouched = touched, lastTouchedScope = scope, lastTouchedAtMs = nowMs,
  )

  /** What "forget that" means now: the latest conversation's facts, for [TOUCHED_FOR_MS] after it ended. */
  fun touchedNow(scope: MemoryScope, nowMs: Long): Set<String> =
      lastTouched.takeIf { scope == lastTouchedScope && nowMs - lastTouchedAtMs in 0..TOUCHED_FOR_MS }.orEmpty()

  companion object {
    /** A conversation longer than this is treated as over (its frozen identity is not reused). */
    const val MAX_CONVERSATION_MS = 2 * 60 * 60 * 1000L
    const val TOUCHED_FOR_MS = 30 * 60 * 1000L
  }
}
