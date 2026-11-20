/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone photo routes against hostile paths, names, headers and bodies. Only the paths that reject
 * before Android's BitmapFactory runs are exercised (the stub throws on the JVM).
 */
class RemoteCompanionPhotosCoverageTest {
  private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

  private fun <T> withRoot(block: (File) -> T): T {
    val root = Files.createTempDirectory("remote-photos-cov").toFile()
    try { return block(root) } finally { root.deleteRecursively() }
  }
  private fun request(method: String, path: String, query: String, body: ByteArray = byteArrayOf(),
      headers: Map<String, String> = emptyMap(), contentLength: Int = body.size) =
      FleetHttpServer.Request(method, path, query, headers, ByteArrayInputStream(body), contentLength)
  private fun error(response: FleetHttpServer.Response): String =
      JSONObject(response.jsonBytes!!.toString(Charsets.UTF_8)).getString("error")

  @Test fun validNameBoundsLengthCharactersAndExtension() {
    assertTrue(RemoteCompanionPhotoPolicy.validName("a.JPG"))
    assertTrue(RemoteCompanionPhotoPolicy.validName("x".repeat(124) + ".gif"))
    for (name in listOf("", "x".repeat(125) + ".gif", "noext", "a.jpg:stream", "a\\b.jpg", "a.\u0001jpg", "a.svg", "a.heic", ".jpg"))
      assertFalse(name, RemoteCompanionPhotoPolicy.validName(name))
  }

  @Test fun magicMatchesEachSupportedFormatAndRejectsTruncatedOrMismatched() {
    assertTrue(RemoteCompanionPhotoPolicy.magicMatches("a.jpeg", jpeg))
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("a.jpg", jpeg.copyOf(2)))
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("a.jpg", byteArrayOf()))
    assertTrue(RemoteCompanionPhotoPolicy.magicMatches("a.gif", "GIF89a....".toByteArray()))
    assertTrue(RemoteCompanionPhotoPolicy.magicMatches("a.GIF", "GIF87a".toByteArray()))
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("a.gif", "GIF88a".toByteArray()))
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("a.gif", "GIF8".toByteArray()))
    assertTrue(RemoteCompanionPhotoPolicy.magicMatches("a.webp", "RIFF\u0000\u0000\u0000\u0000WEBP".toByteArray(Charsets.US_ASCII)))
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("a.webp", "RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray(Charsets.US_ASCII)))
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("a.webp", "RIFF".toByteArray()))
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("a.png", byteArrayOf(0x89.toByte(), 80, 78, 71)))
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("a.png", jpeg + jpeg))
    // A JPEG body under a PNG name (or an unknown extension) is refused.
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("a.png", jpeg))
    assertFalse(RemoteCompanionPhotoPolicy.magicMatches("a.bmp", jpeg))
  }

  @Test fun resolveRejectsOverlongForeignAndControlCharacterPaths() = withRoot { root ->
    assertEquals(root.canonicalFile, RemoteCompanionPhotoPolicy.resolve(root, "/sdcard", true))
    assertNull(RemoteCompanionPhotoPolicy.resolve(root, "/sdcard", false))
    assertNull(RemoteCompanionPhotoPolicy.resolve(root, "/sdcardx/a.jpg", false))
    assertNull(RemoteCompanionPhotoPolicy.resolve(root, "/sdcard/" + "a".repeat(1020), true))
    for (bad in listOf("/sdcard/a\u0000b", "/sdcard/a:b", "/sdcard/a\\b", "/sdcard/ ", "/sdcard/Pictures/"))
      assertNull(bad, RemoteCompanionPhotoPolicy.resolve(root, bad, true))
    assertNull(RemoteCompanionPhotoPolicy.resolve(root, "/sdcard/Pictures/a.exe", false))
    assertEquals(File(root.canonicalFile, "Pictures/a.png"), RemoteCompanionPhotoPolicy.resolve(root, "/sdcard/Pictures/a.png", false))
  }

  @Test fun storeRejectsInvalidNamesAndSizesBeforeTouchingDisk() = withRoot { folder ->
    var writes = 0
    for ((name, expected) in listOf("a.exe" to 4, "a.jpg" to 0, "a.jpg" to RemoteCompanionPhotoPolicy.MAX_BYTES + 1))
      assertEquals("invalid_upload", RemoteCompanionPhotoPolicy.store(folder, name, expected, { writes++; 0L }, { true }))
    assertEquals(0, writes)
    assertTrue(folder.list()!!.isEmpty())
  }

  @Test fun storeDetectsShortWriteEvenWhenWriterOverReports() = withRoot { folder ->
    assertEquals("incomplete_upload", RemoteCompanionPhotoPolicy.store(folder, "a.jpg", 4, { it.write(jpeg.copyOf(2)); 4L }, { true }))
    assertTrue(folder.list()!!.isEmpty())
  }

  @Test fun storeReportsFolderUnavailableWhenRecheckFails() = withRoot { folder ->
    assertEquals("folder_unavailable", RemoteCompanionPhotoPolicy.store(folder, "a.jpg", 4, { it.write(jpeg); 4L }, { true }, canPublish = { false }))
    assertTrue(folder.list()!!.isEmpty())
  }

  @Test fun storeReportsCollisionWhenAnotherUploadWinsTheRace() = withRoot { folder ->
    val result = RemoteCompanionPhotoPolicy.store(folder, "a.jpg", 4, { it.write(jpeg); 4L },
        verify = { File(folder, "a.jpg").writeText("winner"); true })
    assertEquals("collision", result)
    assertEquals("winner", File(folder, "a.jpg").readText())
    assertEquals(listOf("a.jpg"), folder.list()!!.toList())
  }

  @Test fun storeMapsWriterFailureToUploadFailedAndCleansTemp() = withRoot { folder ->
    assertEquals("upload_failed", RemoteCompanionPhotoPolicy.store(folder, "a.jpg", 4, { throw IOException("peer reset") }, { true }))
    assertTrue(folder.list()!!.isEmpty())
    val missing = File(folder, "missing")
    assertEquals("upload_failed", RemoteCompanionPhotoPolicy.store(missing, "a.jpg", 4, { it.write(jpeg); 4L }, { true }))
  }

  @Test fun photoRouteRejectsMethodMissingAndInvalidPaths() = withRoot { root ->
    val post = RemoteCompanionPhotos.handle(request("POST", "/remote/photo", "path=%2Fsdcard%2Fa.jpg"), root)
    assertEquals(405, post.status)
    assertEquals("path_required", error(RemoteCompanionPhotos.handle(request("GET", "/remote/photo", ""), root)))
    val traversal = RemoteCompanionPhotos.handle(request("GET", "/remote/photo", "path=%2Fsdcard%2F..%2Fsecret.jpg"), root)
    assertEquals(400, traversal.status)
    assertEquals("invalid_path", error(traversal))
    val missing = RemoteCompanionPhotos.handle(request("DELETE", "/remote/photo", "path=%2Fsdcard%2Fnone.jpg"), root)
    assertEquals(404, missing.status)
    File(root, "empty.jpg").createNewFile()
    assertEquals("photo_not_found", error(RemoteCompanionPhotos.handle(request("GET", "/remote/photo", "path=%2Fsdcard%2Fempty.jpg"), root)))
  }

  @Test fun disguisedFileIsNeitherServedNorDeleted() = withRoot { root ->
    val fake = File(root, "fake.jpg").apply { writeText("#!/bin/sh\necho pwned") }
    for (method in listOf("GET", "DELETE")) {
      val response = RemoteCompanionPhotos.handle(request(method, "/remote/photo", "path=%2Fsdcard%2Ffake.jpg"), root)
      assertEquals(method, 415, response.status)
      assertEquals("invalid_image", error(response))
    }
    assertTrue(fake.exists())
  }

  @Test fun thumbnailRouteRejectsMissingInvalidAndDisguisedFiles() = withRoot { root ->
    fun thumb(q: String) = RemoteCompanionPhotos.handle(request("GET", "/remote/photo", "thumbnail=1&$q"), root)
    assertEquals("path_required", error(thumb("x=1")))
    assertEquals("invalid_path", error(thumb("path=%2Fsdcard%2Fa.txt")))
    assertEquals(404, thumb("path=%2Fsdcard%2Fnone.png").status)
    File(root, "fake.png").writeText("not a png at all")
    val disguised = thumb("path=%2Fsdcard%2Ffake.png")
    assertEquals(415, disguised.status)
    assertEquals("invalid_image", error(disguised))
  }

  @Test fun uploadRejectsBeforeReadingTheBody() = withRoot { root ->
    fun upload(query: String, method: String = "POST", length: Int = 4, headers: Map<String, String> = emptyMap()) =
        RemoteCompanionPhotos.handle(request(method, "/remote/photo-upload", query, jpeg, headers, length), root)
    assertEquals(405, upload("path=%2Fsdcard&name=a.jpg", method = "PUT").status)
    assertEquals("path_required", error(upload("name=a.jpg")))
    assertEquals("invalid_path", error(upload("path=%2Fsdcard%2FAndroid&name=a.jpg")))
    assertEquals("name_required", error(upload("path=%2Fsdcard")))
    assertEquals("invalid_name", error(upload("path=%2Fsdcard&name=..%2Fa.jpg")))
    assertEquals("invalid_name", error(upload("path=%2Fsdcard&name=a.apk")))
    for (length in listOf(0, -1, RemoteCompanionPhotoPolicy.MAX_BYTES + 1)) {
      val tooBig = upload("path=%2Fsdcard&name=a.jpg", length = length)
      assertEquals("length=$length", 413, tooBig.status)
    }
    val wrongType = upload("path=%2Fsdcard&name=a.jpg", headers = mapOf("content-type" to "text/html; charset=utf-8"))
    assertEquals(415, wrongType.status)
    assertEquals("unsupported_media_type", error(wrongType))
    assertTrue(root.list()!!.isEmpty())
  }

  @Test fun uploadCreatesMissingFolderButRefusesNonImageBodies() = withRoot { root ->
    val response = RemoteCompanionPhotos.handle(request("POST", "/remote/photo-upload", "path=%2Fsdcard%2FPhone&name=a.jpg",
        "not-jpeg".toByteArray(), mapOf("content-type" to "IMAGE/JPEG")), root)
    assertEquals(400, response.status)
    assertEquals("invalid_image", error(response))
    val folder = File(root, "Phone")
    assertTrue(folder.isDirectory)
    assertTrue(folder.list()!!.isEmpty())
  }

  @Test fun uploadCollisionIsReportedAsConflict() = withRoot { root ->
    File(root, "a.jpg").writeBytes(jpeg)
    val response = RemoteCompanionPhotos.handle(request("POST", "/remote/photo-upload", "path=%2Fsdcard&name=a.jpg",
        jpeg, mapOf("content-type" to "application/octet-stream")), root)
    assertEquals(409, response.status)
    assertEquals("collision", error(response))
  }

  @Test fun uploadIntoAFileNamedLikeTheFolderIsRefused() = withRoot { root ->
    File(root, "Phone").writeText("a file, not a folder")
    val response = RemoteCompanionPhotos.handle(request("POST", "/remote/photo-upload", "path=%2Fsdcard%2FPhone&name=a.jpg", jpeg), root)
    assertEquals(403, response.status)
    assertEquals("folder_unavailable", error(response))
  }

  @Test fun allowedFolderAppliesTheSamePathPolicyToTheWireRoot() {
    assertTrue(RemoteCompanionPhotos.allowedFolder("/sdcard/Pictures"))
    for (path in listOf("/sdcard/../data", "/sdcard/Android/data", "/data/local/tmp", "/sdcard/.hidden", "sdcard/Pictures"))
      assertFalse(path, RemoteCompanionPhotos.allowedFolder(path))
  }

  @Test fun photoStreamRejectsEmptyFiles() = withRoot { root ->
    val empty = File(root, "e.jpg").apply { createNewFile() }
    assertTrue(runCatching { RemoteCompanionPhotoStream(empty) }.isFailure)
    val one = File(root, "o.jpg").apply { writeBytes(jpeg) }
    RemoteCompanionPhotoStream(one).use { assertNotNull(it.image { input -> input.read() }) }
  }
}
