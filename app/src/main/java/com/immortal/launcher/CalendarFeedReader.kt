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
  private data class Saved(val feed: String, val completedAt: Long)
  private val clock = nowMillis
  private val savedLock = Any()
  private val saved = LinkedHashMap<String, Saved>(16, .75f, true)
  private val text = SingleFlightValueCache<String, String>(30_000L, 4, nowMillis,
      retainValue = ::canRetain)

  /** Report refresh failures while retaining only usable data from this exact source. */
  fun read(
      url: String,
      nowMillis: Long,
      horizonDays: Int,
      forceRefresh: Boolean = false,
  ): CalendarFeed.Result {
    val normalized = CalendarFeed.normalizeUrl(url)
    val feed = try { freshLoad(normalized, forceRefresh) }
    catch (error: InterruptedException) { throw error }
    catch (_: Exception) {
      val fallback = savedFeed(normalized)
      return CalendarFeed.Result(
          fallback?.let { CalendarFeed.parse(it, nowMillis, horizonDays) } ?: emptyList(),
          unavailable = true)
    }
    // Warm reads update LRU order without renewing the last successful download's age.
    synchronized(savedLock) { saved[normalized] }
    return CalendarFeed.Result(CalendarFeed.parse(feed, nowMillis, horizonDays))
  }

  /** Legacy reader contract: null is empty, transport exceptions propagate. */
  fun fetch(url: String, nowMillis: Long, horizonDays: Int): List<CalendarFeed.Event> {
    val normalized = CalendarFeed.normalizeUrl(url)
    val feed = try { freshLoad(normalized, false) }
        catch (_: Unavailable) { return emptyList() }
    return CalendarFeed.parse(feed, nowMillis, horizonDays)
  }

  private fun freshLoad(normalized: String, refresh: Boolean): String = try {
    text.get(normalized, refresh) {
      val feed = load(normalized) ?: throw Unavailable()
      if (!isCalendar(feed)) throw Unavailable()
      val retain = canRetain(feed)
      synchronized(savedLock) {
        // A newer oversized success supersedes old data but cannot itself be retained.
        saved.remove(normalized)
        if (retain) {
          saved[normalized] = Saved(feed, clock())
          while (saved.size > 4) saved.remove(saved.keys.first())
        }
      }
      feed
    }
  } catch (error: InterruptedException) {
    Thread.currentThread().interrupt()
    throw error
  }

  private fun savedFeed(normalized: String): String? = synchronized(savedLock) {
    val entry = saved[normalized] ?: return@synchronized null
    val age = clock() - entry.completedAt
    if (age < 0 || age >= MAX_AGE_MILLIS) {
      saved.remove(normalized)
      null
    } else entry.feed
  }

  /** Validate the whole document before either cache sees it; HTML is never a success. */
  private fun isCalendar(feed: String): Boolean {
    val components = ArrayList<String>()
    var ended = false
    for (raw in CalendarFeed.unfold(feed)) {
      val line = raw.trim().trimStart('\uFEFF').trim()
      if (line.isEmpty()) continue
      if (ended) return false
      when {
        line.startsWith("BEGIN:", ignoreCase = true) -> {
          val component = line.substring(6)
          if (component.isEmpty()) return false
          if (components.isEmpty()) {
            if (!component.equals("VCALENDAR", ignoreCase = true)) return false
          } else if (component.equals("VCALENDAR", ignoreCase = true)) return false
          components.add(component)
        }
        line.startsWith("END:", ignoreCase = true) -> {
          if (components.isEmpty() ||
              !components.last().equals(line.substring(4), ignoreCase = true)) return false
          components.removeAt(components.lastIndex)
          if (components.isEmpty()) ended = true
        }
        components.isEmpty() -> return false
      }
    }
    return ended && components.isEmpty()
  }

  /** Bound both UTF-16 storage and UTF-8 wire size without allocating encoded bytes. */
  private fun canRetain(feed: String): Boolean {
    if (feed.length > MAX_BYTES / 2) return false
    var bytes = 0
    var index = 0
    while (index < feed.length) {
      val character = feed[index++]
      bytes += when {
        character.code <= 0x7f -> 1
        character.code <= 0x7ff -> 2
        Character.isHighSurrogate(character) && index < feed.length &&
            Character.isLowSurrogate(feed[index]) -> { index++; 4 }
        // String's UTF-8 encoder replaces an unpaired surrogate with one '?' byte.
        Character.isSurrogate(character) -> 1
        else -> 3
      }
      if (bytes > MAX_BYTES) return false
    }
    return true
  }

  private companion object {
    const val MAX_BYTES = 262_144
    const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1_000L
  }
}
