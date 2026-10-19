/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** Android-free byte/framing and decode admission rules shared by both artwork transports. */
object MusicArtworkPolicy {
  const val MAX_BODY_BYTES = 8 * 1024 * 1024
  private const val MAX_HEADER_BYTES = 32 * 1024
  private const val MAX_CHUNK_LINE_BYTES = 1024
  private const val MAX_CHUNK_FRAMING_BYTES = 256 * 1024
  private const val BUFFER_BYTES = 8192
  private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

  data class HttpResponse(val statusCode: Int, val location: String?, val body: ByteArray)

  /** Read to EOF, rejecting excess during the read; the caller cannot raise the hard cap. */
  fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
    val limit = bodyLimit(maxBytes)
    val out = ByteArrayOutputStream(minOf(limit, BUFFER_BYTES))
    val buffer = ByteArray(BUFFER_BYTES)
    while (out.size() < limit) {
      val count = readSome(input, buffer, minOf(buffer.size, limit - out.size()))
      if (count < 0) return out.toByteArray()
      out.write(buffer, 0, count)
    }
    if (input.read() != -1) throw IOException("Artwork body exceeds its byte budget")
    return out.toByteArray()
  }

  /** Smallest representable power of two whose rounded-up decoded edges fit [maxEdge]. */
  fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int? {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return null
    val longest = maxOf(width, height).toLong()
    var sample = 1
    while ((longest + sample - 1) / sample > maxEdge) {
      if (sample > Int.MAX_VALUE / 2) return null
      sample *= 2
    }
    return sample
  }

  /**
   * Parse one HTTP/1.x response without draining redirects/errors or reading past a framed
   * body. Headers/trailers and chunk framing have separate small budgets. Throws on malformed,
   * unsupported, excessive or truncated framing; partial artwork is never returned.
   */
  fun readHttpResponse(input: InputStream, maxBytes: Int): HttpResponse {
    val limit = bodyLimit(maxBytes)
    val headerBudget = ReadBudget(MAX_HEADER_BYTES)
    val status = readLine(input, headerBudget).split(" ", limit = 3)
    if (status.size < 2 || status[0] !in listOf("HTTP/1.0", "HTTP/1.1") ||
        status[1].length != 3 || !status[1].all { it in '0'..'9' }) {
      throw IOException("Malformed artwork HTTP status")
    }
    val code = status[1].toInt()
    if (code !in 100..599) throw IOException("Invalid artwork HTTP status")
    val headers = readHeaders(input, headerBudget)
    val location = headers["location"]
    if (code !in 200..299 || code == 204 || code == 205) {
      return HttpResponse(code, location, byteArrayOf())
    }
    val encoding = headers["transfer-encoding"]
    val length = headers["content-length"]
    val body = when {
      encoding != null -> {
        if (!encoding.equals("chunked", ignoreCase = true) || length != null) {
          throw IOException("Unsupported or conflicting artwork transfer framing")
        }
        readChunked(input, limit)
      }
      length != null -> {
        val count = parseSize(length, radix = 10)
        if (count > limit) throw IOException("Declared artwork body exceeds its byte budget")
        val out = ByteArrayOutputStream(minOf(count.toInt(), BUFFER_BYTES))
        copyExactly(input, out, count.toInt(), ByteArray(BUFFER_BYTES))
        out.toByteArray()
      }
      else -> readBounded(input, limit)
    }
    return HttpResponse(code, location, body)
  }

  private fun bodyLimit(maxBytes: Int): Int {
    require(maxBytes >= 0) { "Artwork byte budget must be nonnegative" }
    return minOf(maxBytes, MAX_BODY_BYTES)
  }

  private fun readChunked(input: InputStream, limit: Int): ByteArray {
    val framing = ReadBudget(MAX_CHUNK_FRAMING_BYTES)
    val out = ByteArrayOutputStream(minOf(limit, BUFFER_BYTES))
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
      val line = readLine(input, framing, MAX_CHUNK_LINE_BYTES)
      val size = parseSize(line.substringBefore(";"), radix = 16)
      if (size > limit - out.size()) throw IOException("Chunked artwork exceeds its byte budget")
      if (size == 0L) {
        readHeaders(input, ReadBudget(minOf(MAX_HEADER_BYTES, framing.remaining)))
        return out.toByteArray()
      }
      copyExactly(input, out, size.toInt(), buffer)
      if (readByte(input, framing) != 13 || readByte(input, framing) != 10) {
        throw IOException("Missing artwork chunk terminator")
      }
    }
  }

  private fun parseSize(value: String, radix: Int): Long {
    if (value.isEmpty() || !value.all {
          it in '0'..'9' || (radix == 16 && (it in 'a'..'f' || it in 'A'..'F'))
        }) throw IOException("Malformed artwork body/chunk size")
    return value.toLongOrNull(radix) ?: throw IOException("Artwork body/chunk size overflows")
  }

  private fun readHeaders(input: InputStream, budget: ReadBudget): Map<String, String> {
    val headers = mutableMapOf<String, String>()
    while (true) {
      val line = readLine(input, budget)
      if (line.isEmpty()) return headers
      val colon = line.indexOf(':')
      if (colon <= 0 || !headerName.matches(line.substring(0, colon))) {
        throw IOException("Malformed artwork HTTP header")
      }
      val name = line.substring(0, colon).lowercase(Locale.ROOT)
      val value = line.substring(colon + 1).trim()
      val previous = headers[name]
      if (previous != null && name in listOf("content-length", "transfer-encoding", "location") &&
          (name != "content-length" || previous != value)) {
        throw IOException("Conflicting artwork HTTP headers")
      }
      headers[name] = value
    }
  }

  private class ReadBudget(var remaining: Int)

  /** Count CRLF bytes too, and fail before allocating/reading an excessive line. */
  private fun readLine(input: InputStream, budget: ReadBudget,
      lineLimit: Int = MAX_HEADER_BYTES): String {
    val out = ByteArrayOutputStream(128)
    var remaining = lineLimit
    fun next(): Int {
      if (remaining-- <= 0) throw IOException("Artwork HTTP line exceeds its byte budget")
      return readByte(input, budget)
    }
    while (true) {
      val byte = next()
      if (byte == 13) {
        if (next() != 10) throw IOException("Malformed artwork HTTP line terminator")
        return out.toString("ISO-8859-1")
      }
      if (byte == 10 || (byte < 32 && byte != 9) || byte == 127) {
        throw IOException("Invalid artwork HTTP line character")
      }
      out.write(byte)
    }
  }

  private fun readByte(input: InputStream, budget: ReadBudget): Int {
    if (budget.remaining-- <= 0) throw IOException("Artwork HTTP framing exceeds its byte budget")
    return input.read().takeIf { it >= 0 } ?: throw IOException("Truncated artwork HTTP framing")
  }

  private fun copyExactly(input: InputStream, out: ByteArrayOutputStream,
      length: Int, buffer: ByteArray) {
    var remaining = length
    while (remaining > 0) {
      val count = readSome(input, buffer, minOf(remaining, buffer.size))
      if (count < 0) throw IOException("Truncated artwork HTTP body")
      out.write(buffer, 0, count)
      remaining -= count
    }
  }

  private fun readSome(input: InputStream, buffer: ByteArray, length: Int): Int {
    val count = input.read(buffer, 0, length)
    if (count != 0) return count
    val byte = input.read()
    if (byte < 0) return -1
    buffer[0] = byte.toByte()
    return 1
  }
}
