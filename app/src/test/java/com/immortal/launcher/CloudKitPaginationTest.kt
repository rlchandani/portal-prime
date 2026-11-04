/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLClassLoader
import java.net.URLStreamHandler
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Public album fetch and metadata reader; the child owns generated HTTPS only. */
class CloudKitPaginationTest {
  @Test fun generatedCloudKitPagesPreservePhotoIdentityAndProgress() {
    val paths = mutableSetOf<String>()
    paths.addAll(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
    var loader: ClassLoader? = javaClass.classLoader
    while (loader != null) {
      if (loader is URLClassLoader) paths.addAll(loader.urLs.map { java.io.File(it.toURI()).path })
      loader = loader.parent
    }
    val log = java.io.File.createTempFile("cloudkit-pages-fixture-", ".log")
    val process = ProcessBuilder(
        java.io.File(System.getProperty("java.home"), "bin/java").path,
        "-cp", paths.joinToString(java.io.File.pathSeparator),
        "com.immortal.launcher.CloudKitPaginationProcess")
        .redirectErrorStream(true).redirectOutput(log).start()
    try {
      assertTrue("Generated child exceeded its deadline", process.waitFor(30, TimeUnit.SECONDS))
      val output = log.readText()
      System.out.println(output)
      assertEquals(output, 0, process.exitValue())
      assertTrue(output, output.contains("CLOUDKIT_CASES tests=12 failures=0"))
    } finally {
      if (process.isAlive) {
        process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
      }
      log.delete()
    }
  }

  class GeneratedCases {
    private fun master(name: String?, photo: String, usable: Boolean = true): JSONObject {
      val fields = JSONObject().put("itemType", JSONObject().put("value", "public.jpeg"))
      if (usable) {
        fields.put("resOriginalRes",
            JSONObject().put("value", JSONObject().put("downloadURL", image(photo))))
        fields.put("resOriginalWidth", JSONObject().put("value", 1920))
      }
      return JSONObject().put("recordType", "CPLMaster").put("fields", fields)
          .apply { if (name != null) put("recordName", name) }
    }

    private fun page(marker: String?, vararg records: JSONObject): String =
        JSONObject().put("records", JSONArray(records.toList()))
            .apply { if (marker != null) put("continuationMarker", marker) }.toString()

    private fun fetch(pages: List<String?>): RemoteAlbum.Album {
      CloudKitPaginationProcess.pages = pages
      CloudKitPaginationProcess.requests.clear()
      CloudKitPaginationProcess.queryMarkers.clear()
      CloudKitPaginationProcess.disconnections = 0
      val album = RemoteAlbum.fetch("https://photos.icloud.com/shared/album/public-fixture")
      assertNotNull(album)
      assertEquals("Public fixture", album!!.title)
      assertTrue(album.headers.isEmpty())
      assertEquals(CloudKitPaginationProcess.requests.size, CloudKitPaginationProcess.disconnections)
      return album
    }

    private fun expect(markers: List<String?>, photos: List<String>, pages: List<String?>) {
      assertEquals(photos.map(::image), fetch(pages).photoUrls)
      assertEquals(markers, CloudKitPaginationProcess.queryMarkers)
    }

    fun repeatedMarkerStopsBeforeAnotherRequestAndKeepsUniqueMasters() {
      expect(listOf(null, "A"), listOf("a", "b"), listOf(
          page("A", master("a", "a")),
          page("A", master("a", "a"), master("b", "b"))))
    }

    fun markerCycleStopsBeforeRevisitingAndKeepsEarlierPhotos() {
      expect(listOf(null, "A", "B"), listOf("a", "b", "c"), listOf(
          page("A", master("a", "a")), page("B", master("b", "b")),
          page("A", master("a", "a"), master("c", "c"))))
    }

    fun overlappingForwardPagesKeepFirstUsableMaster() {
      expect(listOf(null, "A"), listOf("a", "b"), listOf(
          page("A", master("a", "a")),
          page(null, master("a", "changed"), master("b", "b"))))
    }

    fun repeatedMasterWithinPageKeepsFirstButDistinctMastersMayShareUrl() {
      expect(listOf(null), listOf("a", "a"), listOf(page(null,
          master("a", "a"), master("a", "changed"), master("b", "a"))))
    }

    fun unnamedMastersUseUrlIdentityAcrossPages() {
      expect(listOf(null, "A"), listOf("a", "b"), listOf(
          page("A", master(null, "a"), master("", "a")),
          page(null, master(null, "a"), master(null, "b"))))
    }

    fun unusableFirstMasterDoesNotHideItsLaterValidAsset() {
      expect(listOf(null, "A"), listOf("a"), listOf(
          page("A", master("a", "missing", usable = false)),
          page(null, master("a", "a"))))
    }

    fun forwardEmptyPagesAndAssetVideoPairsRemainSupported() {
      val asset = JSONObject().put("recordType", "CPLAsset").put("fields", JSONObject())
      val video = master("video", "video")
      video.getJSONObject("fields").put("itemType",
          JSONObject().put("value", "com.apple.quicktime-movie"))
      expect(listOf(null, "A", "B"), listOf("a", "b"), listOf(
          page("A"), page("B", asset, video, master("a", "a")),
          page(null, master("b", "b"))))
    }

    fun malformedLaterPagePreservesEarlierAlbum() {
      expect(listOf(null, "A"), listOf("a"),
          listOf(page("A", master("a", "a")), """{"records":["""))
    }

    fun failedLaterTransportPreservesEarlierAlbum() {
      expect(listOf(null, "A"), listOf("a"), listOf(page("A", master("a", "a")), null))
    }

    fun missingLaterRecordsPreservesEarlierAlbum() {
      expect(listOf(null, "A"), listOf("a"),
          listOf(page("A", master("a", "a")), """{"error":"public fixture"}"""))
    }

    fun ordinaryEmptyTerminalAlbumStillReturnsTitle() {
      expect(listOf(null), emptyList(), listOf(page(null)))
    }

    fun ordinaryForwardPagesRetainExistingFiftyPageCap() {
      val pages = (0..50).map { index -> page("marker-$index", master("$index", "$index")) }
      expect(listOf(null) + (0..48).map { "marker-$it" }, (0..49).map { "$it" }, pages)
    }

    private fun image(photo: String) = "https://public-images.invalid/$photo.jpg"
  }
}

/** One-shot factory is confined to this child; other Gradle suites retain their transport. */
object CloudKitPaginationProcess {
  var pages: List<String?> = emptyList()
  val requests = mutableListOf<String>()
  val queryMarkers = mutableListOf<String?>()
  var disconnections = 0

  private class Connection(url: URL) : HttpURLConnection(url) {
    private val output = ByteArrayOutputStream()
    private var prepared = false
    private var data: ByteArray? = null

    private fun prepare() {
      if (prepared) return
      prepared = true
      requests.add(url.toString())
      check(requestMethod == "POST")
      val payload = JSONObject(output.toString("UTF-8"))
      val response = if (url.path.endsWith("/records/resolve")) {
        assertEquals("public-fixture", payload.getJSONArray("shortGUIDs").getJSONObject(0).getString("value"))
        """{"results":[{"zoneID":{"zoneName":"Public Zone","ownerRecordName":"public-owner"},"anonymousPublicAccess":{"token":"public-token","databasePartition":"https://public-cloudkit.invalid:443"},"share":{"fields":{"cloudkit.title":{"value":"Public fixture"}}}}]}"""
      } else {
        check(url.host == "public-cloudkit.invalid" && url.path.endsWith("/records/query"))
        assertTrue(url.query.contains("publicAccessAuthToken=public-token"))
        assertEquals(200, payload.getInt("resultsLimit"))
        assertEquals("Public Zone", payload.getJSONObject("zoneID").getString("zoneName"))
        assertEquals("CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted",
            payload.getJSONObject("query").getString("recordType"))
        val marker = payload.optString("continuationMarker", "").ifBlank { null }
        val index = queryMarkers.size
        queryMarkers.add(marker)
        check(pages.isNotEmpty())
        pages[minOf(index, pages.lastIndex)]
      }
      data = response?.toByteArray(Charsets.UTF_8)
    }

    override fun getOutputStream() = output
    override fun getContentLengthLong(): Long {
      prepare()
      return data?.size?.toLong() ?: -1L
    }
    override fun getInputStream(): ByteArrayInputStream {
      prepare()
      return ByteArrayInputStream(data ?: throw IOException("Public generated transport failure"))
    }
    override fun disconnect() { disconnections++ }
    override fun connect() {}
    override fun usingProxy() = false
  }

  @JvmStatic fun main(args: Array<String>) {
    URL.setURLStreamHandlerFactory { protocol ->
      if (protocol != "https") null else object : URLStreamHandler() {
        override fun openConnection(url: URL): java.net.URLConnection {
          check(url.host in setOf("ckdatabasews.icloud.com", "public-cloudkit.invalid"))
          return Connection(url)
        }
      }
    }
    val cases = CloudKitPaginationTest.GeneratedCases()
    val names = listOf(
        "repeatedMarkerStopsBeforeAnotherRequestAndKeepsUniqueMasters",
        "markerCycleStopsBeforeRevisitingAndKeepsEarlierPhotos",
        "overlappingForwardPagesKeepFirstUsableMaster",
        "repeatedMasterWithinPageKeepsFirstButDistinctMastersMayShareUrl",
        "unnamedMastersUseUrlIdentityAcrossPages",
        "unusableFirstMasterDoesNotHideItsLaterValidAsset",
        "forwardEmptyPagesAndAssetVideoPairsRemainSupported",
        "malformedLaterPagePreservesEarlierAlbum",
        "failedLaterTransportPreservesEarlierAlbum",
        "missingLaterRecordsPreservesEarlierAlbum",
        "ordinaryEmptyTerminalAlbumStillReturnsTitle",
        "ordinaryForwardPagesRetainExistingFiftyPageCap")
    var failures = 0
    names.forEach { name ->
      try {
        cases.javaClass.getMethod(name).invoke(cases)
        println("CLOUDKIT_CASE $name PASS")
      } catch (error: java.lang.reflect.InvocationTargetException) {
        failures++
        println("CLOUDKIT_CASE $name FAIL")
        error.cause!!.printStackTrace(System.out)
      }
    }
    println("CLOUDKIT_CASES tests=${names.size} failures=$failures")
    kotlin.system.exitProcess(if (failures == 0) 0 else 1)
  }
}
