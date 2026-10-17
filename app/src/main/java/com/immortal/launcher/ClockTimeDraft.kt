/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

/** Independent keyboard draft; invalid text stays intact until corrected or dismissed. */
internal data class ClockTimeDraft(val hour: String, val minute: String) {
    private fun bounded(text: String, max: Int): Int? =
        text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?.toIntOrNull()?.takeIf { it in 0..max }

    fun totalMinutesOrNull(): Int? {
        val h = bounded(hour, 23) ?: return null
        val m = bounded(minute, 59) ?: return null
        return h * 60 + m
    }

    fun errorOrNull(): String? = when {
        bounded(hour, 23) == null -> "Enter an hour from 0 to 23."
        bounded(minute, 59) == null -> "Enter a minute from 0 to 59."
        else -> null
    }

    companion object {
        fun fromMinutes(value: Int) = ClockTimeDraft(
            (value / 60).toString().padStart(2, '0'),
            (value % 60).toString().padStart(2, '0'))
    }
}
