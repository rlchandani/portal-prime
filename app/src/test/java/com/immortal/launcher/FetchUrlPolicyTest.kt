/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Findings 5 and 6: request-supplied fetch URLs and `/fs/write` limits. Pure, no network. */
class FetchUrlPolicyTest {
  private fun ip(vararg bytes: Int): InetAddress = InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() })
  private fun resolver(map: Map<String, List<InetAddress>>): (String) -> List<InetAddress> =
      { host ->
        map[host] ?: if (host.first().isDigit() || ':' in host) listOf(InetAddress.getByName(host))
        else throw java.net.UnknownHostException(host)
      }

  @Test fun onlyRfc1918AndUniqueLocalCountAsPrivateLan() {
    for (a in listOf(ip(10, 1, 2, 3), ip(172, 16, 0, 1), ip(172, 31, 255, 255), ip(192, 168, 1, 5))) assertTrue("$a", LanUrlPolicy.isPrivateLan(a))
    val ula = ip(0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    assertTrue(LanUrlPolicy.isPrivateLan(ula))
    for (a in listOf(ip(127, 0, 0, 1), ip(169, 254, 169, 254), ip(8, 8, 8, 8), ip(172, 32, 0, 1), ip(0, 0, 0, 0),
        ip(224, 0, 0, 1), ip(100, 64, 0, 1), InetAddress.getByName("::1"), InetAddress.getByName("fe80::1"))) {
      assertFalse("$a", LanUrlPolicy.isPrivateLan(a))
    }
  }

  @Test fun immichPickerMayOnlyReachTheSavedServerOrAHomeNetworkHost() {
    val dns = resolver(mapOf(
        "immich.lan" to listOf(ip(192, 168, 1, 5)),
        "photos.example.com" to listOf(ip(93, 184, 216, 34)),
        "rebind.example" to listOf(ip(192, 168, 1, 5), ip(8, 8, 8, 8))))
    val saved = "https://photos.example.com"
    assertTrue(LanUrlPolicy.immichAlbumsAllowed("https://PHOTOS.example.com/api/", saved, dns))
    assertTrue(LanUrlPolicy.immichAlbumsAllowed("http://immich.lan:2283", saved, dns))
    assertTrue(LanUrlPolicy.immichAlbumsAllowed("http://10.0.0.9:2283", null, dns))
    assertFalse(LanUrlPolicy.immichAlbumsAllowed("https://photos.example.com", null, dns))
    assertFalse(LanUrlPolicy.immichAlbumsAllowed("http://127.0.0.1:8090", saved, dns))
    assertFalse(LanUrlPolicy.immichAlbumsAllowed("http://169.254.169.254/latest", saved, dns))
    assertFalse("mixed answers", LanUrlPolicy.immichAlbumsAllowed("http://rebind.example", saved, dns))
    assertFalse(LanUrlPolicy.immichAlbumsAllowed("http://unknown.example", saved, dns))
    assertFalse(LanUrlPolicy.immichAlbumsAllowed("ftp://192.168.1.5", saved, dns))
    assertFalse(LanUrlPolicy.immichAlbumsAllowed("http://user@192.168.1.5", saved, dns))
    assertFalse(LanUrlPolicy.immichAlbumsAllowed("", saved, dns))
  }

  @Test fun installUrlsMustUseReleaseHostsFDroidOrCatalogHosts() {
    val catalog = listOf(CatalogApp("A", "a", "url", null, "https://downloads.vendor.example/a.apk", null, description = "", category = ""),
        CatalogApp("B", "b", "url", null, "http://plain.example/b.apk", null, description = "", category = ""))
    val hosts = ApkUrlPolicy.allowedHosts(catalog)
    assertTrue("downloads.vendor.example" in hosts)
    assertFalse("non-https catalog hosts are not added", "plain.example" in hosts)
    assertTrue(ApkUrlPolicy.isAllowed("https://github.com/o/r/releases/download/v1/x.apk", hosts))
    assertTrue(ApkUrlPolicy.isAllowed("https://f-droid.org/repo/x_1.apk", hosts))
    assertTrue(ApkUrlPolicy.isAllowed("https://downloads.vendor.example/a.apk", hosts))
    for (bad in listOf("http://github.com/x.apk", "https://192.168.1.10/x.apk", "https://github.com:8443/x.apk",
        "https://user@github.com/x.apk", "https://evil.example/x.apk", "file:///sdcard/x.apk", "not a url")) {
      assertFalse(bad, ApkUrlPolicy.isAllowed(bad, hosts))
    }
  }

  @Test fun installRedirectsStayOnAllowedHosts() {
    val hosts = ApkUrlPolicy.BUILT_IN_HOSTS
    val start = "https://github.com/o/r/releases/download/v1/x.apk"
    assertEquals("https://objects.githubusercontent.com/a?b=1", ApkUrlPolicy.nextHop(start, "https://objects.githubusercontent.com/a?b=1", hosts))
    assertEquals("https://github.com/o/r/other.apk", ApkUrlPolicy.nextHop(start, "/o/r/other.apk", hosts))
    assertNull(ApkUrlPolicy.nextHop(start, "https://10.0.0.1/x.apk", hosts))
    assertNull(ApkUrlPolicy.nextHop(start, "http://objects.githubusercontent.com/a", hosts))
    assertNull(ApkUrlPolicy.nextHop(start, null, hosts))
  }

  @Test fun fsWriteNeedsRoomAndStaysUnderTheCap() {
    val gib = 1024L * 1024 * 1024
    assertNull(FsWritePolicy.refusal(100L * 1024 * 1024, 2 * gib))
    assertNull(FsWritePolicy.refusal(0L, FsWritePolicy.MIN_FREE_AFTER_BYTES))
    assertEquals("body_too_large", FsWritePolicy.refusal(FsWritePolicy.MAX_BYTES + 1, 100 * gib))
    assertEquals("insufficient_storage", FsWritePolicy.refusal(100L * 1024 * 1024, 300L * 1024 * 1024))
    assertEquals("insufficient_storage", FsWritePolicy.refusal(0L, 0L))
  }

  @Test fun albumFetchIsBoundedInTime() {
    val release = CountDownLatch(1)
    val started = System.nanoTime()
    val result = ImmichAlbumFetch.list("u", "k", budgetMs = 200L) { _, _ -> release.await(5, TimeUnit.SECONDS); emptyList() }
    assertEquals(ImmichAlbumFetch.Result.TimedOut, result)
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000L)
    release.countDown()
  }

  @Test fun albumFetchReportsFailureAndAlbums() {
    assertEquals(ImmichAlbumFetch.Result.Failed, ImmichAlbumFetch.list("u", "k") { _, _ -> null })
    assertEquals(ImmichAlbumFetch.Result.Failed, ImmichAlbumFetch.list("u", "k") { _, _ -> error("boom") })
    val albums = listOf(ImmichSource.Album("1", "Family", 3))
    assertEquals(ImmichAlbumFetch.Result.Albums(albums), ImmichAlbumFetch.list("u", "k") { _, _ -> albums })
  }
}
