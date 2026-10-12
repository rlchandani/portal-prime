/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.Semaphore

/** Short cache operations and two pixel decoders; validation belongs to the opened-file owner. */
internal class RemotePhotoThumbnailCache(private val maxBytes: Int = 4 * 1024 * 1024,
    private val maxEntries: Int = 32, private val maxItemBytes: Int = 512 * 1024) {
    data class Ticket(val key: String, val generation: Long, val cached: ByteArray?)
    private val lock = Any()
    private val decodeSlots = Semaphore(2)
    private val entries = LinkedHashMap<String, ByteArray>(32, .75f, true)
    private var bytes = 0
    private var generation = 0L
    fun snapshot(key: String): Ticket = synchronized(lock) { Ticket(key, generation, entries[key]) }
    fun load(ticket: Ticket, decode: () -> ByteArray?): ByteArray? {
        ticket.cached?.let { return it }
        decodeSlots.acquire()
        return try {
            synchronized(lock) {
                if (generation == ticket.generation) entries[ticket.key] else null
            }?.let { return it }
            val value = decode() ?: return null
            if (value.isEmpty() || value.size > maxItemBytes) return null
            synchronized(lock) {
                // A delete invalidates tickets captured before it; old descriptors may still
                // finish their response, but cannot repopulate a newer cache generation.
                if (generation == ticket.generation) {
                    entries[ticket.key]?.let { return it }
                    entries[ticket.key] = value; bytes += value.size
                    while (bytes > maxBytes || entries.size > maxEntries) {
                        val iterator = entries.entries.iterator(); val old = iterator.next()
                        bytes -= old.value.size; iterator.remove()
                    }
                }
            }
            value
        } finally { decodeSlots.release() }
    }
    fun clear() = synchronized(lock) { entries.clear(); bytes = 0; generation++ }
    fun discard(key: String): Boolean = synchronized(lock) {
        entries.remove(key)?.let { bytes -= it.size }
        !entries.containsKey(key)
    }
}
