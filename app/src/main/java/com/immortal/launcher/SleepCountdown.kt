/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/**
 * The sleep timer's countdown, pure: from the armed end time (0 = not running) and the clock.
 * Seconds round up, so a fresh 30-minute timer reads 30:00 and it reaches 00:00 as it fires.
 */
internal object SleepCountdown {
    private const val MS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L

    fun remainingMs(endsAtMs: Long, nowMs: Long): Long = if (endsAtMs <= 0L) 0L else (endsAtMs - nowMs).coerceAtLeast(0L)

    fun isRunning(endsAtMs: Long, nowMs: Long): Boolean = remainingMs(endsAtMs, nowMs) > 0L

    fun durationMs(minutes: Int): Long = minutes.coerceAtLeast(0) * SECONDS_PER_MINUTE * MS_PER_SECOND

    /** mm:ss, or h:mm:ss from an hour up. */
    fun label(remainingMs: Long): String {
        val total = (remainingMs.coerceAtLeast(0L) + MS_PER_SECOND - 1) / MS_PER_SECOND
        val hours = total / SECONDS_PER_HOUR
        val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = total % SECONDS_PER_MINUTE
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
    }

    /** What the card shows: the time left while running, else the length a start would set. */
    fun display(endsAtMs: Long, nowMs: Long, minutes: Int): String =
        if (isRunning(endsAtMs, nowMs)) label(remainingMs(endsAtMs, nowMs)) else label(durationMs(minutes))

    /** Wait until the shown second changes (never 0, so a tick loop cannot spin). */
    fun nextTickMs(remainingMs: Long): Long = if (remainingMs <= 0L) MS_PER_SECOND else ((remainingMs - 1) % MS_PER_SECOND) + 1
}

private val CountdownButtonShape = RoundedCornerShape(14.dp)
private val CountdownButtonHeight = 68.dp

/**
 * Night & Sleep's live sleep timer: a large countdown with Start / Stop. It reads the armed time
 * from [SleepScheduler], so it keeps counting after a page switch, and ticks once per second
 * only while the screen is resumed.
 */
@Composable
internal fun SleepCountdownCard(timerEnabled: Boolean, minutes: Int, onStart: () -> Unit, onStop: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val endsAt = SleepScheduler.sleepTimerEndsAtMs
    val running = SleepCountdown.isRunning(endsAt, now)
    LaunchedEffect(endsAt, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            now = System.currentTimeMillis()
            while (SleepCountdown.isRunning(SleepScheduler.sleepTimerEndsAtMs, now)) {
                delay(SleepCountdown.nextTickMs(SleepCountdown.remainingMs(SleepScheduler.sleepTimerEndsAtMs, now)))
                now = System.currentTimeMillis()
            }
        }
    }
    Card {
        Column(Modifier.padding(horizontal = SettingsSpacing.TEXT_INSET_DP.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Sleep timer", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.weight(1f))
                val shown = SleepCountdown.display(endsAt, now, minutes)
                Text(shown, color = if (running) IosBlue else IosSecondary, fontSize = 58.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { contentDescription = if (running) "Sleeping in $shown" else "Sleep timer $shown" })
            }
            if (running) {
                CountdownButton("Stop", IosRed, Color.White, enabled = true, onClick = { onStop(); now = System.currentTimeMillis() })
            } else {
                CountdownButton("Start sleep timer", IosBlue, MaterialTheme.colorScheme.onPrimary, enabled = timerEnabled,
                    onClick = { onStart(); now = System.currentTimeMillis() })
            }
            if (!timerEnabled) {
                Text("Turn on Sleep Timer below to start it.", color = IosSecondary, fontSize = Sizes.rowSub)
            }
        }
    }
}

@Composable
private fun CountdownButton(label: String, fill: Color, text: Color, enabled: Boolean, onClick: () -> Unit) {
    Surface(color = if (enabled) fill else IosDivider, shape = CountdownButtonShape,
        modifier = Modifier.fillMaxWidth().heightIn(min = CountdownButtonHeight)
            .tvFocusable(CountdownButtonShape, focusScale = 1f, ringColor = IosBlue, enabled = enabled) { onClick() }) {
        Text(label, color = if (enabled) text else IosSecondary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 18.dp).fillMaxWidth())
    }
}
