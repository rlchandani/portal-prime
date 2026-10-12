/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Worker-only feed sharing. Time and horizon filtering remains fresh for every caller. */
internal class CalendarFeedReader(
    private val load: (String) -> String?,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
  private class Unavailable : Exception()
  private val text = SingleFlightValueCache<String, String>(30_000L, 4, nowMillis,
      retainValue = { it.length <= 262_144 })

  fun fetch(url: String, nowMillis: Long, horizonDays: Int): List<CalendarFeed.Event> {
    val normalized = CalendarFeed.normalizeUrl(url)
    val feed = try { text.get(normalized) { load(normalized) ?: throw Unavailable() } }
        catch (_: Unavailable) { return emptyList() }
    // Do not retain a filtered event list: an event can end while the feed is fresh.
    return CalendarFeed.parse(feed, nowMillis, horizonDays)
  }
}
