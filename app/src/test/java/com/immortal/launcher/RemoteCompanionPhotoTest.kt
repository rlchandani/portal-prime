/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.io.ByteArrayInputStream
import org.json.JSONObject
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RemoteCompanionPhotoTest {
  @Test fun pathsRejectTraversalAndroidPrivateAndSymlinks() {
    val root = Files.createTempDirectory("remote-photos").toFile()
    val elsewhere = Files.createTempDirectory("remote-private").toFile()
    try {
      File(root, "Pictures").mkdir()
      assertNotNull(RemoteCompanionPhotoPolicy.resolve(root, "/sdcard/Pictures", true))
      for (path in listOf("/sdcard/../secret", "/data/app", "/sdcard/Android/data", "/sdcard//Pictures", "/sdcard/.hidden", "/sdcard/Pictures/./x")) assertNull(path, RemoteCompanionPhotoPolicy.resolve(root, path, true))
      Files.createSymbolicLink(File(root, "Pictures/link").toPath(), elsewhere.toPath())
      assertNull(RemoteCompanionPhotoPolicy.resolve(root, "/sdcard/Pictures/link/photo.jpg", false))
    } finally { File(root, "Pictures/link").delete(); root.deleteRecursively(); elsewhere.deleteRecursively() }
  }
  @Test fun uploadsRejectNonImagesUnsafeNamesAndFalseImageMagic() {
    for (name in listOf("app.apk", "photo.jpg.apk", "../a.jpg", "a/b.jpg", ".hidden.jpg", "a\n.jpg")) assertFalse(name, RemoteCompanionPhotoPolicy.validName(name))
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("photo.jpg", "not jpeg".toByteArray()))
    assertTrue(RemoteCompanionPhotoPolicy.magicMatches("photo.png", byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10)))
  }
  @Test fun failedUploadPreservesExistingDestinationAndLeavesNoTemp() {
    val folder = Files.createTempDirectory("remote-upload").toFile()
    try {
      val destination = File(folder, "a.jpg").apply { writeText("original") }
      assertEquals("collision", RemoteCompanionPhotoPolicy.store(folder, "a.jpg", 4, { it.write(byteArrayOf(1,2,3,4)); 4 }, { true }))
      assertEquals("original", destination.readText())
      assertEquals("incomplete_upload", RemoteCompanionPhotoPolicy.store(folder, "b.jpg", 4, { it.write(byteArrayOf(1)); 1 }, { true }))
      assertFalse(File(folder, "b.jpg").exists())
      assertEquals(listOf("a.jpg"), folder.list()!!.toList())
      assertEquals("invalid_image", RemoteCompanionPhotoPolicy.store(folder, "c.jpg", 4, { it.write(byteArrayOf(1,2,3,4)); 4 }, { false }))
      assertFalse(File(folder, "c.jpg").exists())
    } finally { folder.deleteRecursively() }
  }
  @Test fun atomicUploadOnlyPublishesVerifiedCompleteBytes() {
    val folder = Files.createTempDirectory("remote-complete").toFile()
    try {
      assertEquals("stored", RemoteCompanionPhotoPolicy.store(folder, "a.jpg", 4, { it.write(byteArrayOf(1,2,3,4)); 4 }, { it.length() == 4L }))
      assertArrayEquals(byteArrayOf(1,2,3,4), File(folder, "a.jpg").readBytes())
      assertEquals(listOf("a.jpg"), folder.list()!!.toList())
    } finally { folder.deleteRecursively() }
  }

  @Test fun streamedPhotoKeepsOpenedBytesAndLengthAfterDeleteAndReupload() {
    val folder = Files.createTempDirectory("remote-descriptor").toFile()
    try {
      val file = File(folder, "a.jpg").apply { writeText("old") }
      val response = RemoteCompanionPhotoStream(file).response("image/jpeg")
      assertTrue(file.delete())
      file.writeText("new longer image")
      val output = java.io.ByteArrayOutputStream()
      response.use { it.streamWriter!!(output) }
      assertEquals(3L, response.streamLength)
      assertEquals("old", output.toString("UTF-8"))
    } finally { folder.deleteRecursively() }
  }
  @Test fun abandonedOrFailedResponseClosesItsDescriptor() {
    val folder = Files.createTempDirectory("remote-cleanup").toFile()
    try {
      val file = File(folder, "a.jpg").apply { writeText("old") }
      val abandoned = RemoteCompanionPhotoStream(file)
      abandoned.response("image/jpeg").close()
      assertFalse(abandoned.isOpen)
      val failed = RemoteCompanionPhotoStream(file)
      runCatching { failed.response("image/jpeg").use { response -> response.streamWriter!!(object : java.io.OutputStream() {
        override fun write(b: Int) { throw java.io.IOException("client disconnected") }
      }) } }
      assertFalse(failed.isOpen)
    } finally { folder.deleteRecursively() }
  }

  @Test fun descriptorLengthIsCheckedEvenIfPathChangedAfterPreflight() {
    val folder = Files.createTempDirectory("remote-length").toFile()
    try {
      val file = File(folder, "a.jpg")
      java.io.RandomAccessFile(file, "rw").use { it.setLength(RemoteCompanionPhotoPolicy.MAX_BYTES.toLong() + 1) }
      val stream = runCatching { RemoteCompanionPhotoStream(file) }
      stream.getOrNull()?.close()
      assertTrue(stream.isFailure)
    } finally { folder.deleteRecursively() }
  }

  @Test fun pathsRejectAndroidAtAnyDepth() {
    val root = Files.createTempDirectory("remote-nested-private").toFile()
    try {
      for (path in listOf("/sdcard/Pictures/Android", "/sdcard/Pictures/aNdRoId/data/photo.jpg")) {
        assertNull(path, RemoteCompanionPhotoPolicy.resolve(root, path, true))
        assertNull(path, RemoteCompanionPhotoPolicy.resolve(root, path, false))
      }
    } finally { root.deleteRecursively() }
  }

  @Test fun listingRejectsAmbiguousOrUnsupportedRecursiveFlagsBeforeFilesystemAccess() {
    for (flag in listOf("recursive=0", "recursive=true", "recursive=", "recursive", "recursive=%ZZ", "recursive=1&recursive=1")) {
      val request = FleetHttpServer.Request("GET", "/remote/photos", "path=%2Fsdcard&$flag", emptyMap(), ByteArrayInputStream(byteArrayOf()), 0)
      val response = RemoteCompanionPhotos.handle(request)
      assertEquals(flag, 400, response.status)
      assertEquals(flag, "invalid_recursive", JSONObject(response.jsonBytes!!.toString(Charsets.UTF_8)).getString("error"))
    }
  }


  @Test fun thumbnailMetadataRewindsBeforeAndAfterReadingWithoutClosingStream() {
    val file = Files.createTempFile("thumbnail-metadata", ".jpg").toFile().apply { writeText("original") }
    try {
      java.io.FileInputStream(file).use { input ->
        input.channel.position(4)
        assertEquals(6, thumbnailOrientation(input) {
          assertEquals(0L, it.channel.position())
          assertEquals('o'.code, it.read())
          6
        })
        assertEquals(0L, input.channel.position())
        assertTrue(input.channel.isOpen)
      }
    } finally { file.delete() }
  }

  @Test fun malformedThumbnailMetadataFallsBackAndLeavesDecoderAtStart() {
    val file = Files.createTempFile("thumbnail-invalid-metadata", ".jpg").toFile().apply { writeText("original") }
    try {
      java.io.FileInputStream(file).use { input ->
        assertEquals(1, thumbnailOrientation(input) {
          it.read()
          throw java.io.IOException("malformed EXIF")
        })
        assertEquals(0L, input.channel.position())
        assertTrue(input.channel.isOpen)
      }
    } finally { file.delete() }
  }

  @Test fun thumbnailMetadataUsesCapturedInodeAfterDeleteAndReplacement() {
    val folder = Files.createTempDirectory("thumbnail-inode").toFile()
    try {
      val file = File(folder, "image.jpg").apply { writeText("old") }
      RemoteCompanionPhotoStream(file).use { stream ->
        assertTrue(file.delete())
        file.writeText("new replacement")
        stream.image { input ->
          input.channel.position(2)
          assertEquals(8, thumbnailOrientation(input) { assertEquals("old", it.readBytes().toString(Charsets.UTF_8)); 8 })
          assertEquals(0L, input.channel.position())
          assertTrue(stream.isOpen)
        }
      }
    } finally { folder.deleteRecursively() }
  }

  @Test fun unknownThumbnailOrientationUsesUntransformedFallback() {
    val file = Files.createTempFile("thumbnail-unknown-metadata", ".jpg").toFile().apply { writeText("original") }
    try {
      java.io.FileInputStream(file).use { input ->
        for (orientation in listOf(-1, 0, 9, 100)) assertEquals(1, thumbnailOrientation(input) { orientation })
        assertTrue(input.channel.isOpen)
      }
    } finally { file.delete() }
  }

}
