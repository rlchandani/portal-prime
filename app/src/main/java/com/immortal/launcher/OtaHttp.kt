/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** The body exceeded what the manifest promised; reported as an integrity failure, not a network one. */
internal class OtaIntegrityException(message: String) : IOException(message)

internal data class OtaDownloaded(val bytes: Long, val sha256: String)

/**
 * Update transport. Redirects are followed by hand so every hop stays on [OtaUrlPolicy]'s
 * GitHub hosts over https; bodies are size-bounded and hashed while streaming.
 */
internal object OtaHttp {
  private const val BUFFER_BYTES = 64 * 1024
  private const val HTTP_OK = 200
  private val REDIRECTS = 300..399

  fun fetchManifest(url: String = OtaConstants.FEED_URL): String {
    val connection = open(url)
    return try {
      StoreMetadataHttp.readText(connection, OtaConstants.MAX_MANIFEST_BYTES)
    } finally {
      connection.disconnect()
    }
  }

  /** Streams [manifest]'s APK into [destination] (published only when complete). */
  fun downloadApk(manifest: OtaManifest, destination: File): OtaDownloaded {
    val connection = open(manifest.apkUrl)
    try {
      val declared = connection.contentLengthLong
      if (declared >= 0 && declared != manifest.size) {
        throw OtaIntegrityException("declared length $declared differs from the manifest")
      }
      var result: OtaDownloaded? = null
      FleetUploadWriter.write(destination) { output ->
        connection.inputStream.use { input -> copyBounded(input, output, manifest.size) }.also { result = it }.bytes
      }
      return result ?: throw IOException("download produced no result")
    } finally {
      connection.disconnect()
    }
  }

  /** Copies at most [limit] bytes, hashing as it goes; one byte more fails the download. */
  fun copyBounded(input: InputStream, output: OutputStream, limit: Long): OtaDownloaded {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    while (true) {
      if (Thread.currentThread().isInterrupted) throw IOException("download interrupted")
      var count = input.read(buffer)
      if (count == 0) {
        // Some transports return zero from a bulk read; a single-byte read makes progress.
        val next = input.read()
        if (next < 0) break
        buffer[0] = next.toByte()
        count = 1
      }
      if (count < 0) break
      if (count > limit - total) throw OtaIntegrityException("body exceeds the manifest size")
      output.write(buffer, 0, count)
      digest.update(buffer, 0, count)
      total += count
    }
    return OtaDownloaded(total, OtaIntegrity.hex(digest.digest()))
  }

  private fun open(start: String): HttpURLConnection {
    if (!OtaUrlPolicy.isAllowedHop(start)) throw IOException("update URL not allowed")
    var url = start
    repeat(OtaConstants.MAX_REDIRECTS + 1) {
      val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = OtaConstants.CONNECT_TIMEOUT_MS
        readTimeout = OtaConstants.READ_TIMEOUT_MS
        setRequestProperty("User-Agent", OtaConstants.USER_AGENT)
      }
      val code = try {
        connection.responseCode
      } catch (e: IOException) {
        connection.disconnect()
        throw e
      }
      if (code == HTTP_OK) return connection
      val location = connection.getHeaderField("Location")
      connection.disconnect()
      if (code !in REDIRECTS) throw IOException("HTTP $code")
      url = OtaUrlPolicy.nextHop(url, location) ?: throw IOException("redirect target not allowed")
    }
    throw IOException("too many redirects")
  }
}
