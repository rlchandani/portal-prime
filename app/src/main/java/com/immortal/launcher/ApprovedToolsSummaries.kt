/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Read-only library metadata; the retained Tools host supplies its existing IO/lifecycle lane. */
internal object ApprovedToolsSummaries {
    fun load(context: Context, nowMillis: Long = System.currentTimeMillis()): Map<String, String> =
        mapOf(
            "alarm" to summary {
                val alarm = AlarmConfig.load(context)
                if (!alarm.enabled) "Off" else String.format(Locale.getDefault(), "%02d:%02d · %s",
                    alarm.hour, alarm.minute, if (alarm.days.isEmpty()) "Once" else "Repeating")
            },
            "timers" to summary {
                val timers = TimerConfig.load(context).count { it.endAtMillis > nowMillis }
                if (timers == 0) "Ready when you are" else
                    "$timers timer${if (timers == 1) "" else "s"} running"
            },
            "countdowns" to summary { "${CountdownConfig.load(context).size} saved" },
            "camera" to summary { "${CameraConfig.load(context).size} saved" },
            "notes" to summary {
                if (NotesConfig.loadText(context).isBlank() && !NotesConfig.hasAudioNote(context)) "Leave a message" else "A note is waiting"
            },
        )

    /** A failed feature never discards its neighbours or turns into an editable empty default. */
    private inline fun summary(read: () -> String): String = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        "Unavailable"
    }
}
