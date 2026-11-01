/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.ui.clock

import com.immortal.launcher.ClockMath
import com.immortal.launcher.ClockSpec
import java.text.DateFormatSymbols
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Format choices and stable measurement samples, resolved at construction/layout cadence. */
internal object DigitalClockTimeFormat {
    fun pattern(spec: ClockSpec): String {
        val hour = if (spec.is24h) "H" else "h"
        val hours = if (spec.leadingZero) hour + hour else hour
        return hours + ClockMath.separator(spec.separator, true) + "mm" +
            if (spec.showAmPm) " a" else ""
    }

    fun reservedLabels(spec: ClockSpec, locale: Locale): List<String> {
        val eight = (DecimalFormatSymbols.getInstance(locale).zeroDigit.code + 8).toChar()
        val digits = "$eight$eight${ClockMath.separator(spec.separator, true)}$eight$eight"
        return if (spec.showAmPm) DateFormatSymbols.getInstance(locale).amPmStrings.map {
            "$digits $it"
        } else listOf(digits)
    }
}
