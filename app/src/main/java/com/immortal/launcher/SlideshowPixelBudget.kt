/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.IdentityHashMap

/** Shared admission for retained pixels and worst-case decoder/EXIF scratch. */
internal class SlideshowPixelBudget(private val limit: Long = 48L * 1024 * 1024) {
  data class Entry(val identity: Any, val bytes: Long)
  class Reservation internal constructor(internal val bytes: Long)
  private val lock = Object()
  private val owners = HashMap<String, List<Entry>>()
  private val reservations = HashSet<Reservation>()
  @Volatile var peak: Long = 0
    private set

  fun used(): Long = synchronized(lock) { total() }

  fun setOwner(name: String, entries: List<Entry>) = synchronized(lock) {
    if (entries.isEmpty()) owners.remove(name) else owners[name] = entries.toList()
    record()
    lock.notifyAll()
  }

  /** Called only by the preparation worker; cancellation never blocks the UI. */
  fun reserve(bytes: Long, current: () -> Boolean): Reservation? = synchronized(lock) {
    require(bytes in 0..limit)
    while (current() && !Thread.currentThread().isInterrupted) {
      if (total() + bytes <= limit) {
        return@synchronized Reservation(bytes).also { reservations.add(it); record() }
      }
      try { lock.wait(100) } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return@synchronized null
      }
    }
    null
  }

  fun commit(token: Reservation, owner: String, entries: List<Entry>) = synchronized(lock) {
    check(reservations.remove(token)) { "Unknown pixel reservation" }
    if (entries.isEmpty()) owners.remove(owner) else owners[owner] = entries.toList()
    record()
    lock.notifyAll()
  }

  fun release(token: Reservation) = synchronized(lock) {
    reservations.remove(token)
    lock.notifyAll()
  }

  private fun total(): Long {
    val pixels = IdentityHashMap<Any, Long>()
    owners.values.flatten().forEach { pixels[it.identity] = maxOf(pixels[it.identity] ?: 0L, it.bytes) }
    return pixels.values.sum() + reservations.sumOf { it.bytes }
  }

  private fun record() {
    val used = total()
    check(used <= limit) { "Slideshow pixels exceed admission budget: $used > $limit" }
    peak = maxOf(peak, used)
  }
}
