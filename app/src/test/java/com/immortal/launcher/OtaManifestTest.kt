/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtaManifestTest {
  private val pkg = "com.immortal.launcher.debug"
  private val sha = "ab".repeat(32)
  private val apkUrl = "https://github.com/rlchandani/portal-prime/releases/download/hearthpane-v1.229/hearthpane.apk"

  private fun manifest(edit: JSONObject.() -> Unit = {}): String = JSONObject()
      .put("schema", 1).put("packageName", pkg).put("versionCode", 224).put("versionName", "1.229")
      .put("sha256", sha).put("size", 12_345_678).put("apkUrl", apkUrl).put("minSdk", 24)
      .put("notes", "Wireless updates").apply(edit).toString()

  private fun parse(json: String, installed: Long = 223, sdk: Int = 29) =
      OtaManifestParser.parse(json, pkg, installed, sdk)

  private fun rejection(json: String, installed: Long = 223, sdk: Int = 29) =
      (parse(json, installed, sdk) as OtaManifestResult.Rejected).reason

  @Test fun validManifestParses() {
    val m = (parse(manifest()) as OtaManifestResult.Valid).manifest
    assertEquals(pkg, m.packageName)
    assertEquals(224L, m.versionCode)
    assertEquals("1.229", m.versionName)
    assertEquals(sha, m.sha256)
    assertEquals(12_345_678L, m.size)
    assertEquals(apkUrl, m.apkUrl)
    assertEquals("Wireless updates", m.notes)
  }

  @Test fun uppercaseShaIsNormalized() {
    val m = (parse(manifest { put("sha256", "AB".repeat(32)) }) as OtaManifestResult.Valid).manifest
    assertEquals(sha, m.sha256)
  }

  @Test fun schemaMismatchIsRejected() {
    assertEquals(OtaRejection.SCHEMA, rejection(manifest { put("schema", 2) }))
    assertEquals(OtaRejection.SCHEMA, rejection(manifest { remove("schema") }))
    assertEquals(OtaRejection.SCHEMA, rejection(manifest { put("schema", "1") }))
  }

  @Test fun malformedJsonIsRejected() {
    assertEquals(OtaRejection.MALFORMED, rejection("not json"))
    assertEquals(OtaRejection.MALFORMED, rejection(manifest { remove("versionCode") }))
    assertEquals(OtaRejection.MALFORMED, rejection(manifest { put("versionCode", "224") }))
    assertEquals(OtaRejection.MALFORMED, rejection(manifest { put("versionName", "") }))
  }

  @Test fun wrongPackageIsRejected() {
    assertEquals(OtaRejection.WRONG_PACKAGE, rejection(manifest { put("packageName", "com.immortal.launcher") }))
    assertEquals(OtaRejection.WRONG_PACKAGE, rejection(manifest { put("packageName", "com.portalinstaller") }))
  }

  @Test fun notNewerIsReportedAsNotNewer() {
    assertEquals(OtaRejection.NOT_NEWER, rejection(manifest(), installed = 224))
    assertEquals(OtaRejection.NOT_NEWER, rejection(manifest(), installed = 300))
  }

  @Test fun nonHttpsOrNonGithubUrlsAreRejected() {
    fun url(u: String) = rejection(manifest { put("apkUrl", u) })
    assertEquals(OtaRejection.BAD_URL, url(apkUrl.replace("https://", "http://")))
    assertEquals(OtaRejection.BAD_URL, url("https://example.com/rlchandani/portal-prime/releases/download/x/hearthpane.apk"))
    assertEquals(OtaRejection.BAD_URL, url("https://github.com.evil.io/rlchandani/portal-prime/releases/download/x/a.apk"))
    assertEquals(OtaRejection.BAD_URL, url("https://github.com@evil.io/rlchandani/portal-prime/releases/download/x/a.apk"))
    assertEquals(OtaRejection.BAD_URL, url("https://github.com:8443/rlchandani/portal-prime/releases/download/x/a.apk"))
    assertEquals(OtaRejection.BAD_URL, url("https://github.com/someone/else/releases/download/x/hearthpane.apk"))
    assertEquals(OtaRejection.BAD_URL, url("https://github.com/rlchandani/portal-prime/releases/download/../../../x.apk"))
    assertEquals(OtaRejection.BAD_URL, url("not a url"))
  }

  @Test fun badShaIsRejected() {
    assertEquals(OtaRejection.BAD_SHA, rejection(manifest { put("sha256", "ab".repeat(31)) }))
    assertEquals(OtaRejection.BAD_SHA, rejection(manifest { put("sha256", "zz".repeat(32)) }))
    assertEquals(OtaRejection.BAD_SHA, rejection(manifest { remove("sha256") }))
  }

  @Test fun badSizeIsRejected() {
    assertEquals(OtaRejection.BAD_SIZE, rejection(manifest { put("size", 0) }))
    assertEquals(OtaRejection.BAD_SIZE, rejection(manifest { put("size", -1) }))
    assertEquals(OtaRejection.BAD_SIZE, rejection(manifest { put("size", OtaConstants.MAX_APK_BYTES + 1) }))
    assertEquals(OtaRejection.BAD_SIZE, rejection(manifest { put("size", 1.5) }))
    assertEquals(OtaRejection.BAD_SIZE, rejection(manifest { remove("size") }))
  }

  @Test fun tooNewMinSdkIsRejected() {
    assertEquals(OtaRejection.UNSUPPORTED_DEVICE, rejection(manifest { put("minSdk", 30) }, sdk = 29))
  }

  @Test fun notesAreBounded() {
    val m = (parse(manifest { put("notes", "x".repeat(5_000)) }) as OtaManifestResult.Valid).manifest
    assertEquals(OtaConstants.MAX_NOTES, m.notes.length)
  }

  @Test fun redirectHopsStayOnGithub() {
    assertTrue(OtaUrlPolicy.isAllowedHop("https://objects.githubusercontent.com/github-production-release-asset/1"))
    assertTrue(OtaUrlPolicy.isAllowedHop("https://release-assets.githubusercontent.com/github-production-release-asset/1"))
    assertTrue(OtaUrlPolicy.isAllowedHop(OtaConstants.FEED_URL))
    assertFalse(OtaUrlPolicy.isAllowedHop("http://objects.githubusercontent.com/x"))
    assertFalse(OtaUrlPolicy.isAllowedHop("https://evil.io/x"))
    assertEquals("https://objects.githubusercontent.com/a?b=1",
        OtaUrlPolicy.nextHop(apkUrl, "https://objects.githubusercontent.com/a?b=1"))
    assertEquals("https://github.com/rlchandani/portal-prime/releases/download/v2/hearthpane.apk",
        OtaUrlPolicy.nextHop(apkUrl, "/rlchandani/portal-prime/releases/download/v2/hearthpane.apk"))
    assertNull(OtaUrlPolicy.nextHop(apkUrl, "https://evil.io/hearthpane.apk"))
    assertNull(OtaUrlPolicy.nextHop(apkUrl, null))
  }
}
