/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import com.immortal.launcher.SlideshowFaceMetadata.Bounds
import com.immortal.launcher.SlideshowFaceMetadata.Entry
import com.immortal.launcher.SlideshowFaceMetadata.Status

/** Memory reads never open the disk cache; load/persist belong to the shared background worker. */
internal class SlideshowFaceCache(
    private val file: File,
    private val maxEntries: Int = 512,
    private val maxBytes: Int = 256 * 1024,
    private val replace: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
) {
  init {
    require(maxEntries in 1..512 && maxBytes in 256..256 * 1024)
  }

  // Immutable snapshots keep presentation-time lookups independent of disk I/O.
  @Volatile private var entries: Map<String, Entry> = emptyMap()
  @Volatile private var loaded = false

  fun entry(key: String, now: Long): Entry? =
      entries[key]?.takeIf { SlideshowFaceMetadata.fresh(it, key, now) }

  @Synchronized fun record(entry: Entry, now: Long) {
    if (!SlideshowFaceMetadata.fresh(entry, entry.revisionKey, now)) return
    val updated = pruned(entries, now)
    updated.remove(entry.revisionKey)
    updated[entry.revisionKey] = entry.copy(regions = entry.regions.toList())
    while (updated.size > maxEntries) updated.remove(updated.keys.first())
    entries = updated
  }

  @Synchronized fun forgetPending(key: String) {
    if (entries[key]?.status == Status.PENDING) entries = entries - key
  }

  /** Single worker only. A bounded malformed/obsolete file is treated as an empty cache. */
  fun load(now: Long) {
    if (loaded) return
    val disk = runCatching {
      clearTemps()
      if (!file.isFile || file.length() > maxBytes) return@runCatching emptyMap<String, Entry>()
      // Bound the actual read too, in case the file changes between stat and open.
      val bytes = file.inputStream().use { input ->
        val buffer = ByteArray(maxBytes + 1)
        var count = 0
        while (count < buffer.size) {
          val read = input.read(buffer, count, buffer.size - count)
          if (read < 0) break
          count += read
        }
        if (count > maxBytes) throw IOException("cache exceeds budget")
        buffer.copyOf(count)
      }
      decode(bytes, now)
    }.getOrDefault(emptyMap())
    synchronized(this) {
      val merged = LinkedHashMap(disk)
      entries.forEach { (key, entry) ->
        // A request can reserve PENDING before disk hydration. Do not erase a warm result.
        if (entry.status != Status.PENDING || merged[key] == null) merged[key] = entry
      }
      entries = pruned(merged, now)
      loaded = true
    }
  }

  /** Write a complete bounded snapshot, fsync, then rename in the same directory (API 24+). */
  fun persist(now: Long) {
    val snapshot = synchronized(this) {
      pruned(entries, now).also { entries = it }
    }
    val encoded = snapshot.values.map { it to encodeEntry(it) }.toMutableList()
    var bytes = 8 + encoded.sumOf { it.second.size }
    while (bytes > maxBytes && encoded.isNotEmpty()) bytes -= encoded.removeAt(0).second.size
    val retained = encoded.map { it.first.revisionKey }.toSet()
    synchronized(this) {
      entries = entries.filter { (key, value) -> key in retained || value != snapshot[key] }
    }
    val parent = file.parentFile ?: throw IOException("cache directory missing")
    if (!parent.isDirectory && !parent.mkdirs()) throw IOException("cache directory unavailable")
    // This namespace has one writer; remove temp files left by a killed previous process.
    clearTemps()
    val temp = File.createTempFile("${file.name}.", ".tmp", parent)
    try {
      FileOutputStream(temp).use { output ->
        val data = DataOutputStream(output)
        data.writeInt(MAGIC)
        data.writeInt(encoded.size)
        encoded.forEach { data.write(it.second) }
        data.flush()
        output.fd.sync()
      }
      // Never delete the old target first. A failed rename leaves the previous snapshot valid.
      if (!replace(temp, file)) throw IOException("cache replacement failed")
    } finally {
      temp.delete()
    }
  }

  private fun clearTemps() {
    file.parentFile?.listFiles { _, name ->
      name.startsWith("${file.name}.") && name.endsWith(".tmp")
    }?.forEach { it.delete() }
  }

  private fun pruned(source: Map<String, Entry>, now: Long): LinkedHashMap<String, Entry> =
      LinkedHashMap<String, Entry>().apply {
        source.values.filter { SlideshowFaceMetadata.fresh(it, it.revisionKey, now) }
            .sortedBy { it.analyzedAt }.takeLast(maxEntries).forEach { put(it.revisionKey, it) }
      }

  private fun encodeEntry(entry: Entry): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { data ->
      data.writeInt(entry.schema)
      data.writeUTF(entry.model)
      data.writeUTF(entry.policy)
      data.writeUTF(entry.revisionKey)
      data.writeUTF(entry.status.name)
      data.writeLong(entry.analyzedAt)
      data.writeInt(entry.width)
      data.writeInt(entry.height)
      data.writeInt(entry.regions.size)
      entry.regions.forEach {
        data.writeFloat(it.left); data.writeFloat(it.top)
        data.writeFloat(it.right); data.writeFloat(it.bottom)
      }
    }
    return bytes.toByteArray()
  }

  private fun decode(bytes: ByteArray, now: Long): Map<String, Entry> =
      DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        if (data.readInt() != MAGIC) throw IOException("cache format mismatch")
        val count = data.readInt()
        if (count !in 0..512) throw IOException("invalid cache count")
        val decoded = LinkedHashMap<String, Entry>()
        repeat(count) {
          val schema = data.readInt()
          val model = data.readUTF()
          val policy = data.readUTF()
          val key = data.readUTF()
          val status = Status.valueOf(data.readUTF())
          val analyzedAt = data.readLong()
          val width = data.readInt()
          val height = data.readInt()
          val regionCount = data.readInt()
          if (regionCount !in 0..SlideshowFaceMetadata.MAX_REGIONS) throw IOException("invalid regions")
          val regions = List(regionCount) {
            Bounds(data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat())
          }
          val entry = Entry(key, status, analyzedAt, width, height, regions, schema, model, policy)
          if (SlideshowFaceMetadata.fresh(entry, key, now)) decoded[key] = entry
        }
        if (data.read() != -1) throw IOException("trailing cache data")
        pruned(decoded, now)
      }

  companion object {
    private const val MAGIC = 0x53464631 // SFF1; all coordinates are upright.
  }
}

/** Bounded pending cache work. The coordinator serializes access and owns the in-flight item. */
internal class SlideshowFacePending<T>(
    private val capacity: Int,
    private val discard: (String, T) -> Unit,
) {
  data class Job<T>(val session: Long, val key: String, val value: T)
  init { require(capacity > 0) }
  private val jobs = LinkedHashMap<String, Job<T>>()
  private var active: Long? = null
  fun activate(session: Long) {
    jobs.values.forEach { discard(it.key, it.value) }
    jobs.clear()
    active = session
  }
  fun offer(session: Long, key: String, value: T): Boolean {
    if (session != active) {
      discard(key, value)
      return false
    }
    jobs.remove(key)?.let { discard(it.key, it.value) }
    if (jobs.size == capacity) {
      val oldest = jobs.remove(jobs.keys.first())!!
      discard(oldest.key, oldest.value)
    }
    jobs[key] = Job(session, key, value)
    return true
  }
  fun poll(): Job<T>? = jobs.keys.firstOrNull()?.let { jobs.remove(it) }
  fun cancel(session: Long) {
    if (session == active) {
      jobs.values.forEach { discard(it.key, it.value) }
      jobs.clear()
      active = null
    }
  }
}
