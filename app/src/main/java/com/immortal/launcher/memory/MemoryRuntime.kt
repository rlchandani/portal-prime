/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The profiles and usage counts in memory, written back debounced (plan §3.2: at most one write per [WRITE_DELAY_MS],
 * Forget at once). Every read and change goes through one lock, and the values it hands out are immutable, so the
 * provider's binder threads and the "What Jarvis knows" screen can share it. Storage, the timer and the clock are
 * seams, so the debounce and Forget rules are JVM-tested.
 */
internal class MemoryRuntime(
    private val load: (MemoryScope) -> MemoryProfile,
    private val save: (MemoryProfile) -> Boolean,
    private val loadUsage: () -> UsageCounts,
    private val saveUsage: (UsageCounts) -> Boolean,
    private val remove: (MemoryScope) -> Boolean,
    private val wipe: () -> Boolean,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val now: () -> Long,
) {
  private val lock = Any()
  private var profiles: Map<MemoryScope, MemoryProfile> = emptyMap()
  private var usage: UsageCounts? = null
  private var dirty: Set<MemoryScope> = emptySet()
  private var usageDirty = false
  private var writePending = false
  private val revisions = MutableStateFlow(0)

  /** Bumped after every change, so a screen showing a profile knows to read it again. */
  val revision: StateFlow<Int> = revisions.asStateFlow()

  /** [scope]'s profile with decay applied, loading it on first use. */
  fun profile(scope: MemoryScope): MemoryProfile = synchronized(lock) {
    MemoryLearner.refresh(cached(scope), now())
  }

  /** Applies [change] to [scope]'s profile; [immediate] writes now (Forget), otherwise within [WRITE_DELAY_MS]. */
  fun update(scope: MemoryScope, immediate: Boolean = false, change: (MemoryProfile) -> MemoryProfile): MemoryProfile {
    val next = synchronized(lock) {
      val updated = change(cached(scope)).normalized()
      profiles = profiles + (scope to updated)
      dirty = dirty + scope
      updated
    }
    revisions.value++
    if (immediate) flush() else scheduleWrite()
    return next
  }

  /** Counts one open of [surface] for [scope]; written with the next debounced write. */
  fun recordUsage(change: (UsageCounts) -> UsageCounts) {
    synchronized(lock) {
      usage = change(usage ?: loadUsage())
      usageDirty = true
    }
    scheduleWrite()
  }

  /** The usage counts within their retention window (pruned on read, so a long quiet spell leaves nothing stale). */
  fun usage(): UsageCounts = synchronized(lock) {
    (usage ?: loadUsage()).pruned(now(), java.time.ZoneId.systemDefault()).also { usage = it }
  }

  /** Writes everything changed since the last write. */
  fun flush() {
    val (toSave, counts) = synchronized(lock) {
      writePending = false
      val out = dirty.mapNotNull { profiles[it] } to usage?.takeIf { usageDirty }
      dirty = emptySet()
      usageDirty = false
      out
    }
    val failed = toSave.filterNot(save).map { it.scope }.toSet()
    val usageFailed = counts != null && !saveUsage(counts)
    if (failed.isNotEmpty() || usageFailed) {
      synchronized(lock) {
        dirty = dirty + failed
        usageDirty = usageDirty || usageFailed
      }
    }
  }

  /**
   * "Forget all" on the screen: everything about [scope] (its profile, Recent topics and usage counts), at once.
   * Its pause setting goes with it. True when nothing of it remains on disk.
   */
  fun forgetScope(scope: MemoryScope): Boolean {
    val counts = synchronized(lock) {
      profiles = profiles - scope
      dirty = dirty - scope
      (usage ?: loadUsage()).without(scope).also { usage = it; usageDirty = false }
    }
    revisions.value++
    val removed = remove(scope)
    return saveUsage(counts) && removed
  }

  /** Forget all: every profile and every count, in memory and on disk, at once. True when nothing remains. */
  fun forgetAll(): Boolean {
    synchronized(lock) {
      profiles = emptyMap()
      usage = UsageCounts()
      dirty = emptySet()
      usageDirty = false
    }
    revisions.value++
    return wipe()
  }

  private fun cached(scope: MemoryScope): MemoryProfile =
      profiles[scope] ?: load(scope).also { profiles = profiles + (scope to it) }

  private fun scheduleWrite() {
    val first = synchronized(lock) { (!writePending).also { writePending = true } }
    if (first) schedule(WRITE_DELAY_MS) { flush() }
  }

  companion object {
    const val WRITE_DELAY_MS = 30_000L
  }
}
