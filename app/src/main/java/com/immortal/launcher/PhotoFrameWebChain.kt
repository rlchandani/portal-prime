/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The built-in feed's source chain (Unsplash with a key, Picsum, Wikimedia, bundled): the first
 * source that answers wins. A source that fails is skipped for [cooldownMs], so a dead host isn't
 * re-hammered every photo; when every source fails or is cooling down, the chain is tried once more
 * ignoring cooldowns rather than leave the frame blank. Pure: sources, clock and log are passed in.
 */
internal class PhotoFrameWebChain<T : Any>(
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
  private val cooldownUntil = HashMap<String, Long>()

  /**
   * The first photo from [sources] (name to fetch), or null. [current] is checked around every
   * fetch: a superseded request returns null and hands a late photo to [discard].
   */
  fun fetch(sources: List<Pair<String, () -> T?>>, current: () -> Boolean, discard: (T) -> Unit): T? {
    val now = clock()
    for ((name, fetch) in sources) {
      if (!current()) return null
      if ((cooldownUntil[name] ?: 0L) > now) continue
      val photo = runCatching { fetch() }.getOrNull()
      if (!current()) { photo?.let(discard); return null }
      if (photo != null) return photo
      log("screensaver photo source '$name' unavailable; backing off")
      cooldownUntil[name] = now + cooldownMs
    }
    for ((_, fetch) in sources) {
      if (!current()) return null
      val photo = runCatching { fetch() }.getOrNull()
      if (!current()) { photo?.let(discard); return null }
      if (photo != null) return photo
    }
    log("screensaver: no photo source available")
    return null
  }

  /** The source is cooling down at [nowMs] (for tests and diagnostics). */
  fun coolingDown(name: String, nowMs: Long): Boolean = (cooldownUntil[name] ?: 0L) > nowMs

  companion object {
    /** A failed web source is skipped this long; the entry expires on its own so recovery self-heals. */
    const val COOLDOWN_MS = 5 * 60_000L
  }
}
