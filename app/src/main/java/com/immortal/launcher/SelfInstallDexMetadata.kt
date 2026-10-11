/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Required dex metadata for the opt-in self-install experiment, paired as base.apk / base.dm. */
object SelfInstallDexMetadata {
  private const val PROFILE_PATH = "assets/dexopt/baseline.prof"
  private const val METADATA_PATH = "assets/dexopt/baseline.profm"
  private const val MAX_PROFILE_BYTES = 1536 * 1024
  private const val MAX_METADATA_BYTES = 64 * 1024
  private const val ZIP_OVERHEAD_BYTES = 1024
  private val profileHeader = byteArrayOf(0x70, 0x72, 0x6f, 0, 0x30, 0x31, 0x30, 0)
  private val metadataHeader = byteArrayOf(0x70, 0x72, 0x6d, 0, 0x30, 0x30, 0x32, 0)

  fun eligible(
      enabled: Boolean,
      sdkInt: Int,
      requestedPackage: String,
      installerPackage: String,
      archivePackage: String?,
  ): Boolean =
      enabled && sdkInt in 28..30 &&
          requestedPackage == installerPackage && archivePackage == installerPackage

  /** Match only the designated APK so an experimental installer retains an APK-only rollback. */
  fun matchesTarget(apk: File, expectedSha256: String): Boolean {
    require(expectedSha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid profile target hash" }
    if (!apk.isFile || apk.length() !in 1..(64L * 1024 * 1024)) return false
    return try {
      val digest = MessageDigest.getInstance("SHA-256")
      var total = 0L
      apk.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (true) {
          val count = input.read(buffer)
          if (count == -1) break
          total += count
          if (total > 64L * 1024 * 1024) return false
          digest.update(buffer, 0, count)
        }
      }
      val hex = digest.digest().joinToString("") { "%02x".format(it) }
      hex == expectedSha256
    } catch (_: IOException) {
      false
    } catch (_: SecurityException) {
      false
    }
  }

  /** Reject invalid inputs with a fixed error, without retaining archive paths or underlying causes. */
  fun prepareRequired(apk: File): ByteArray =
      try {
        ZipFile(apk).use { zip ->
          val profileEntry = requiredEntry(zip, PROFILE_PATH)
          val metadataEntry = requiredEntry(zip, METADATA_PATH)
          val profile = readRequired(zip, profileEntry, profileHeader, MAX_PROFILE_BYTES)
          val metadata = readRequired(zip, metadataEntry, metadataHeader, MAX_METADATA_BYTES)
          val output = ByteArrayOutputStream(profile.size + metadata.size + ZIP_OVERHEAD_BYTES)
          ZipOutputStream(output).use { result ->
            writeStored(result, "primary.prof", profile)
            writeStored(result, "primary.profm", metadata)
          }
          if (output.size() > MAX_PROFILE_BYTES + MAX_METADATA_BYTES + ZIP_OVERHEAD_BYTES) reject()
          output.toByteArray()
        }
      } catch (_: IOException) {
        reject()
      } catch (_: IllegalArgumentException) {
        reject()
      } catch (_: SecurityException) {
        reject()
      }

  private fun requiredEntry(zip: ZipFile, name: String): ZipEntry {
    var found: ZipEntry? = null
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
      val entry = entries.nextElement()
      if (entry.name == name) {
        if (found != null) reject()
        found = entry
      }
    }
    return found ?: reject()
  }

  private fun readRequired(zip: ZipFile, entry: ZipEntry, header: ByteArray, limit: Int): ByteArray {
    if (entry.isDirectory || entry.size !in header.size.toLong()..limit.toLong()) reject()
    val output = ByteArrayOutputStream(minOf(entry.size.toInt(), 8192))
    val crc = CRC32()
    zip.getInputStream(entry).use { input ->
      val buffer = ByteArray(8192)
      while (true) {
        // Read at most one byte beyond the cap, even when the ZIP's declared size is dishonest.
        val remaining = limit - output.size()
        val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
        if (count == -1) break
        if (count > remaining) reject()
        output.write(buffer, 0, count)
        crc.update(buffer, 0, count)
      }
    }
    if (output.size().toLong() != entry.size || crc.value != entry.crc) reject()
    val bytes = output.toByteArray()
    if (bytes.size < header.size || header.indices.any { bytes[it] != header[it] }) reject()
    return bytes
  }

  private fun writeStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
    // STORED keeps the output bound independent of compression ratios.
    val entry = ZipEntry(name).apply {
      method = ZipEntry.STORED
      size = bytes.size.toLong()
      compressedSize = bytes.size.toLong()
      crc = CRC32().apply { update(bytes) }.value
    }
    zip.putNextEntry(entry)
    zip.write(bytes)
    zip.closeEntry()
  }

  private fun reject(): Nothing = throw IllegalArgumentException("Invalid self-install dex metadata")
}
