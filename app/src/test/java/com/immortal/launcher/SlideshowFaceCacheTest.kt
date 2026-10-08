/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.SlideshowFaceMetadata.Bounds
import com.immortal.launcher.SlideshowFaceMetadata.Entry
import com.immortal.launcher.SlideshowFaceMetadata.Status
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SlideshowFaceCacheTest {
  @get:Rule val temporary = TemporaryFolder()
  private val now = 2_000_000_000_000L
  private fun file() = File(temporary.newFolder(), "metadata.bin")
  private fun entry(id: String, status: Status = Status.FACES, at: Long = now) =
      Entry(SlideshowFaceMetadata.key(id), status, at, 640, 480,
          if (status == Status.FACES) listOf(Bounds(.1f, .2f, .5f, .8f)) else emptyList())

  @Test fun `construction is memory only and explicit load restores every status`() {
    val file = file()
    val writer = SlideshowFaceCache(file)
    val entries = Status.entries.map { entry("asset:$it:revision:2", it) }
    entries.forEach { writer.record(it, now) }
    writer.persist(now)
    val reader = SlideshowFaceCache(file)
    assertNull(reader.entry(entries.first().revisionKey, now))
    reader.load(now)
    entries.forEach { assertEquals(it, reader.entry(it.revisionKey, now)) }
  }

  @Test fun `load replaces speculative pending with fresh persisted result`() {
    val file = file()
    val result = entry("revision")
    SlideshowFaceCache(file).apply { record(result, now); persist(now) }
    val reader = SlideshowFaceCache(file)
    reader.record(result.copy(status = Status.PENDING, regions = emptyList()), now)
    reader.load(now)
    assertEquals(result, reader.entry(result.revisionKey, now))
  }

  @Test fun `pending cancellation cannot erase an already completed result`() {
    val cache = SlideshowFaceCache(file())
    val completed = entry("complete")
    val pending = entry("pending", Status.PENDING)
    cache.record(completed, now)
    cache.record(pending, now)
    cache.forgetPending(completed.revisionKey)
    cache.forgetPending(pending.revisionKey)
    assertEquals(completed, cache.entry(completed.revisionKey, now))
    assertNull(cache.entry(pending.revisionKey, now))
  }

  @Test fun `read and persistence prune expired metadata and oldest entries`() {
    val file = file()
    val cache = SlideshowFaceCache(file, maxEntries = 2)
    val old = entry("old")
    val mid = entry("mid", at = now + 1)
    val newest = entry("new", at = now + 2)
    cache.record(old, now)
    cache.record(mid, now + 1)
    cache.record(newest, now + 2)
    assertNull(cache.entry(old.revisionKey, now + 2))
    cache.record(entry("expired", Status.FAILURE, now - 600_000), now + 2)
    cache.persist(now + 2)
    SlideshowFaceCache(file, maxEntries = 2).apply {
      load(now + 2)
      assertEquals(mid, entry(mid.revisionKey, now + 2))
      assertEquals(newest, entry(newest.revisionKey, now + 2))
      assertNull(entry(newest.revisionKey, now + 2 + 30 * SlideshowFaceMetadata.DAY_MS))
    }
  }

  @Test fun `byte cap retains newest complete records and excludes raw source credentials`() {
    val file = file()
    val cache = SlideshowFaceCache(file, maxBytes = 512)
    val newest = entry("https://user:secret@example.test/p?auth=private&rev=2")
    repeat(20) { cache.record(entry("old:$it"), now) }
    cache.record(newest, now)
    cache.persist(now)
    assertTrue(file.length() in 1..512)
    assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("secret"))
    assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("auth=private"))
    SlideshowFaceCache(file, maxBytes = 512).apply {
      load(now)
      assertEquals(newest, entry(newest.revisionKey, now))
      assertNull(entry(SlideshowFaceMetadata.key("old:0"), now))
    }
  }

  @Test fun `failed atomic replacement preserves previous complete file and cleans temp`() {
    val file = file()
    val previous = entry("before")
    SlideshowFaceCache(file).apply { record(previous, now); persist(now) }
    val originalBytes = file.readBytes()
    val failing = SlideshowFaceCache(file, replace = { _, _ -> false })
    failing.record(entry("after"), now)
    assertThrows(IOException::class.java) { failing.persist(now) }
    assertArrayEquals(originalBytes, file.readBytes())
    assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList())
  }

  @Test fun `truncated oversized and corrupt caches safely load empty`() {
    val file = file()
    val result = entry("good")
    SlideshowFaceCache(file).apply { record(result, now); persist(now) }
    val validBytes = file.readBytes()
    for (bad in listOf(validBytes.copyOf(validBytes.size / 2),
        ByteArray(256 * 1024 + 1), byteArrayOf(1, 2, 3, 4))) {
      file.writeBytes(bad)
      SlideshowFaceCache(file).apply {
        load(now)
        assertNull(entry(result.revisionKey, now))
      }
    }
  }

  @Test fun `reloaded metadata respects TTL and source revision boundary`() {
    val file = file()
    val negative = entry("asset:r1", Status.NO_FACE)
    SlideshowFaceCache(file).apply { record(negative, now); persist(now) }
    SlideshowFaceCache(file).apply {
      load(now + 7 * SlideshowFaceMetadata.DAY_MS)
      assertNull(entry(negative.revisionKey, now + 7 * SlideshowFaceMetadata.DAY_MS))
      assertNull(entry(SlideshowFaceMetadata.key("asset:r2"), now))
    }
  }

  @Test fun `persisted schema model and policy bytes invalidate obsolete analysis`() {
    val file = file()
    val result = entry("versioned")
    SlideshowFaceCache(file).apply { record(result, now); persist(now) }
    val original = file.readBytes()
    val text = original.toString(Charsets.ISO_8859_1)
    for (offset in listOf(11, text.indexOf(SlideshowFaceMetadata.MODEL),
        text.indexOf(SlideshowFaceMetadata.POLICY))) {
      assertTrue(offset >= 0)
      val changed = original.copyOf()
      changed[offset] = (changed[offset].toInt() + 1).toByte()
      file.writeBytes(changed)
      SlideshowFaceCache(file).apply {
        load(now)
        assertNull(entry(result.revisionKey, now))
      }
    }
  }

  @Test fun `background hydration prunes interrupted atomic write temp files`() {
    val file = file()
    val result = entry("complete")
    SlideshowFaceCache(file).apply { record(result, now); persist(now) }
    val orphan = File(file.parentFile, "${file.name}.interrupted.tmp")
    orphan.writeBytes(ByteArray(512))
    SlideshowFaceCache(file).apply {
      load(now)
      assertEquals(result, entry(result.revisionKey, now))
    }
    assertFalse(orphan.exists())
  }

  @Test fun `queue coalesces duplicate pending metadata and evicts oldest owned derivative`() {
    val discarded = ArrayList<String>()
    val queue = SlideshowFacePending<String>(2) { _, value -> discarded.add(value) }
    queue.activate(1)
    queue.offer(1, "a", "a1")
    queue.offer(1, "a", "a2")
    queue.offer(1, "b", "b")
    queue.offer(1, "c", "c")
    assertEquals(listOf("a1", "a2"), discarded)
    assertEquals("b", queue.poll()!!.value)
    assertEquals("c", queue.poll()!!.value)
    assertNull(queue.poll())
  }

  @Test fun `session supersession and close discard pending and reject stale admission`() {
    val discarded = ArrayList<String>()
    val queue = SlideshowFacePending<String>(2) { _, value -> discarded.add(value) }
    queue.activate(1)
    queue.offer(1, "old", "old")
    queue.activate(2)
    assertFalse(queue.offer(1, "stale", "stale"))
    queue.offer(2, "new", "new")
    queue.cancel(1) // old close cannot cancel the new session
    assertEquals("new", queue.poll()!!.value)
    queue.offer(2, "close", "close")
    queue.cancel(2)
    assertFalse(queue.offer(2, "late", "late"))
    assertNull(queue.poll())
    assertEquals(listOf("old", "stale", "close", "late"), discarded)
  }
}
