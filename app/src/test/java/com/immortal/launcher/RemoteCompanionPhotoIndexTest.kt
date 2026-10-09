/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteCompanionPhotoIndexTest {
  private fun withRoot(test: (File) -> Unit) {
    val root = Files.createTempDirectory("remote-photo-index").toFile()
    try { test(root) } finally {
      Files.walk(root.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
  }
  private fun photo(root: File, path: String): File = File(root, path).apply {
    parentFile!!.mkdirs()
    writeText("not an image")
    assertTrue(setLastModified(1000L))
  }
  private fun request(query: String = "path=%2Fsdcard&recursive=1", method: String = "GET") =
      FleetHttpServer.Request(method, "/remote/photos", query, emptyMap(), ByteArrayInputStream(byteArrayOf()), 0)
  private fun body(response: FleetHttpServer.Response) = JSONObject(response.jsonBytes!!.toString(Charsets.UTF_8))
  private fun listing(index: RemoteCompanionPhotoIndex, query: String = "path=%2Fsdcard&recursive=1"): JSONObject {
    val response = index.handle(request(query))
    assertEquals(200, response.status)
    return body(response)
  }
  private fun paths(files: JSONArray) = (0 until files.length()).map { files.getJSONObject(it).getString("path") }
  private fun index(root: File, limits: RemoteCompanionPhotoIndex.Limits = RemoteCompanionPhotoIndex.Limits()) =
      RemoteCompanionPhotoIndex(root, limits, nowNanos = { 0L })

  @Test fun recursiveDiscoveryCollectsRootAndLegacyFoldersWithoutDecoding() = withRoot { root ->
    photo(root, "root.JPG")
    photo(root, "Pictures/Phone/one.png")
    photo(root, "DCIM/Camera/two.webp")
    photo(root, "Download/old.gif")
    File(root, "empty").mkdir()
    val result = listing(index(root))
    assertEquals("/sdcard", result.getString("path"))
    assertTrue(result.getBoolean("recursive"))
    assertFalse(result.getBoolean("truncated"))
    val files = result.getJSONArray("files")
    assertEquals(listOf("/sdcard/DCIM/Camera/two.webp", "/sdcard/Download/old.gif", "/sdcard/Pictures/Phone/one.png", "/sdcard/root.JPG"), paths(files))
    for (i in 0 until files.length()) {
      val file = files.getJSONObject(i)
      assertEquals(12L, file.getLong("size"))
      assertEquals(1000L, file.getLong("modified"))
      assertEquals(file.getString("path").substringAfterLast('/'), file.getString("name"))
    }
    val folders = result.getJSONArray("folders")
    assertEquals(listOf("/sdcard", "/sdcard/DCIM/Camera", "/sdcard/Download", "/sdcard/Pictures/Phone"), paths(folders))
    for (i in 0 until folders.length()) assertEquals(1, folders.getJSONObject(i).getInt("count"))
  }

  @Test fun requestedSubfolderKeepsItsWireRootAndDoesNotCollectSiblings() = withRoot { root ->
    photo(root, "Pictures/Phone/a.jpg")
    photo(root, "DCIM/b.jpg")
    val result = listing(index(root), "path=%2Fsdcard%2FPictures&recursive=1")
    assertEquals("/sdcard/Pictures", result.getString("path"))
    assertEquals(listOf("/sdcard/Pictures/Phone/a.jpg"), paths(result.getJSONArray("files")))
  }

  @Test fun exclusionsCoverHiddenAndroidUnsafeNamesAndSymlinks() = withRoot { root ->
    photo(root, "safe/a.jpg")
    for (path in listOf(".hidden/a.jpg", "Android/data/a.jpg", "safe/aNdRoId/a.jpg", "safe/.hidden.jpg", "bad:folder/a.jpg", "bad\\folder/a.jpg", "bad\nfolder/a.jpg", "safe/a:b.jpg", "safe/readme.txt", "safe/" + "x".repeat(129) + ".jpg")) photo(root, path)
    Files.createSymbolicLink(File(root, "alias").toPath(), File(root, "safe").toPath())
    Files.createSymbolicLink(File(root, "safe/link.jpg").toPath(), File(root, "safe/a.jpg").toPath())
    Files.createSymbolicLink(File(root, "outside").toPath(), root.parentFile!!.toPath())
    val result = listing(index(root))
    assertEquals(listOf("/sdcard/safe/a.jpg"), paths(result.getJSONArray("files")))
    assertFalse(result.getBoolean("truncated"))
  }

  @Test fun duplicateNamesRemainDistinctAndFolderCountsAreDirect() = withRoot { root ->
    photo(root, "a/same.jpg")
    photo(root, "a/other.png")
    photo(root, "a/nested/same.jpg")
    photo(root, "b/same.jpg")
    val result = listing(index(root))
    assertEquals(listOf("/sdcard/a/nested/same.jpg", "/sdcard/a/other.png", "/sdcard/a/same.jpg", "/sdcard/b/same.jpg"), paths(result.getJSONArray("files")))
    val folders = result.getJSONArray("folders")
    assertEquals(listOf("/sdcard/a", "/sdcard/a/nested", "/sdcard/b"), paths(folders))
    assertEquals(listOf(2, 1, 1), (0 until folders.length()).map { folders.getJSONObject(it).getInt("count") })
  }

  @Test fun orderIsDeterministicAcrossCreationOrders() = withRoot { root ->
    val names = listOf("z/b.jpg", "B/a.jpg", "a/z.jpg", "a/A.jpg", "Z.jpg")
    for (name in names) photo(root, name)
    val first = paths(listing(index(root)).getJSONArray("files"))
    for (name in names) File(root, name).delete()
    for (name in names.reversed()) photo(root, name)
    assertEquals(listOf("/sdcard/B/a.jpg", "/sdcard/Z.jpg", "/sdcard/a/A.jpg", "/sdcard/a/z.jpg", "/sdcard/z/b.jpg"), first)
    assertEquals(first, paths(listing(index(root)).getJSONArray("files")))
  }

  @Test fun legacyListingPreservesShapeAndOnlyCollectsDirectPhotos() = withRoot { root ->
    photo(root, "Pictures/direct.jpg")
    photo(root, "Pictures/nested/deep.jpg")
    val result = listing(index(root), "path=%2Fsdcard%2FPictures")
    assertEquals(listOf("/sdcard/Pictures/direct.jpg"), paths(result.getJSONArray("files")))
    assertEquals(setOf("ok", "path", "files", "freeBytes", "truncated"), result.keys().asSequence().toSet())
    assertFalse(result.getBoolean("truncated"))
  }

  @Test fun legacyListingStillHasIts500PhotoLimit() = withRoot { root ->
    repeat(501) { photo(root, "$it.jpg") }
    val result = listing(index(root), "path=%2Fsdcard")
    assertEquals(500, result.getJSONArray("files").length())
    assertTrue(result.getBoolean("truncated"))
  }

  @Test fun photoLimitReturnsDeterministicSubsetAndTruncation() = withRoot { root ->
    for (name in listOf("c.jpg", "a.jpg", "b.jpg")) photo(root, name)
    val result = listing(index(root, RemoteCompanionPhotoIndex.Limits(maxPhotos = 2)))
    assertEquals(listOf("/sdcard/a.jpg", "/sdcard/b.jpg"), paths(result.getJSONArray("files")))
    assertTrue(result.getBoolean("truncated"))
    assertEquals(2, result.getJSONArray("folders").getJSONObject(0).getInt("count"))
  }

  @Test fun exhaustiveListingAtPhotoLimitIsNotTruncated() = withRoot { root ->
    photo(root, "a.jpg"); photo(root, "b.jpg")
    assertFalse(listing(index(root, RemoteCompanionPhotoIndex.Limits(maxPhotos = 2))).getBoolean("truncated"))
  }

  @Test fun directoryLimitIncludesRequestedRootAndReportsOmittedDescendants() = withRoot { root ->
    photo(root, "a/a.jpg"); photo(root, "b/b.jpg")
    val result = listing(index(root, RemoteCompanionPhotoIndex.Limits(maxDirectories = 2)))
    assertEquals(listOf("/sdcard/a/a.jpg"), paths(result.getJSONArray("files")))
    assertTrue(result.getBoolean("truncated"))
  }

  @Test fun exhaustiveListingAtDirectoryLimitIsNotTruncated() = withRoot { root ->
    photo(root, "a/a.jpg")
    assertFalse(listing(index(root, RemoteCompanionPhotoIndex.Limits(maxDirectories = 2))).getBoolean("truncated"))
  }

  @Test fun depthLimitCollectsAllowedLevelAndReportsDeeperDirectories() = withRoot { root ->
    photo(root, "a/a.jpg"); photo(root, "a/b/b.jpg")
    val result = listing(index(root, RemoteCompanionPhotoIndex.Limits(maxDepth = 1)))
    assertEquals(listOf("/sdcard/a/a.jpg"), paths(result.getJSONArray("files")))
    assertTrue(result.getBoolean("truncated"))
  }

  @Test fun exhaustiveListingAtDepthLimitIsNotTruncated() = withRoot { root ->
    photo(root, "a/a.jpg")
    assertFalse(listing(index(root, RemoteCompanionPhotoIndex.Limits(maxDepth = 1))).getBoolean("truncated"))
  }

  private class ObservedStream(private val delegate: DirectoryStream<Path>, private val onNext: () -> Unit) : DirectoryStream<Path> {
    var closed = false
    override fun close() { closed = true; delegate.close() }
    override fun iterator(): MutableIterator<Path> {
      val iterator = delegate.iterator()
      return object : MutableIterator<Path> {
        override fun hasNext() = iterator.hasNext()
        override fun next(): Path = iterator.next().also { onNext() }
        override fun remove() = throw UnsupportedOperationException()
      }
    }
  }

  @Test fun examinedEntryLimitCountsNonPhotosAndClosesDirectoryStream() = withRoot { root ->
    repeat(10) { File(root, "$it.txt").writeText("text") }
    var examined = 0
    lateinit var stream: ObservedStream
    val index = RemoteCompanionPhotoIndex(root, RemoteCompanionPhotoIndex.Limits(maxEntries = 2), nowNanos = { 0L }, openDirectory = {
      ObservedStream(Files.newDirectoryStream(it.toPath())) { examined++ }.also { stream = it }
    })
    val result = listing(index)
    assertEquals(2, examined)
    assertEquals(0, result.getJSONArray("files").length())
    assertTrue(result.getBoolean("truncated"))
    assertTrue(stream.closed)
  }

  @Test fun exhaustiveListingAtEntryLimitIsNotTruncated() = withRoot { root ->
    photo(root, "a.jpg"); photo(root, "b.jpg")
    assertFalse(listing(index(root, RemoteCompanionPhotoIndex.Limits(maxEntries = 2))).getBoolean("truncated"))
  }

  @Test fun injectedDeadlineStopsReadingAndClosesDirectoryStream() = withRoot { root ->
    photo(root, "a.jpg"); photo(root, "b.jpg")
    var nanos = 0L
    var examined = 0
    lateinit var stream: ObservedStream
    val index = RemoteCompanionPhotoIndex(root, RemoteCompanionPhotoIndex.Limits(maxMillis = 1), nowNanos = { nanos }, openDirectory = {
      ObservedStream(Files.newDirectoryStream(it.toPath())) { examined++; nanos = 1_000_000L }.also { stream = it }
    })
    assertTrue(listing(index).getBoolean("truncated"))
    assertEquals(1, examined)
    assertTrue(stream.closed)
  }

  @Test fun unreadableDescendantProducesPartialListingInsteadOfClaimingExhaustive() = withRoot { root ->
    photo(root, "a.jpg"); photo(root, "private/b.jpg")
    val index = RemoteCompanionPhotoIndex(root, nowNanos = { 0L }, openDirectory = {
      if (it.name == "private") throw AccessDeniedException(it.path)
      Files.newDirectoryStream(it.toPath())
    })
    val result = listing(index)
    assertEquals(listOf("/sdcard/a.jpg"), paths(result.getJSONArray("files")))
    assertTrue(result.getBoolean("truncated"))
  }

  @Test fun unreadableRequestedFolderRetains403Error() = withRoot { root ->
    val index = RemoteCompanionPhotoIndex(root, openDirectory = { throw AccessDeniedException(it.path) })
    val response = index.handle(request())
    assertEquals(403, response.status)
    assertEquals("not_readable", body(response).getString("error"))
  }

  @Test fun newRequestSeesAddedRemovedAndModifiedPhotosWithoutCache() = withRoot { root ->
    photo(root, "a.jpg")
    val index = index(root)
    assertEquals(listOf("/sdcard/a.jpg"), paths(listing(index).getJSONArray("files")))
    File(root, "a.jpg").delete()
    val replacement = photo(root, "new/b.jpg").apply { writeText("changed"); assertTrue(setLastModified(2000L)) }
    val result = listing(index)
    assertEquals(listOf("/sdcard/new/b.jpg"), paths(result.getJSONArray("files")))
    assertEquals(7L, result.getJSONArray("files").getJSONObject(0).getLong("size"))
    assertEquals(2000L, result.getJSONArray("files").getJSONObject(0).getLong("modified"))
    assertTrue(replacement.exists())
  }

  @Test fun invalidMethodsPathsMissingFoldersAndFileRootsRetainErrors() = withRoot { root ->
    val index = index(root)
    for (method in listOf("POST", "DELETE", "PATCH", "HEAD")) assertEquals(method, 405, index.handle(request(method = method)).status)
    assertEquals(400, index.handle(request("recursive=1")).status)
    for (path in listOf("/data", "/sdcard/../x", "/sdcard//x", "/sdcard/.hidden", "/sdcard/Pictures/Android", "/sdcard/" + "x".repeat(1024))) {
      assertEquals(path, 400, index.handle(request("path=$path&recursive=1")).status)
    }
    assertEquals(404, index.handle(request("path=%2Fsdcard%2Fmissing&recursive=1")).status)
    photo(root, "a.jpg")
    assertEquals(404, index.handle(request("path=%2Fsdcard%2Fa.jpg&recursive=1")).status)
    Files.createSymbolicLink(File(root, "alias").toPath(), root.toPath())
    assertEquals(400, index.handle(request("path=%2Fsdcard%2Falias&recursive=1")).status)
  }

  @Test fun discoveryDoesNotWaitForUploadMutationLock() = withRoot { root ->
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val workers = Executors.newFixedThreadPool(2)
    try {
      val input = object : java.io.InputStream() {
        override fun read(): Int {
          entered.countDown()
          check(release.await(5, TimeUnit.SECONDS))
          return 1
        }
      }
      val upload = workers.submit<FleetHttpServer.Response> {
        RemoteCompanionPhotos.handle(FleetHttpServer.Request("POST", "/remote/photo-upload", "path=%2Fsdcard&name=new.jpg", emptyMap(), input, 1), root)
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      val listing = workers.submit<FleetHttpServer.Response> { RemoteCompanionPhotos.handle(request(), root) }
      assertEquals(200, listing.get(2, TimeUnit.SECONDS).status)
      release.countDown()
      assertEquals(400, upload.get(5, TimeUnit.SECONDS).status)
    } finally { release.countDown(); workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS) }
  }
}
