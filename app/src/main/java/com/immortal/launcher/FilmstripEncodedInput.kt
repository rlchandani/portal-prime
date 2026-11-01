/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.EOFException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.zip.CRC32

/** The same conservative container parser can inspect owned bytes or a bounded file window. */
internal interface FilmstripEncodedInput {
  val size: Int
  operator fun get(position: Int): Byte
  fun checksum(position: Int, length: Int): Long
}

internal class FilmstripEncodedBytes(private val bytes: ByteArray) : FilmstripEncodedInput {
  override val size: Int get() = bytes.size
  override fun get(position: Int): Byte = bytes[position]
  override fun checksum(position: Int, length: Int): Long =
      CRC32().apply { update(bytes, position, length) }.value
}

/**
 * One 8 KiB page, independent of file length. Payload jumps seek rather than buffer the image.
 * The caller owns the handle; offsets and length are from that same open file descriptor.
 */
internal class FilmstripEncodedFile(
    private val file: RandomAccessFile,
    override val size: Int,
) : FilmstripEncodedInput {
  private val page = ByteArray(8192)
  private var pageStart = -1
  private var pageLength = 0

  override fun get(position: Int): Byte {
    load(position)
    return page[position - pageStart]
  }

  override fun checksum(position: Int, length: Int): Long {
    if (position < 0 || length < 0 || position.toLong() + length > size)
      throw EOFException("Incomplete image metadata")
    val crc = CRC32()
    var offset = position
    val end = position + length
    while (offset < end) {
      load(offset)
      val count = minOf(end - offset, pageLength - (offset - pageStart))
      crc.update(page, offset - pageStart, count)
      offset += count
    }
    return crc.value
  }

  private fun load(position: Int) {
    checkpoint()
    if (position !in 0 until size) throw EOFException("Incomplete image metadata")
    val start = position / page.size * page.size
    if (start == pageStart) return
    val count = minOf(page.size, size - start)
    file.seek(start.toLong())
    file.readFully(page, 0, count)
    checkpoint()
    pageStart = start
    pageLength = count
  }

  companion object {
    fun checkpoint() {
      if (Thread.currentThread().isInterrupted)
        throw InterruptedIOException("Filmstrip image scan interrupted")
    }
  }
}
