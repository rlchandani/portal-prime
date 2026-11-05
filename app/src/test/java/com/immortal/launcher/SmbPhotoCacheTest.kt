/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SmbPhotoCacheTest {
  @get:Rule val tmp = TemporaryFolder()
  private val key get() = smbPhotoCacheKey("nas", "photos", "viewer", null, "album\\one.jpg")
  private fun cache(budget: Long = 1024) = MediaCache(File(tmp.root, "cache"), budget)
  private fun fetch(cache: MediaCache?, current: () -> Boolean = { true },
      read: () -> ByteArray?): String? =
      fetchCachedSmbPhoto(cache, key, current, read,
          { it.readText().takeUnless { text -> text == "corrupt" } },
          { it.toString(Charsets.UTF_8) }, {})

  @Test fun repeatHitDoesNotOpenImageTransport() {
    val cache = cache()
    assertEquals("photo", fetch(cache) { "photo".toByteArray() })
    assertEquals("photo", fetch(cache) { error("Warm photo must not read SMB") })
    assertArrayEquals("photo".toByteArray(), cache.imageFile(key).readBytes())
  }

  @Test fun cacheDisabledReadsDirectlyWithoutPublishing() {
    assertEquals("photo", fetch(null) { "photo".toByteArray() })
    assertFalse(File(tmp.root, "cache").exists())
  }

  @Test fun identitySeparatesServerShareAccountDomainAndPath() {
    val baseline = key
    val variants = listOf(
        smbPhotoCacheKey("other", "photos", "viewer", null, "album/one.jpg"),
        smbPhotoCacheKey("nas", "other", "viewer", null, "album/one.jpg"),
        smbPhotoCacheKey("nas", "photos", "other", null, "album/one.jpg"),
        smbPhotoCacheKey("nas", "photos", "viewer", "other", "album/one.jpg"),
        smbPhotoCacheKey("nas", "photos", "viewer", null, "album/two.jpg"),
        smbPhotoCacheKey("nas", "photos/viewer", "", null, "album/one.jpg"))
    val cache = cache()
    cache.putImage(baseline, "private-photo".toByteArray())
    variants.forEach { assertNull(cache.getIfPresent(it, false)) }
    assertEquals(baseline, smbPhotoCacheKey("NAS", "photos", "viewer", null, "album/one.jpg"))
  }

  @Test fun corruptHitRecoversThroughSameSource() {
    val cache = cache()
    cache.putImage(key, "corrupt".toByteArray())
    assertEquals("fresh", fetch(cache) { "fresh".toByteArray() })
    assertEquals("fresh", cache.imageFile(key).readText())
  }

  @Test fun writeFailureOrImmediateEvictionStillReturnsPhoto() {
    assertEquals("photo", fetch(cache(1)) { "photo".toByteArray() })
    assertFalse(cache(1).isCached(key, false))
    val blocked = tmp.newFile("not-a-directory")
    assertEquals("photo", fetch(MediaCache(blocked, 1024)) { "photo".toByteArray() })
  }

  @Test fun cancellationAfterReadDoesNotPublishOrDecode() {
    var current = true
    val cache = cache()
    assertNull(fetch(cache, { current }) { current = false; "late".toByteArray() })
    assertFalse(cache.isCached(key, false))
  }

  @Test fun cancellationAfterDecodeDiscardsOwnedResult() {
    var current = true
    var discarded: String? = null
    val result = fetchCachedSmbPhoto<String>(null, key, { current },
        { "late".toByteArray() }, { error("No cache") },
        { current = false; "decoded" }, { discarded = it })
    assertNull(result)
    assertEquals("decoded", discarded)
  }

  @Test fun boundedReadsAndExceptionsCloseAndUnregister() {
    for (fail in listOf(false, true)) {
      var closed = false
      var registered = false
      val input = object : ByteArrayInputStream(ByteArray(9)) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (fail) throw java.io.IOException("partial SMB read")
          return super.read(b, off, len)
        }
        override fun close() { closed = true; super.close() }
      }
      assertNull(readSmbPhotoEncoded({ input }, { true },
          { registered = true; true }, { registered = false }, 8))
      assertTrue(closed)
      assertFalse(registered)
    }
  }

  @Test fun heldReadCancellationClosesStreamAndDoesNotPublish() {
    val current = AtomicBoolean(true)
    val opened = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val tracked = AtomicReference<InputStream>()
    val result = AtomicReference<ByteArray?>()
    val input = object : InputStream() {
      override fun read(): Int = error("Buffered read expected")
      override fun read(b: ByteArray, off: Int, len: Int): Int {
        opened.countDown()
        check(closed.await(2, TimeUnit.SECONDS))
        throw java.io.IOException("closed by owner")
      }
      override fun close() { closed.countDown() }
    }
    val worker = Thread {
      result.set(readSmbPhotoEncoded({ input }, current::get,
          { tracked.set(it); current.get() }, { tracked.compareAndSet(it, null) }, 8))
    }
    worker.start()
    try {
      assertTrue(opened.await(2, TimeUnit.SECONDS))
      current.set(false)
      tracked.get().close()
      worker.join(2000)
      assertFalse(worker.isAlive)
      assertNull(result.get())
      assertNull(tracked.get())
    } finally {
      current.set(false)
      input.close()
      worker.join(2000)
    }
  }
}
