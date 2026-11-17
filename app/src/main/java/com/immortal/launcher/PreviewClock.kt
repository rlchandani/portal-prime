/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

/** Time and date text for the live previews, following Display & Brightness › Time format. */
internal object PreviewClock {
    private const val MS_PER_MINUTE = 60_000L
    private val time24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    private val time12: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())
    private val date: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault())

    fun time(epochMs: Long, use24Hour: Boolean, zone: ZoneId = ZoneId.systemDefault()): String =
        (if (use24Hour) time24 else time12).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun date(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        date.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun hour(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int = Instant.ofEpochMilli(epochMs).atZone(zone).hour

    /** Until the shown minute changes; at least 1 ms so a tick loop cannot spin. */
    fun msUntilNextMinute(epochMs: Long): Long = MS_PER_MINUTE - Math.floorMod(epochMs, MS_PER_MINUTE)
}

/** Wall-clock ms, advanced on each minute boundary while [running] (one recomposition a minute). */
@Composable
internal fun rememberMinuteClock(running: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        now = System.currentTimeMillis()
        while (true) {
            delay(PreviewClock.msUntilNextMinute(now))
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** Display & Brightness › Time format, followed live. */
@Composable
internal fun rememberUse24HourClock(): Boolean {
    val use24 by rememberPreferenceSnapshot(ImmortalSettings.PREFS) { ImmortalSettings.use24HourClock(it) }
    return use24
}
