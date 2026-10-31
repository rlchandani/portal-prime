/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateFormatter {
    private const val MAX_FORMATTERS_PER_THREAD = 8

    private data class FormatKey(val locale: Locale, val skeleton: String)

    // SimpleDateFormat is mutable: each caller thread owns its own bounded LRU.
    private val formatters = object : ThreadLocal<LinkedHashMap<FormatKey, SimpleDateFormat>>() {
        override fun initialValue() = object : LinkedHashMap<FormatKey, SimpleDateFormat>(
            MAX_FORMATTERS_PER_THREAD, 0.75f, true
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<FormatKey, SimpleDateFormat>?
            ): Boolean = size > MAX_FORMATTERS_PER_THREAD
        }
    }

    /**
     * Formats a date using a localized pattern based on the provided skeleton.
     * Capitalizes the first letter if the locale naturally produces a lowercase start
     * (which is common in French and some other languages for days/months).
     *
     * Example skeletons: "EEEMMMd" -> "EEE, MMM d" (US) or "EEE d MMM" (FR)
     * "EEEEMMMMd" -> "EEEE, MMMM d" (US) or "EEEE d MMMM" (FR)
     */
    fun format(date: Date, skeleton: String, locale: Locale = Locale.getDefault()): String {
        val cache = formatters.get()!!
        val key = FormatKey(locale, skeleton)
        val zone = TimeZone.getDefault()
        val formatter = cache[key] ?: SimpleDateFormat(
            DateFormat.getBestDateTimePattern(locale, skeleton), locale
        ).also {
            it.timeZone = zone
            cache[key] = it
        }
        // IDs alone miss rule changes. The calendar applies DST to the supplied date,
        // so crossing a transition needs no new pattern or formatter.
        val cachedZone = formatter.timeZone
        if (cachedZone.id != zone.id || !cachedZone.hasSameRules(zone)) {
            formatter.timeZone = zone
        }
        val raw = formatter.format(date)
        
        // Capitalize the first letter of each word (Title Case) for a premium UI look.
        // This universally solves issues in locales (like French or Spanish) where 
        // days and months are normally lowercase, without hardcoding country rules.
        return raw.split(" ").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }
}
