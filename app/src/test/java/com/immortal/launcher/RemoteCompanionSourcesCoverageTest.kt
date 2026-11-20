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
import org.junit.Test

/** A phone must never be able to redirect a saved photo-source secret to a different peer. */
class RemoteCompanionSourcesCoverageTest {
  private val stored = ScreensaverConfig.Settings(
      immichUrl = "https://photos.example.invalid", immichKey = "test-immich-key",
      smbHost = "nas.example.invalid", smbShare = "photos", smbPath = "/family", smbUser = "reader", smbPass = "test-smb-pass",
      davUrl = "https://dav.example.invalid/photos", davUser = "reader", davPass = "test-dav-pass")
  private val empty = ScreensaverConfig.Settings()
  private fun prepare(vararg pairs: Pair<String, Any>, from: ScreensaverConfig.Settings = stored) =
      RemoteCompanionSources.prepare(JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }, from)

  @Test fun endpointNormalisesSchemeHostPortPathAndQuery() {
    assertEquals("http://h.example.invalid:80", RemoteCompanionSources.endpoint("http://H.Example.Invalid/"))
    assertEquals("https://h.example.invalid:443", RemoteCompanionSources.endpoint(" HTTPS://h.example.invalid "))
    assertEquals("https://h.example.invalid:8443/p?q=1", RemoteCompanionSources.endpoint("https://h.example.invalid:8443/p/?q=1"))
    assertEquals("https://h.example.invalid:443", RemoteCompanionSources.endpoint("https://h.example.invalid/api/", immich = true))
    assertEquals("https://h.example.invalid:443/api", RemoteCompanionSources.endpoint("https://h.example.invalid/api/"))
  }

  @Test fun endpointRejectsNonHttpCredentialsFragmentsAndGarbage() {
    for (url in listOf("ftp://h.example.invalid", "file:///sdcard/x", "https://", "https://user:pw@h.example.invalid",
        "https://h.example.invalid/#frag", "not a url", "http://bad host/", "", "javascript:alert(1)"))
      assertNull(url, RemoteCompanionSources.endpoint(url))
  }

  @Test fun albumAndWebUrlsAreTypeCheckedAndValidated() {
    assertEquals("invalid_source_field", prepare("albumUrl" to 5).error)
    assertEquals("invalid_album_link", prepare("albumUrl" to "https://example.invalid/album").error)
    val album = prepare("albumUrl" to "https://www.icloud.com/sharedalbum/#B0aBcDeF")
    assertNull(album.error)
    assertEquals("invalid_source_field", prepare("webUrl" to true).error)
    assertEquals("invalid_web_url", prepare("webUrl" to "javascript:alert(1)").error)
    assertEquals("invalid_web_url", prepare("webUrl" to "   ").error)
    val raw = " https://frame.example.invalid/page "
    assertEquals(WebPageUrl.normalize(raw), prepare("webUrl" to raw).body!!.getString("webUrl"))
  }

  @Test fun nonStringCredentialFieldsAreRejectedBeforeAnyResolution() {
    for (field in listOf("immichUrl", "immichKey", "immichAlbumId", "smbHost", "smbShare", "smbPath", "smbUser", "smbPass", "davUrl", "davUser", "davPass")) {
      val result = prepare(field to 42)
      assertEquals(field, "invalid_source_field", result.error)
      assertNull(field, result.body)
    }
  }

  @Test fun unrelatedFieldsPassThroughUntouched() {
    val result = prepare("source" to "default", "interval" to 30)
    assertNull(result.error)
    assertEquals(setOf("source", "interval"), result.body!!.fieldKeys())
  }

  @Test fun immichAlbumOnlyEditReusesSavedEndpointAndKey() {
    val body = prepare("immichAlbumId" to "album-1").body!!
    assertEquals("https://photos.example.invalid", body.getString("immichUrl"))
    assertEquals("test-immich-key", body.getString("immichKey"))
    assertEquals("invalid_immich_endpoint", prepare("immichAlbumId" to "album-1", from = empty).error)
  }

  @Test fun immichKeyIsNotReusedAcrossSchemePortOrPathChanges() {
    for (url in listOf("http://photos.example.invalid", "https://photos.example.invalid:8443", "https://photos.example.invalid/other", "https://photos.example.invalid?x=1"))
      assertEquals(url, "immichKey_required", prepare("immichUrl" to url).error)
    // Default port written explicitly, case and /api suffix are the same endpoint.
    assertEquals("test-immich-key", prepare("immichUrl" to "https://PHOTOS.example.invalid:443/API").body!!.getString("immichKey"))
    assertEquals("invalid_immich_endpoint", prepare("immichUrl" to "https://evil@photos.example.invalid").error)
  }

  @Test fun maskedOrBlankSecretsCountAsOmitted() {
    val kept = prepare("immichUrl" to "https://photos.example.invalid", "immichKey" to SourceSecrets.MASK).body!!
    assertEquals("test-immich-key", kept.getString("immichKey"))
    // A masked placeholder sent to a new host must not smuggle the saved key there.
    assertEquals("immichKey_required", prepare("immichUrl" to "https://other.example.invalid", "immichKey" to SourceSecrets.MASK).error)
    assertEquals("smbPass_required", prepare("smbHost" to "other.example.invalid", "smbShare" to "photos", "smbUser" to "reader", "smbPass" to "  ").error)
  }

  @Test fun smbRequiresHostAndShare() {
    assertEquals("smb_endpoint_required", prepare("smbHost" to " ", "smbShare" to "photos").error)
    assertEquals("smb_endpoint_required", prepare("smbUser" to "reader", from = empty).error)
    assertEquals("smb_endpoint_required", prepare("smbHost" to "nas.example.invalid", "smbShare" to "", from = empty).error)
  }

  @Test fun smbSameShareKeepsAccountPathAndTrimsInput() {
    val body = prepare("smbHost" to " NAS.example.invalid ", "smbShare" to "photos").body!!
    assertEquals("NAS.example.invalid", body.getString("smbHost"))
    assertEquals("reader", body.getString("smbUser"))
    assertEquals("test-smb-pass", body.getString("smbPass"))
    assertEquals("/family", body.getString("smbPath"))
    val explicitPath = prepare("smbPath" to "/other").body!!
    assertEquals("/other", explicitPath.getString("smbPath"))
    assertEquals("test-smb-pass", explicitPath.getString("smbPass"))
  }

  @Test fun smbShareNameIsCaseSensitiveAndNewAccountNeedsPassword() {
    // A different share is a different peer: the saved account is not carried over.
    val otherShare = prepare("smbShare" to "Photos").body!!
    assertEquals("", otherShare.getString("smbUser"))
    assertEquals("", otherShare.getString("smbPass"))
    assertEquals("smbPass_required", prepare("smbUser" to "admin").error)
    val withPass = prepare("smbUser" to "admin", "smbPass" to "test-new-pass").body!!
    assertEquals("test-new-pass", withPass.getString("smbPass"))
  }

  @Test fun smbNewAnonymousShareGetsNoSavedUserPasswordOrPath() {
    val body = prepare("smbHost" to "other.example.invalid", "smbShare" to "public").body!!
    assertEquals("", body.getString("smbUser"))
    assertEquals("", body.getString("smbPass"))
    assertFalse(body.has("smbPath"))
  }

  @Test fun davUserOnlyEditUsesSavedEndpointAndRejectsInvalidEndpoints() {
    val body = prepare("davUser" to " reader ").body!!
    assertEquals("https://dav.example.invalid/photos", body.getString("davUrl"))
    assertEquals("reader", body.getString("davUser"))
    assertEquals("test-dav-pass", body.getString("davPass"))
    assertEquals("invalid_dav_endpoint", prepare("davUser" to "reader", from = empty).error)
    assertEquals("invalid_dav_endpoint", prepare("davUrl" to "ftp://dav.example.invalid").error)
    assertEquals("davPass_required", prepare("davUrl" to "http://dav.example.invalid/photos", "davUser" to "reader").error)
  }

  @Test fun davExplicitPasswordWinsAndSameAccountIsDetectedAfterTrailingSlash() {
    val body = prepare("davUrl" to "https://dav.example.invalid/photos/", "davUser" to "reader", "davPass" to "test-fresh").body!!
    assertEquals("test-fresh", body.getString("davPass"))
    val kept = prepare("davUser" to "reader", "davPass" to "").body!!
    assertEquals("test-dav-pass", kept.getString("davPass"))
  }
}
