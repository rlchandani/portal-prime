/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Bounded RFC 6455 text transport. Blocking; use off the main thread, with one reader.
 * Existing MaControl connect/sendText/readText/close calls remain supported.
 *
 * This is raw LAN ws, with no TLS. Supply a bare host; https/wss URLs are rejected,
 * never downgraded. Tokens sent over it require a trusted private network. A TLS
 * implementation can be injected into MaMusicClient without changing its protocol.
 */
class MaWebSocket(
    private val host: String,
    private val port: Int,
    private val path: String = "/ws",
    private val socketFactory: () -> Socket = { Socket() },
) {
  private val lifecycle = Any()
  private val writer = Any()
  private val reader = Any()
  private val random = SecureRandom()
  @Volatile private var socket: Socket? = null
  @Volatile private var input: InputStream? = null
  @Volatile private var output: OutputStream? = null
  @Volatile private var closed = false
  private var messageTimeoutMs = 6000

  fun connect(timeoutMs: Int = 6000): Boolean {
    require(validHost(host) && port in 1..65535 && timeoutMs in 1..60_000 &&
        path.startsWith('/') && path.length <= 2048 && path.all { it.code in 33..126 }) {
      "Invalid or unsupported WebSocket endpoint"
    }
    val s = synchronized(lifecycle) {
      check(!closed && socket == null) { "WebSocket is closed or already connected" }
      socketFactory().also { socket = it }
    }
    try {
      s.connect(InetSocketAddress(host.removeSurrounding("[", "]"), port), timeoutMs)
      s.soTimeout = timeoutMs
      messageTimeoutMs = timeoutMs
      val i = s.getInputStream()
      val o = s.getOutputStream()
      val key = base64(ByteArray(16).also { random.nextBytes(it) })
      val authority = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
      val request = "GET $path HTTP/1.1\r\nHost: $authority:$port\r\n" +
          "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\n" +
          "Sec-WebSocket-Version: 13\r\n\r\n"
      o.write(request.toByteArray(Charsets.ISO_8859_1))
      o.flush()
      if (!verifyHandshake(i, key, deadline(timeoutMs))) {
        close()
        return false
      }
      synchronized(lifecycle) {
        if (closed || socket !== s) throw IOException("WebSocket closed during connect")
        input = i
        output = o
      }
      return true
    } catch (e: Exception) {
      close()
      throw e
    }
  }

  /** An idle timeout is safe to poll; a timeout inside a frame closes the socket. */
  fun setReadTimeout(timeoutMs: Int) {
    require(timeoutMs in 1..60_000)
    synchronized(lifecycle) { socket?.soTimeout = timeoutMs }
  }

  private fun verifyHandshake(i: InputStream, key: String, until: Long): Boolean {
    val bytes = ByteArrayOutputStream()
    var suffix = 0
    var lineBytes = 0
    while (suffix != 4) {
      if (bytes.size() >= MAX_HEADERS || lineBytes >= MAX_HEADER_LINE) {
        throw IOException("WebSocket headers exceed limit")
      }
      val b = required(i, until)
      if (b != 13 && b != 10 && b !in 32..126 && b != 9) {
        throw IOException("Invalid WebSocket header")
      }
      bytes.write(b)
      lineBytes = if (b == 10) 0 else lineBytes + 1
      suffix = when {
        b == (if (suffix % 2 == 0) 13 else 10) -> suffix + 1
        b == 13 -> 1
        else -> 0
      }
    }
    val lines = bytes.toString("ISO-8859-1").removeSuffix("\r\n\r\n").split("\r\n")
    if (!lines.first().matches(Regex("HTTP/1\\.1 101(?: [^\\r\\n]*)?")) || lines.size > 65) return false
    val headers = HashMap<String, String>()
    for (line in lines.drop(1)) {
      val colon = line.indexOf(':')
      if (colon <= 0 || line[0].isWhitespace()) return false
      val name = line.substring(0, colon).lowercase(java.util.Locale.ROOT)
      if (!name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return false
      // Reject duplicate fields, including ambiguous accept/upgrade headers.
      if (headers.put(name, line.substring(colon + 1).trim()) != null) return false
    }
    val expected = base64(MessageDigest.getInstance("SHA-1")
        .digest((key + GUID).toByteArray(Charsets.ISO_8859_1)))
    fun hasToken(name: String, token: String) = headers[name]?.split(',')
        ?.any { it.trim().equals(token, ignoreCase = true) } == true
    return headers["sec-websocket-accept"] == expected && hasToken("upgrade", "websocket") &&
        hasToken("connection", "upgrade") && !headers.containsKey("sec-websocket-extensions") &&
        !headers.containsKey("sec-websocket-protocol")
  }

  /** Client frames are masked; malformed UTF16 and >2,000,000 UTF8 bytes are rejected. */
  fun sendText(text: String) {
    require(text.length <= MAX_MESSAGE_BYTES) { "WebSocket message exceeds limit" }
    val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
    require(encoded.remaining() <= MAX_MESSAGE_BYTES) { "WebSocket message exceeds limit" }
    val payload = ByteArray(encoded.remaining()).also { encoded.get(it) }
    sendFrame(1, payload)
  }

  private fun sendFrame(opcode: Int, payload: ByteArray) = synchronized(writer) {
    val o = output?.takeUnless { closed } ?: throw IOException("WebSocket is not connected")
    val header = ByteArrayOutputStream(14)
    header.write(0x80 or opcode)
    val size = payload.size
    when {
      size < 126 -> header.write(0x80 or size)
      size < 65536 -> { header.write(0xFE); header.write(size ushr 8); header.write(size) }
      else -> {
        header.write(0xFF)
        for (shift in 7 downTo 0) header.write((size.toLong() ushr (8 * shift)).toInt())
      }
    }
    val mask = ByteArray(4).also { random.nextBytes(it) }
    header.write(mask)
    for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
    try {
      o.write(header.toByteArray())
      o.write(payload)
      o.flush()
    } catch (e: IOException) { close(); throw e }
  }

  /** Complete UTF8 text only; bounded fragments, ping/pong, and validated close frames. */
  fun readText(): String? = synchronized(reader) {
    val i = input?.takeUnless { closed } ?: return@synchronized null
    // A timeout before ANY frame byte does not lose parser state.
    val first = i.read()
    if (first < 0) { close(); return@synchronized null }
    try {
      readMessage(i, first, deadline(messageTimeoutMs))
    } catch (e: IdleReadTimeout) { throw e }
      catch (e: IOException) { close(); throw e }
  }

  private fun readMessage(i: InputStream, first: Int, until: Long): String? {
    val message = ByteArrayOutputStream()
    var fragmented = false
    var b0 = first
    repeat(MAX_FRAMES_PER_MESSAGE) {
      val fin = b0 and 0x80 != 0
      val opcode = b0 and 15
      if (b0 and 0x70 != 0 || opcode !in setOf(0, 1, 8, 9, 10)) {
        throw IOException("Unsupported WebSocket frame")
      }
      val b1 = required(i, until)
      if (b1 and 0x80 != 0) throw IOException("Masked WebSocket server frame")
      var size = (b1 and 127).toLong()
      if (size == 126L) {
        size = (required(i, until).toLong() shl 8) or required(i, until).toLong()
        if (size < 126) throw IOException("Nonminimal WebSocket length")
      } else if (size == 127L) {
        size = required(i, until).toLong()
        if (size and 128 != 0L) throw IOException("Negative WebSocket length")
        repeat(7) { size = (size shl 8) or required(i, until).toLong() }
        if (size < 65536) throw IOException("Nonminimal WebSocket length")
      }
      val control = opcode >= 8
      if (size > MAX_MESSAGE_BYTES || (control && (!fin || size > 125)) ||
          (!control && size > MAX_MESSAGE_BYTES - message.size())) {
        throw IOException("WebSocket frame or message exceeds limit")
      }
      if ((opcode == 0 && !fragmented) || (opcode == 1 && fragmented)) {
        throw IOException("Invalid WebSocket continuation")
      }
      val payload = ByteArray(size.toInt())
      var count = 0
      while (count < payload.size) {
        checkDeadline(until)
        val n = i.read(payload, count, payload.size - count)
        if (n < 0) throw EOFException("Truncated WebSocket frame")
        if (n == 0) continue
        count += n
      }
      when (opcode) {
        0, 1 -> {
          message.write(payload)
          if (fin) return decodeUtf8(message.toByteArray())
          fragmented = true
        }
        9 -> sendFrame(10, payload)
        8 -> {
          validateClose(payload)
          try { sendFrame(8, payload) } finally { close() }
          return null
        }
      }
      b0 = try {
        required(i, until)
      } catch (e: SocketTimeoutException) {
        // A complete standalone ping/pong leaves no parser state to discard. A
        // timeout between text fragments still closes via readText's IOException path.
        if (!fragmented) throw IdleReadTimeout()
        throw e
      }
    }
    throw IOException("Too many WebSocket fragments or control frames")
  }

  private class IdleReadTimeout : SocketTimeoutException("WebSocket idle timeout")

  private fun validateClose(payload: ByteArray) {
    if (payload.size == 1) throw IOException("Invalid WebSocket close")
    if (payload.size < 2) return
    val code = ((payload[0].toInt() and 255) shl 8) or (payload[1].toInt() and 255)
    if (code !in 1000..1014 && code !in 3000..4999 || code in setOf(1004, 1005, 1006)) {
      throw IOException("Invalid WebSocket close code")
    }
    decodeUtf8(payload.copyOfRange(2, payload.size))
  }

  private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(bytes)).toString()

  private fun required(i: InputStream, until: Long): Int {
    checkDeadline(until)
    val value = i.read()
    if (value < 0) throw EOFException("Truncated WebSocket data")
    return value
  }

  private fun checkDeadline(until: Long) {
    if (System.nanoTime() >= until) throw SocketTimeoutException("WebSocket message timeout")
  }

  /** Terminal, idempotent, and independent of reader/writer locks; unblocks socket I/O. */
  fun close() {
    val s = synchronized(lifecycle) {
      closed = true
      input = null
      output = null
      socket.also { socket = null }
    }
    runCatching { s?.close() }
  }

  companion object {
    const val MAX_MESSAGE_BYTES = 2_000_000 // below 2 MiB, also the pure adapter's bound
    private const val MAX_HEADERS = 16_384
    private const val MAX_HEADER_LINE = 4096
    private const val MAX_FRAMES_PER_MESSAGE = 1024
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    internal fun validHost(value: String): Boolean = value.length in 1..253 &&
        value.none { it.code <= 32 || it.code >= 127 || it in "/\\?#@" } && !value.contains("://")
    private fun deadline(timeoutMs: Int) = System.nanoTime() + timeoutMs * 1_000_000L

    // java.util.Base64 requires API 26; retain the project's API 24 floor without Android stubs.
    private fun base64(bytes: ByteArray): String {
      val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
      val result = StringBuilder((bytes.size + 2) / 3 * 4)
      var index = 0
      while (index < bytes.size) {
        val a = bytes[index++].toInt() and 255
        val b = if (index < bytes.size) bytes[index++].toInt() and 255 else -1
        val c = if (index < bytes.size) bytes[index++].toInt() and 255 else -1
        result.append(alphabet[a ushr 2])
        result.append(alphabet[((a and 3) shl 4) or (if (b >= 0) b ushr 4 else 0)])
        result.append(if (b >= 0) alphabet[((b and 15) shl 2) or (if (c >= 0) c ushr 6 else 0)] else '=')
        result.append(if (c >= 0) alphabet[c and 63] else '=')
      }
      return result.toString()
    }
  }
}
