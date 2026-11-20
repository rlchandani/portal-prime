/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.InetAddress
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Edge cases of the LAN / APK host allowlists: resolver failures, odd URIs, ports and redirects. */
class FetchUrlPolicyCoverageTest {
  private fun ip(vararg bytes: Int): InetAddress = InetAddress.getByAddress(ByteArray(bytes.size) { bytes[it].toByte() })

  private fun app(url: String?) = CatalogApp("A", "a", "url", null, url, null, description = "", category = "")

  @Test fun savedServerMatchIsByNormalizedEndpointOnly() {
    val never: (String) -> List<InetAddress> = { throw AssertionError("saved server must not be resolved") }
    assertEquals(LanUrlPolicy.Target.Saved,
        LanUrlPolicy.immichAlbumsTarget("https://photos.example.invalid/", "https://PHOTOS.example.invalid:443", never))
    val dns: (String) -> List<InetAddress> = { listOf(ip(8, 8, 8, 8)) }
    assertNull("blank stored is not a match", LanUrlPolicy.immichAlbumsTarget("https://photos.example.invalid", "  ", dns))
    assertNull("different port is not the saved server",
        LanUrlPolicy.immichAlbumsTarget("https://photos.example.invalid:8443", "https://photos.example.invalid", dns))
    assertNull("unparseable stored", LanUrlPolicy.immichAlbumsTarget("https://photos.example.invalid", "::nope::", dns))
  }

  @Test fun resolverFailureOrEmptyAnswerIsRefused() {
    assertNull(LanUrlPolicy.immichAlbumsTarget("http://immich.example.invalid", null) { throw UnknownHostException(it) })
    assertNull(LanUrlPolicy.immichAlbumsTarget("http://immich.example.invalid", null) { emptyList() })
    assertNull(LanUrlPolicy.immichAlbumsTarget("http://immich.example.invalid", null) { throw IllegalStateException() })
  }

  @Test fun pinnedTargetCarriesTheCheckedAnswer() {
    val answer = listOf(ip(192, 168, 7, 2), ip(10, 0, 0, 3))
    val target = LanUrlPolicy.immichAlbumsTarget("http://immich.example.invalid:2283", null) { answer }
    assertEquals(LanUrlPolicy.Target.Pinned("immich.example.invalid", answer), target)
  }

  @Test fun ipv6UniqueLocalLiteralIsPinnedWithoutBrackets() {
    val ula = ip(0xfd, 0x12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    var asked: String? = null
    val target = LanUrlPolicy.immichAlbumsTarget("http://[fd12::1]:2283", null) { asked = it; listOf(ula) }
    assertEquals("fd12::1", asked)
    assertTrue(target is LanUrlPolicy.Target.Pinned)
    val fc = ip(0xfc, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    assertTrue(LanUrlPolicy.isPrivateLan(fc))
    val global = ip(0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
    assertFalse(LanUrlPolicy.isPrivateLan(global))
  }

  @Test fun defaultSystemResolverHandlesIpLiteralsWithoutDns() {
    assertTrue(LanUrlPolicy.immichAlbumsAllowed("http://192.168.50.2:2283", null))
    assertFalse(LanUrlPolicy.immichAlbumsAllowed("http://192.0.2.10:2283", null))
    assertTrue(LanUrlPolicy.immichAlbumsTarget("http://10.1.2.3", null) is LanUrlPolicy.Target.Pinned)
  }

  @Test fun catalogEntriesWithoutUrlOrHostAddNothing() {
    val hosts = ApkUrlPolicy.allowedHosts(listOf(app(null), app("https:///nohost.apk"), app("https://CDN.Example.invalid/x.apk")))
    assertEquals(ApkUrlPolicy.BUILT_IN_HOSTS + "cdn.example.invalid", hosts)
  }

  @Test fun explicitDefaultHttpsPortAndSchemeCaseAreAccepted() {
    val hosts = setOf("cdn.example.invalid")
    assertTrue(ApkUrlPolicy.isAllowed("https://cdn.example.invalid:443/x.apk", hosts))
    assertTrue(ApkUrlPolicy.isAllowed("HTTPS://CDN.example.invalid/x.apk", hosts))
    assertFalse("relative", ApkUrlPolicy.isAllowed("/x.apk", hosts))
    assertFalse("registry authority has no host", ApkUrlPolicy.isAllowed("https://bad_host!/x.apk", hosts))
    assertFalse("port 80", ApkUrlPolicy.isAllowed("https://cdn.example.invalid:80/x.apk", hosts))
  }

  @Test fun redirectEdgeCases() {
    val hosts = setOf("cdn.example.invalid")
    val start = "https://cdn.example.invalid/a/x.apk"
    assertNull("blank", ApkUrlPolicy.nextHop(start, "   ", hosts))
    assertNull("empty", ApkUrlPolicy.nextHop(start, "", hosts))
    assertNull("unparseable location", ApkUrlPolicy.nextHop(start, "https://[bad", hosts))
    assertNull("unparseable current", ApkUrlPolicy.nextHop("::bad url::", "/x.apk", hosts))
    assertEquals("https://cdn.example.invalid/a/y.apk", ApkUrlPolicy.nextHop(start, "  y.apk ", hosts))
    assertNull("credentials smuggled in redirect", ApkUrlPolicy.nextHop(start, "https://u:p@cdn.example.invalid/x", hosts))
  }

  @Test fun fsWriteBoundariesAreInclusive() {
    assertNull(FsWritePolicy.refusal(FsWritePolicy.MAX_BYTES, FsWritePolicy.MAX_BYTES + FsWritePolicy.MIN_FREE_AFTER_BYTES))
    assertEquals("insufficient_storage",
        FsWritePolicy.refusal(FsWritePolicy.MAX_BYTES, FsWritePolicy.MAX_BYTES + FsWritePolicy.MIN_FREE_AFTER_BYTES - 1))
  }
}
