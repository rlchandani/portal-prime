/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.Locale

/** Length-prefixed source identity cannot collide across shares/accounts or separator characters. */
internal fun smbPhotoCacheKey(
    host: String, share: String, user: String, domain: String?, path: String,
): String = "smb:" + listOf(host.lowercase(Locale.ROOT), share, user, domain.orEmpty(),
    path.replace('\\', '/')).joinToString("") { "${it.length}:$it" }

/** Worker-only cache orchestration; successful decoded values remain owned by the caller. */
internal fun <T : Any> fetchCachedSmbPhoto(
    cache: MediaCache?, key: String, isCurrent: () -> Boolean,
    read: () -> ByteArray?, decodeFile: (File) -> T?, decodeBytes: (ByteArray) -> T?,
    discard: (T) -> Unit,
): T? {
  fun admit(value: T?): T? {
    if (value == null) return null
    if (isCurrent()) return value
    discard(value)
    return null
  }
  if (!isCurrent()) return null
  cache?.getIfPresent(key, false)?.let { file ->
    val decoded = runCatching { decodeFile(file) }.getOrNull()
    if (decoded != null) return admit(decoded)
  }
  if (!isCurrent()) return null
  val encoded = read() ?: return null
  if (!isCurrent()) return null
  cache?.putImage(key, encoded)
  if (!isCurrent()) return null
  // Publication can fail or immediately evict the file; downloaded bytes still work.
  return admit(decodeBytes(encoded))
}

/** Register the physical stream before blocking reads so controller retirement can close it. */
internal fun readSmbPhotoEncoded(
    open: () -> InputStream?, isCurrent: () -> Boolean,
    register: (InputStream) -> Boolean, unregister: (InputStream) -> Unit,
    maxBytes: Int = SlideshowDecodeBudget.MAX_ENCODED_BYTES,
): ByteArray? = runCatching {
  if (!isCurrent()) return@runCatching null
  val input = open() ?: return@runCatching null
  try {
    if (!register(input)) return@runCatching null
    val guarded = object : FilterInputStream(input) {
      override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isCurrent()) throw InterruptedIOException("SMB photo request canceled")
        return super.read(buffer, offset, length)
      }
      override fun read(): Int {
        if (!isCurrent()) throw InterruptedIOException("SMB photo request canceled")
        return super.read()
      }
    }
    SlideshowDecoder.readEncoded(guarded, maxBytes)?.takeIf { isCurrent() }
  } finally {
    unregister(input)
    runCatching { input.close() }
  }
}.getOrNull()
