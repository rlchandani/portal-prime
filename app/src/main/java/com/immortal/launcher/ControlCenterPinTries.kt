/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Where the kids-mode PIN tries are read: [elapsed] is `SystemClock.elapsedRealtime()` (monotonic,
 * the clock the pad counts its rest in), [wall] is `System.currentTimeMillis()` and [boot] is
 * `Settings.Global.BOOT_COUNT` ([UNKNOWN_BOOT] when the platform doesn't say).
 */
internal data class CcPinClock(val elapsed: Long, val wall: Long, val boot: Int) {
  companion object {
    const val UNKNOWN_BOOT = -1

    fun now(c: Context): CcPinClock {
      val boot = runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT) }
          .getOrDefault(UNKNOWN_BOOT)
      return CcPinClock(SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot)
    }
  }
}

/**
 * The persisted tries: the count, the rest's end on both clocks, and enough about the boot it was
 * written in ([savedElapsed], [savedWall], [boot]) to tell whether the monotonic clock still applies.
 */
internal data class CcPinTriesRecord(
    val failures: Int,
    val lockedUntilElapsed: Long,
    val lockedUntilWall: Long,
    val savedElapsed: Long,
    val savedWall: Long,
    val boot: Int,
)

/** The restored state, in the pad's terms (rest end on the current elapsed-realtime clock). */
internal data class CcPinRestored(val failures: Int, val lockedUntil: Long)

/**
 * Pure, monotonic-safe restore of the kids PIN tries (unit tested).
 *
 * - Same boot: the rest end is kept on `elapsedRealtime`, so changing the wall clock can't shorten
 *   or lengthen it. Capped at one full rest from now in case the record is corrupt.
 * - After a reboot `elapsedRealtime` restarted, so the stored wall-clock end is used instead,
 *   clamped to `0..LOCKOUT_MS`: a clock set backwards can extend the rest by at most one rest, and
 *   a clock set forwards can only end it (a reboot already takes longer than the rest).
 *
 * A reboot is recognised by a different [CcPinClock.boot] count; without one, by elapsed realtime
 * going backwards or by the boot instant (wall − elapsed) moving more than [BOOT_DRIFT_MS].
 * The try count itself always survives; only a correct PIN clears it.
 */
internal object CcPinTriesClock {
  /** How far the estimated boot instant may move (NTP corrections) and still be the same boot. */
  const val BOOT_DRIFT_MS = 120_000L

  fun record(failures: Int, lockedUntilElapsed: Long, clock: CcPinClock): CcPinTriesRecord {
    val locked = lockedUntilElapsed > clock.elapsed
    return CcPinTriesRecord(
        failures = failures,
        lockedUntilElapsed = if (locked) lockedUntilElapsed else 0L,
        lockedUntilWall = if (locked) clock.wall + (lockedUntilElapsed - clock.elapsed) else 0L,
        savedElapsed = clock.elapsed,
        savedWall = clock.wall,
        boot = clock.boot,
    )
  }

  fun sameBoot(r: CcPinTriesRecord, clock: CcPinClock): Boolean {
    if (clock.elapsed < r.savedElapsed) return false
    if (r.boot != CcPinClock.UNKNOWN_BOOT && clock.boot != CcPinClock.UNKNOWN_BOOT) return r.boot == clock.boot
    val bootThen = r.savedWall - r.savedElapsed
    val bootNow = clock.wall - clock.elapsed
    return kotlin.math.abs(bootNow - bootThen) <= BOOT_DRIFT_MS
  }

  fun restore(r: CcPinTriesRecord, clock: CcPinClock): CcPinRestored {
    val failures = r.failures.coerceIn(0, CcPinEntry.MAX_TRIES - 1)
    val remaining = when {
      r.lockedUntilElapsed <= 0L && r.lockedUntilWall <= 0L -> 0L
      sameBoot(r, clock) -> r.lockedUntilElapsed - clock.elapsed
      else -> r.lockedUntilWall - clock.wall
    }.coerceIn(0L, CcPinEntry.LOCKOUT_MS)
    return CcPinRestored(failures, if (remaining > 0L) clock.elapsed + remaining else 0L)
  }
}

/** Storage for the tries; `null` clears them (a correct PIN). */
internal interface CcPinTriesStore {
  fun load(): CcPinTriesRecord?
  fun save(r: CcPinTriesRecord?)
}

/**
 * The tries in their own private preferences file: deliberately not a setting (no spec, never part
 * of household settings sync or the remote), so one Portal's lockout never travels to another.
 */
internal class CcPinTriesPrefs(context: Context) : CcPinTriesStore {
  private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  override fun load(): CcPinTriesRecord? {
    if (!prefs.contains(K_FAILURES)) return null
    return runCatching {
      CcPinTriesRecord(prefs.getInt(K_FAILURES, 0), prefs.getLong(K_UNTIL_ELAPSED, 0L), prefs.getLong(K_UNTIL_WALL, 0L),
          prefs.getLong(K_SAVED_ELAPSED, 0L), prefs.getLong(K_SAVED_WALL, 0L), prefs.getInt(K_BOOT, CcPinClock.UNKNOWN_BOOT))
    }.onFailure { Log.w(TAG, "kids PIN tries unreadable; starting fresh", it) }.getOrNull()
  }

  override fun save(r: CcPinTriesRecord?) {
    val e = prefs.edit()
    if (r == null || (r.failures == 0 && r.lockedUntilWall == 0L)) e.clear()
    else e.putInt(K_FAILURES, r.failures).putLong(K_UNTIL_ELAPSED, r.lockedUntilElapsed).putLong(K_UNTIL_WALL, r.lockedUntilWall)
        .putLong(K_SAVED_ELAPSED, r.savedElapsed).putLong(K_SAVED_WALL, r.savedWall).putInt(K_BOOT, r.boot)
    // commit, not apply: a restart right after a wrong PIN must not lose the try.
    if (!e.commit()) Log.w(TAG, "kids PIN tries not saved")
  }

  private companion object {
    const val TAG = "CcPinTries"
    const val PREFS = "hearthpane_cc_pin_tries"
    const val K_FAILURES = "failures"
    const val K_UNTIL_ELAPSED = "locked_until_elapsed"
    const val K_UNTIL_WALL = "locked_until_wall"
    const val K_SAVED_ELAPSED = "saved_elapsed"
    const val K_SAVED_WALL = "saved_wall"
    const val K_BOOT = "boot"
  }
}
