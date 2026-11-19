/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Which entities changed since a phone's last read (pure, unit tested), so a poll carries only
 * what moved. Each published entity map that differs bumps [rev] and stamps the entities that
 * changed; a phone sends back the `rev` it holds and gets the delta, or everything when it is
 * new, too far behind (the removal log was trimmed) or ahead (this Portal restarted).
 */
internal class CcRemoteJournal {
  data class Delta(val rev: Long, val full: Boolean, val changed: List<CcEntity>, val removed: List<String>)

  private var rev = 0L
  private var floor = 0L
  private var lastVersion = Long.MIN_VALUE
  private var last: Map<String, CcEntity> = emptyMap()
  private val changedAt = HashMap<String, Long>()
  private val removedAt = HashMap<String, Long>()

  /** [version] is the engine's `entitiesVersion`: the map is only diffed when it moved. */
  @Synchronized
  fun read(entities: Map<String, CcEntity>, version: Long, since: Long): Delta {
    if (version != lastVersion) { record(entities); lastVersion = version }
    val full = since <= 0 || since < floor || since > rev
    if (full) return Delta(rev, true, entities.values.toList(), emptyList())
    val changed = entities.values.filter { (changedAt[it.id] ?: 0L) > since }
    val removed = removedAt.filterValues { it > since }.keys.sorted()
    return Delta(rev, false, changed, removed)
  }

  private fun record(entities: Map<String, CcEntity>) {
    val moved = entities.values.filter { last[it.id] != it }
    val gone = last.keys.filter { it !in entities }
    if (moved.isEmpty() && gone.isEmpty() && last.isNotEmpty()) { last = entities; return }
    rev++
    moved.forEach { changedAt[it.id] = rev; removedAt.remove(it.id) }
    gone.forEach { changedAt.remove(it); removedAt[it] = rev }
    if (removedAt.size > MAX_REMOVED) { removedAt.clear(); floor = rev }
    last = entities
  }

  private companion object {
    /** A phone further behind than this many removals simply gets everything again. */
    const val MAX_REMOVED = 512
  }
}
