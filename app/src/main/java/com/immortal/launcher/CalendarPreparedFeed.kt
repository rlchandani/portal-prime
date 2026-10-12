/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.Locale
import java.util.TimeZone

/** Share immutable event definitions; evaluate time/horizon-dependent occurrences on each read. */
internal class CalendarPreparedFeed<P : Any>(
    private val prepare: (String, TimeZone, Locale) -> P,
    private val evaluate: (P, Long, Int) -> List<CalendarFeed.Event>,
    private val retainPrepared: (P) -> Boolean = { true },
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
  private data class Key(val source: String, val zone: TimeZone, val locale: Locale)
  private data class Prepared<P>(val value: P, val characters: Int)
  private val documents = SingleFlightValueCache<Key, Prepared<P>>(30_000L, 4, nowMillis,
      retainValue = { it.characters <= 262_144 && retainPrepared(it.value) })

  fun parse(source: String, nowMillis: Long, horizonDays: Int): List<CalendarFeed.Event> {
    val zone = TimeZone.getDefault()
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    val key = Key(source, zone, locale)
    val document = documents.get(key) {
      // The cache key's timezone remains private even if a preparation callback changes its copy.
      Prepared(prepare(source, zone.clone() as TimeZone, locale), source.length)
    }
    return evaluate(document.value, nowMillis, horizonDays)
  }
}
