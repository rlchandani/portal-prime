/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Notification
import java.util.Collections

internal fun notificationBadgeEligible(clearable: Boolean, flags: Int): Boolean =
    clearable && flags and Notification.FLAG_GROUP_SUMMARY == 0

/** Counts only; no notification bodies, Android objects, Activity or Context are retained. */
internal class NotificationBadgeSnapshot {
    data class Entry(val key: String, val packageName: String, val eligible: Boolean)
    private val entries = mutableMapOf<String, String>()
    private val counts = mutableMapOf<String, Int>()
    private val pending = mutableMapOf<String, Entry?>()
    private var generation = 0L
    @Volatile var seeded = false
        private set
    @Volatile private var published: Map<String, Int> = emptyMap()

    @Synchronized fun beginSeed(): Long {
        generation++
        seeded = false
        entries.clear(); counts.clear(); pending.clear()
        published = emptyMap()
        return generation
    }

    @Synchronized fun completeSeed(epoch: Long, initial: List<Entry>): Boolean {
        if (epoch != generation || seeded) return false
        initial.forEach(::update)
        pending.forEach { (key, entry) -> if (entry == null) removeEntry(key) else update(entry) }
        pending.clear()
        seeded = true
        publish()
        return true
    }

    @Synchronized fun upsert(entry: Entry) {
        if (!seeded) pending[entry.key] = entry
        else if (update(entry)) publish()
    }

    @Synchronized fun remove(key: String) {
        if (!seeded) pending[key] = null
        else if (removeEntry(key)) publish()
    }

    @Synchronized fun clear() { beginSeed() }
    fun snapshot(): Map<String, Int> = published

    private fun update(entry: Entry): Boolean {
        val previous = entries[entry.key]
        val next = entry.packageName.takeIf { entry.eligible }
        if (previous == next) return false
        removeEntry(entry.key)
        if (next != null) {
            entries[entry.key] = next
            counts[next] = (counts[next] ?: 0) + 1
        }
        return true
    }

    private fun removeEntry(key: String): Boolean {
        val pkg = entries.remove(key) ?: return false
        val remaining = counts.getValue(pkg) - 1
        if (remaining == 0) counts.remove(pkg) else counts[pkg] = remaining
        return true
    }

    private fun publish() {
        published = if (counts.isEmpty()) emptyMap() else Collections.unmodifiableMap(counts.toMap())
    }
}
