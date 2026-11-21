/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Reused [SimpleDateFormat]s for Control Center labels. Building one loads locale data (about
 * 0.5 ms each on a Portal); the Alerts cards and the header subtitle built three to six per
 * screen switch. One formatter per thread, pattern and locale; the zone is set on every call,
 * so the output is exactly a fresh formatter's.
 */
internal object CcDateFormats {
  private val cache = ThreadLocal<HashMap<Pair<String, Locale>, SimpleDateFormat>>()

  fun formatter(pattern: String, locale: Locale = Locale.getDefault()): SimpleDateFormat {
    val map = cache.get() ?: HashMap<Pair<String, Locale>, SimpleDateFormat>().also { cache.set(it) }
    return map.getOrPut(pattern to locale) { SimpleDateFormat(pattern, locale) }
  }

  fun format(pattern: String, ms: Long, zone: TimeZone = TimeZone.getDefault()): String =
      formatter(pattern).apply { timeZone = zone }.format(Date(ms))
}
