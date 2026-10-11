/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SelfInstallDexMetadataTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  private val installerPackage = "com.immortal.launcher"
  private val profilePath = "assets/dexopt/baseline.prof"
  private val metadataPath = "assets/dexopt/baseline.profm"
  private val profileHeader = byteArrayOf(0x70, 0x72, 0x6f, 0, 0x30, 0x31, 0x30, 0)
  private val metadataHeader = byteArrayOf(0x70, 0x72, 0x6d, 0, 0x30, 0x30, 0x32, 0)
  private val profile = profileHeader + byteArrayOf(1, 2, 3, 4)
  private val metadata = metadataHeader + byteArrayOf(5, 6, 7)
  private val profileLimit = 1536 * 1024
  private val metadataLimit = 64 * 1024

  @Test
  fun eligible_acceptsExplicitSelfInstallOnApis28Through30() {
    for (sdk in listOf(28, 29, 30)) {
      for (pkg in listOf(installerPackage, "$installerPackage.debug")) {
        assertTrue(SelfInstallDexMetadata.eligible(true, sdk, pkg, pkg, pkg))
      }
    }
  }

  @Test
  fun eligible_rejectsDisabledExperiment() {
    assertFalse(SelfInstallDexMetadata.eligible(false, 29, installerPackage, installerPackage, installerPackage))
  }

  @Test
  fun eligible_rejectsUnsupportedApis() {
    for (sdk in listOf(Int.MIN_VALUE, -1, 24, 27, 31, 36, Int.MAX_VALUE)) {
      assertFalse(SelfInstallDexMetadata.eligible(true, sdk, installerPackage, installerPackage, installerPackage))
    }
  }

  @Test
  fun eligible_rejectsRequestedPackageMismatch() {
    for (pkg in listOf("", "com.other.app", installerPackage.uppercase())) {
      assertFalse(SelfInstallDexMetadata.eligible(true, 29, pkg, installerPackage, installerPackage))
    }
  }

  @Test
  fun eligible_rejectsUnknownOrMismatchedArchivePackage() {
    for (pkg in listOf(null, "", "com.other.app", installerPackage.uppercase())) {
      assertFalse(SelfInstallDexMetadata.eligible(true, 29, installerPackage, installerPackage, pkg))
    }
  }

  @Test
  fun eligible_rejectsOtherAppEvenWhenRequestMatchesArchive() {
    assertFalse(SelfInstallDexMetadata.eligible(true, 29, "com.other.app", installerPackage, "com.other.app"))
  }

  @Test
  fun matchesTarget_acceptsExactDesignatedApk() {
    val bytes = zipBytes(profilePath to profile, metadataPath to metadata)
    assertTrue(SelfInstallDexMetadata.matchesTarget(apk(bytes), sha256(bytes)))
  }

  @Test
  fun matchesTarget_rejectsModifiedApkEvenWhenProfilesMatch() {
    val accepted = zipBytes(profilePath to profile, metadataPath to metadata)
    val changed = zipBytes(profilePath to profile, metadataPath to metadata, "classes.dex" to byteArrayOf(9))
    assertFalse(SelfInstallDexMetadata.matchesTarget(apk(changed), sha256(accepted)))
  }

  @Test
  fun matchesTarget_leavesOtherSelfInstallApksEligibleForApkOnlyRollback() {
    assertFalse(SelfInstallDexMetadata.matchesTarget(apk(byteArrayOf(1)), sha256(byteArrayOf(2))))
  }

  @Test
  fun matchesTarget_rejectsMalformedTargetHashes() {
    for (hash in listOf("", "a".repeat(63), "A".repeat(64), "g".repeat(64))) {
      assertThrows(IllegalArgumentException::class.java) {
        SelfInstallDexMetadata.matchesTarget(apk(profile), hash)
      }
    }
  }

  @Test
  fun matchesTarget_rejectsEmptyMissingOrDirectoryTargets() {
    val target = sha256(profile)
    assertFalse(SelfInstallDexMetadata.matchesTarget(apk(byteArrayOf()), target))
    assertFalse(SelfInstallDexMetadata.matchesTarget(File(temporaryFolder.root, "missing"), target))
    assertFalse(SelfInstallDexMetadata.matchesTarget(temporaryFolder.newFolder(), target))
  }

  @Test
  fun matchesTarget_rejectsOversizedApkBeforeStreamingIt() {
    val apk = temporaryFolder.newFile()
    RandomAccessFile(apk, "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
    assertFalse(SelfInstallDexMetadata.matchesTarget(apk, sha256(profile)))
  }

  private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  @Test
  fun prepareRequired_outputsOnlyPrimaryEntriesWithExactInputBytes() {
    val apk = apk(
        zipBytes(
            "AndroidManifest.xml" to byteArrayOf(9),
            "classes.dex" to byteArrayOf(8),
            "primary.prof" to byteArrayOf(7),
            "primary.profm" to byteArrayOf(6),
            metadataPath to metadata,
            profilePath to profile,
        ))

    assertOutput(SelfInstallDexMetadata.prepareRequired(apk), profile, metadata)
  }

  @Test
  fun prepareRequired_rejectsMissingProfile() {
    assertRejected(apk(zipBytes(metadataPath to metadata)))
  }

  @Test
  fun prepareRequired_rejectsMissingMetadata() {
    assertRejected(apk(zipBytes(profilePath to profile)))
  }

  @Test
  fun prepareRequired_rejectsEmptyZip() {
    assertRejected(apk(zipBytes()))
  }

  @Test
  fun prepareRequired_rejectsDuplicateProfile() {
    assertDuplicateRejected(profilePath, "assets/dexopt/baseline.prox", profile)
  }

  @Test
  fun prepareRequired_rejectsDuplicateMetadata() {
    assertDuplicateRejected(metadataPath, "assets/dexopt/baseline.profx", metadata)
  }

  @Test
  fun prepareRequired_rejectsWrongProfileMagic() {
    for (index in 0..3) {
      val invalid = profile.copyOf()
      invalid[index] = (invalid[index].toInt() xor 1).toByte()
      assertRejected(apk(zipBytes(profilePath to invalid, metadataPath to metadata)))
    }
  }

  @Test
  fun prepareRequired_rejectsWrongMetadataMagic() {
    for (index in 0..3) {
      val invalid = metadata.copyOf()
      invalid[index] = (invalid[index].toInt() xor 1).toByte()
      assertRejected(apk(zipBytes(profilePath to profile, metadataPath to invalid)))
    }
  }

  @Test
  fun prepareRequired_rejectsWrongProfileVersions() {
    for (version in listOf("009\u0000", "015\u0000", "010x")) {
      val invalid = profile.copyOf()
      version.toByteArray(Charsets.US_ASCII).copyInto(invalid, 4)
      assertRejected(apk(zipBytes(profilePath to invalid, metadataPath to metadata)))
    }
  }

  @Test
  fun prepareRequired_rejectsWrongMetadataVersions() {
    for (version in listOf("001\u0000", "003\u0000", "002x")) {
      val invalid = metadata.copyOf()
      version.toByteArray(Charsets.US_ASCII).copyInto(invalid, 4)
      assertRejected(apk(zipBytes(profilePath to profile, metadataPath to invalid)))
    }
  }

  @Test
  fun prepareRequired_rejectsTruncatedProfileHeaders() {
    for (size in 0..7) {
      assertRejected(apk(zipBytes(profilePath to profileHeader.copyOf(size), metadataPath to metadata)))
    }
  }

  @Test
  fun prepareRequired_rejectsTruncatedMetadataHeaders() {
    for (size in 0..7) {
      assertRejected(apk(zipBytes(profilePath to profile, metadataPath to metadataHeader.copyOf(size))))
    }
  }

  @Test
  fun prepareRequired_acceptsExactSizeLimitsWithBoundedOutput() {
    val maxProfile = payload(profileHeader, profileLimit)
    val maxMetadata = payload(metadataHeader, metadataLimit)
    val apk = apk(zipBytes(profilePath to maxProfile, metadataPath to maxMetadata))

    assertOutput(SelfInstallDexMetadata.prepareRequired(apk), maxProfile, maxMetadata)
  }

  @Test
  fun prepareRequired_rejectsOversizedProfile() {
    val oversized = profileHeader.copyOf(profileLimit + 1)
    assertRejected(apk(zipBytes(profilePath to oversized, metadataPath to metadata)))
  }

  @Test
  fun prepareRequired_rejectsOversizedMetadata() {
    val oversized = metadataHeader.copyOf(metadataLimit + 1)
    assertRejected(apk(zipBytes(profilePath to profile, metadataPath to oversized)))
  }

  @Test
  fun prepareRequired_boundsProfileReadWhenDeclaredSizeIsTooSmall() {
    assertUnderstatedSizeRejected(profilePath, profileHeader.copyOf(profileLimit + 1))
  }

  @Test
  fun prepareRequired_boundsMetadataReadWhenDeclaredSizeIsTooSmall() {
    assertUnderstatedSizeRejected(metadataPath, metadataHeader.copyOf(metadataLimit + 1))
  }

  @Test
  fun prepareRequired_rejectsSizeMismatchWithinLimits() {
    val bytes = zipBytes(profilePath to profile, metadataPath to metadata)
    patchDeclaredSize(bytes, profilePath, profile.size - 1)
    assertRejected(apk(bytes))
  }

  @Test
  fun prepareRequired_rejectsCorruptPayloadWithIntactHeader() {
    val bytes = zipBytes(profilePath to profile, metadataPath to metadata, stored = true)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val payloadOffset = 30 + buffer.getShort(26).toInt() + buffer.getShort(28).toInt()
    bytes[payloadOffset + 8] = (bytes[payloadOffset + 8].toInt() xor 1).toByte()
    assertRejected(apk(bytes))
  }

  @Test
  fun prepareRequired_rejectsNonZipWithoutLeakingContentOrPath() {
    assertRejected(apk("private profile content".toByteArray()))
  }

  @Test
  fun prepareRequired_rejectsTruncatedZip() {
    val bytes = zipBytes(profilePath to profile, metadataPath to metadata)
    assertRejected(apk(bytes.copyOf(bytes.size - 10)))
  }

  @Test
  fun prepareRequired_rejectsMissingFileWithoutLeakingPath() {
    assertRejected(File(temporaryFolder.root, "private-missing-apk"))
  }

  private fun apk(bytes: ByteArray): File =
      temporaryFolder.newFile().apply { writeBytes(bytes) }

  private fun assertRejected(apk: File) {
    val error = assertThrows(IllegalArgumentException::class.java) {
      SelfInstallDexMetadata.prepareRequired(apk)
    }
    assertTrue(error.message.orEmpty().isNotBlank())
    assertTrue(error.message.orEmpty().length <= 80)
    assertFalse(error.message.orEmpty().contains(apk.path))
    assertFalse(error.message.orEmpty().contains("private"))
    assertFalse(error.message.orEmpty().contains("assets/"))
    assertNull(error.cause)
    assertEquals(0, error.suppressed.size)
  }

  private fun assertOutput(bytes: ByteArray, expectedProfile: ByteArray, expectedMetadata: ByteArray) {
    assertTrue(bytes.size <= profileLimit + metadataLimit + 1024)
    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        assertFalse(entry.isDirectory)
        entries.add(entry.name to zip.readBytes())
        zip.closeEntry()
      }
    }
    assertEquals(listOf("primary.prof", "primary.profm"), entries.map { it.first }.sorted())
    assertArrayEquals(expectedProfile, entries.single { it.first == "primary.prof" }.second)
    assertArrayEquals(expectedMetadata, entries.single { it.first == "primary.profm" }.second)
  }

  private fun payload(header: ByteArray, size: Int): ByteArray =
      ByteArray(size).also { bytes ->
        Random(1234).nextBytes(bytes)
        header.copyInto(bytes)
      }

  private fun zipBytes(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
      for ((name, bytes) in entries) {
        val entry = ZipEntry(name)
        if (stored) {
          entry.method = ZipEntry.STORED
          entry.size = bytes.size.toLong()
          entry.compressedSize = bytes.size.toLong()
          entry.crc = CRC32().apply { update(bytes) }.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return output.toByteArray()
  }

  private fun assertDuplicateRejected(name: String, alias: String, payload: ByteArray) {
    val bytes = zipBytes(profilePath to profile, metadataPath to metadata, alias to payload)
    val aliasBytes = alias.toByteArray(Charsets.US_ASCII)
    val nameBytes = name.toByteArray(Charsets.US_ASCII)
    assertEquals(aliasBytes.size, nameBytes.size)
    var replacements = 0
    for (offset in 0..bytes.size - aliasBytes.size) {
      if (aliasBytes.indices.all { bytes[offset + it] == aliasBytes[it] }) {
        nameBytes.copyInto(bytes, offset)
        replacements++
      }
    }
    assertEquals(2, replacements)
    val apk = apk(bytes)
    ZipFile(apk).use { zip ->
      assertEquals(2, zip.entries().asSequence().count { it.name == name })
    }
    assertRejected(apk)
  }

  private fun assertUnderstatedSizeRejected(name: String, oversized: ByteArray) {
    val entries = if (name == profilePath) {
      arrayOf(profilePath to oversized, metadataPath to metadata)
    } else {
      arrayOf(profilePath to profile, metadataPath to oversized)
    }
    val bytes = zipBytes(*entries)
    patchDeclaredSize(bytes, name, 8)
    val apk = apk(bytes)
    ZipFile(apk).use { zip ->
      val entry = zip.getEntry(name)
      assertEquals(8L, entry.size)
      assertEquals(oversized.size, zip.getInputStream(entry).use { it.readBytes().size })
    }
    assertRejected(apk)
  }

  private fun patchDeclaredSize(bytes: ByteArray, name: String, declaredSize: Int) {
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val endOffset = bytes.size - 22
    assertEquals(0x06054b50, buffer.getInt(endOffset))
    var offset = buffer.getInt(endOffset + 16)
    while (offset < endOffset) {
      assertEquals(0x02014b50, buffer.getInt(offset))
      val nameLength = buffer.getShort(offset + 28).toInt() and 0xffff
      val extraLength = buffer.getShort(offset + 30).toInt() and 0xffff
      val commentLength = buffer.getShort(offset + 32).toInt() and 0xffff
      val entryName = String(bytes, offset + 46, nameLength, Charsets.US_ASCII)
      if (entryName == name) {
        buffer.putInt(offset + 24, declaredSize)
        return
      }
      offset += 46 + nameLength + extraLength + commentLength
    }
    throw AssertionError("Fixture entry not found")
  }
}
