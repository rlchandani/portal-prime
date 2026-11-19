/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Token buckets per caller and kind of request (pure, unit tested), plus one Portal-wide bucket
 * so several phones together can't flood Home Assistant. Callers are opaque keys (a hash of the
 * session, never the token). Over the limit = `429` with a retry hint; nothing queues.
 */
internal class CcRemoteLimiter {
  enum class Kind(val perSecond: Double, val burst: Double) {
    /** The phone polls state about every 1.5 s while open; bursts cover a resume. */
    STATE(4.0, 10.0),
    /** Taps, slider releases, scenes. */
    ACT(3.0, 8.0),
    /** Snapshots: a full view refreshes about once a second, a grid of five every few. */
    CAMERA(4.0, 12.0),
    PLAN(1.0, 4.0),
  }

  private class Bucket(var tokens: Double, var at: Long)

  private val buckets = HashMap<String, Bucket>()

  /** 0 = allowed (one token taken); else milliseconds until the next token. */
  @Synchronized
  fun take(caller: String, kind: Kind, now: Long): Long {
    if (buckets.size > MAX_BUCKETS) buckets.entries.removeAll { now - it.value.at > IDLE_MS }
    val own = retry(bucket("$caller/${kind.name}", kind, now), kind, now)
    val all = retry(bucket("*/${kind.name}", kind, now), kind, now, GLOBAL_FACTOR)
    if (own > 0 || all > 0) return maxOf(own, all)
    bucket("$caller/${kind.name}", kind, now).tokens -= 1.0
    bucket("*/${kind.name}", kind, now).tokens -= 1.0
    return 0
  }

  private fun bucket(key: String, kind: Kind, now: Long): Bucket = buckets.getOrPut(key) { Bucket(kind.burst * if (key.startsWith("*")) GLOBAL_FACTOR else 1.0, now) }

  private fun retry(b: Bucket, kind: Kind, now: Long, factor: Double = 1.0): Long {
    val rate = kind.perSecond * factor
    b.tokens = minOf(kind.burst * factor, b.tokens + (now - b.at).coerceAtLeast(0) * rate / MS_PER_S)
    b.at = now
    return if (b.tokens >= 1.0) 0 else Math.ceil((1.0 - b.tokens) * MS_PER_S / rate).toLong().coerceAtLeast(1)
  }

  private companion object {
    const val MS_PER_S = 1000.0
    /** All phones together get this many times one phone's budget. */
    const val GLOBAL_FACTOR = 3.0
    const val MAX_BUCKETS = 64
    const val IDLE_MS = 10 * 60_000L
  }
}
