/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/**
 * Grammar + state machine for Meta's own presence signal, read from logcat.
 *
 * The Portal's front-camera presence CV is platform-signature-gated, but its verdicts leak
 * into logcat: `aloha.UserPresenceManager` logs availability transitions on Android 10
 * models, and gen-1 devices log a `PresenceManager` heartbeat while a person is in view.
 * With READ_LOGS (already held for the fleet /logcat endpoint) tailing those tags gives
 * camera-quality presence with no camera contention — see [MetaPresenceReader].
 *
 * This file is the pure half (no Android): classifying lines and folding signals into a
 * present/absent/unknown verdict with time-based expiry. JVM-unit-tested.
 */
object MetaPresence {

  /** What a single log line tells us. */
  enum class Signal {
    /** Positive evidence a person is in view right now. */
    BEAT,
    /** The platform reports the room idle (weaker than a beat — apps also trigger it). */
    IDLE,
  }

  /** A beat older than this no longer proves presence (heartbeats come every ~30s). */
  const val BEAT_EXPIRY_MS = 75_000L

  /** How long after an idle report (with no newer beat) we call the room absent. */
  const val IDLE_GRACE_MS = 45_000L

  /**
   * Classify one logcat line, or null for chatter. Matched loosely on the message tail so
   * `-v epoch`/`-v time` prefixes and per-model tag spelling don't matter.
   */
  fun classify(line: String): Signal? =
      when {
        // Android 10 grammar: aloha.UserPresenceManager availability transitions.
        line.contains("Availability available", ignoreCase = true) -> Signal.BEAT
        // Gen-1 grammar: PresenceManager emits only while someone is present.
        line.contains("PresenceManager") && line.contains("presence detected", ignoreCase = true) ->
            Signal.BEAT
        // Portal 10 gen-2 grammar (observed live): a ~30s "onNotifyPresence presence updated"
        // heartbeat while a person is in view. The companion "onNotifyPresence, dreaming" line
        // must NOT match (substring guard: "presence updated").
        line.contains("onNotifyPresence") && line.contains("presence updated", ignoreCase = true) ->
            Signal.BEAT
        line.contains("Availability idle", ignoreCase = true) -> Signal.IDLE
        else -> null
      }

  /**
   * Folds [Signal]s into a rolling verdict.
   *
   * state() returns true (present: fresh beat), false (absent: idle reported after real
   * presence and the grace passed), or null (unknown: no evidence either way — beats went
   * stale without an idle, or nothing was ever seen). Callers treat null as "don't act".
   */
  class Tracker {
    private var lastBeatMs: Long = Long.MIN_VALUE
    private var idleSinceMs: Long = Long.MIN_VALUE
    private var everSawBeat = false

    fun onSignal(signal: Signal, atMs: Long) {
      when (signal) {
        Signal.BEAT -> {
          lastBeatMs = atMs
          everSawBeat = true
          idleSinceMs = Long.MIN_VALUE // fresh evidence cancels a pending idle
        }
        Signal.IDLE -> if (idleSinceMs == Long.MIN_VALUE) idleSinceMs = atMs
      }
    }

    fun state(nowMs: Long): Boolean? {
      val beatFresh = lastBeatMs != Long.MIN_VALUE && nowMs - lastBeatMs <= BEAT_EXPIRY_MS
      val idlePending = idleSinceMs != Long.MIN_VALUE && idleSinceMs > lastBeatMs
      return when {
        beatFresh && !idlePending -> true
        beatFresh && idlePending && nowMs - idleSinceMs <= IDLE_GRACE_MS -> true
        idlePending && everSawBeat && nowMs - idleSinceMs > IDLE_GRACE_MS -> false
        else -> null
      }
    }
  }
}
