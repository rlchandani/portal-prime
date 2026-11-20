/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.URLDecoder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure shared-album link recognition and JSON/HTML extraction; no network fetch is exercised. */
class RemoteAlbumParsingCoverageTest {

  @Test fun providerNameCoversEveryProviderAndFallback() {
    assertEquals("iCloud Shared Album", RemoteAlbum.providerName("https://www.icloud.com/sharedalbum/#B0abc"))
    assertEquals("iCloud Shared Album", RemoteAlbum.providerName("https://photos.icloud.com/shared/album/ABC"))
    assertEquals("Google Photos", RemoteAlbum.providerName("https://photos.app.goo.gl/abc"))
    assertEquals("Synology Photos", RemoteAlbum.providerName("https://nas.example.invalid/mo/sharing/tok"))
    assertEquals("Shared album", RemoteAlbum.providerName("https://example.invalid/album"))
  }

  @Test fun shareLinksMustBePlainHttpWithAHostAndNoCredentialsOrBadPort() {
    for (url in listOf(
        "ftp://www.icloud.com/sharedalbum/#B0abc",
        "mailto:someone@icloud.com",
        "https://user@www.icloud.com/sharedalbum/#B0abc",
        "https://user:pw@photos.app.goo.gl/abc",
        "https:///sharedalbum/#B0abc",
        "https://nas.example.invalid:0/mo/sharing/tok",
        "https://nas.example.invalid:70000/mo/sharing/tok",
        "not a url at all",
        "",
    )) assertFalse(url, RemoteAlbum.isSupported(url))
    assertTrue(RemoteAlbum.isSupported("  HTTPS://WWW.ICLOUD.COM/sharedalbum/#B0abc  "))
    assertTrue(RemoteAlbum.isSupported("http://nas.example.invalid:5000/mo/sharing/tok"))
  }

  @Test fun icloudHostMustBeIcloudOrASubdomain() {
    assertTrue(RemoteAlbum.isIcloudCloudKit("https://icloud.com./shared/album/ABC"))
    for (host in listOf("evilicloud.com", "icloud.com.evil.example", "icloud.co"))
      assertFalse(host, RemoteAlbum.isIcloudCloudKit("https://$host/shared/album/ABC"))
  }

  @Test fun legacyIcloudNeedsExactRouteAndNonBlankFragment() {
    assertTrue(RemoteAlbum.isIcloudLegacy("https://www.icloud.com/photostream/#B0abc"))
    assertTrue(RemoteAlbum.isIcloudLegacy("https://www.icloud.com/Photo-Stream/#B0abc"))
    for (url in listOf("https://www.icloud.com/sharedalbum/", "https://www.icloud.com/sharedalbum/#",
        "https://www.icloud.com/sharedalbum/#/", "https://www.icloud.com/sharedalbum/x/#B0abc",
        "https://www.icloud.com/sharedalbum/#?x=1"))
      assertFalse(url, RemoteAlbum.isIcloudLegacy(url))
  }

  @Test fun icloudTokenStripsQueryAndSlashes() {
    assertEquals("B0abc", RemoteAlbum.icloudToken("https://www.icloud.com/sharedalbum/#/B0abc/?x=1"))
    assertNull(RemoteAlbum.icloudToken("https://www.icloud.com/sharedalbum/"))
    assertNull(RemoteAlbum.icloudToken("https://www.icloud.com/sharedalbum/# ?x"))
  }

  @Test fun cloudKitRequiresTokenSegment() {
    assertFalse(RemoteAlbum.isIcloudCloudKit("https://photos.icloud.com/shared/album/"))
    assertFalse(RemoteAlbum.isIcloudCloudKit("https://photos.icloud.com/shared/album//x"))
    assertFalse(RemoteAlbum.isIcloudCloudKit("https://photos.icloud.com/shared/albums/ABC"))
    assertTrue(RemoteAlbum.isIcloudCloudKit("https://photos.icloud.com/SHARED/ALBUM/ABC/extra"))
  }

  @Test fun cloudKitTokenTrimsEverySuffix() {
    assertEquals("ABC", RemoteAlbum.cloudKitToken("https://photos.icloud.com/shared/album/ABC?x=1"))
    assertEquals("ABC", RemoteAlbum.cloudKitToken("https://photos.icloud.com/shared/album/ABC#frag"))
    assertEquals("ABC", RemoteAlbum.cloudKitToken("https://photos.icloud.com/Shared/Album/ABC/more"))
    assertNull(RemoteAlbum.cloudKitToken("https://photos.icloud.com/shared/album/"))
    assertNull(RemoteAlbum.cloudKitToken("https://photos.icloud.com/shared/album/ ?x"))
    assertNull(RemoteAlbum.cloudKitToken("https://photos.icloud.com/other/ABC"))
  }

  @Test fun googleLinksNeedTheirTokenRoute() {
    assertTrue(RemoteAlbum.isGooglePhotos("https://photos.google.com/share/AF1Qabc?key=x"))
    assertTrue(RemoteAlbum.isGooglePhotos("https://photos.app.goo.gl./abc"))
    for (url in listOf("https://photos.app.goo.gl/", "https://photos.google.com/share/", "https://photos.google.com/album/x",
        "https://google.com/share/abc", "https://photos.google.com.evil.example/share/abc"))
      assertFalse(url, RemoteAlbum.isGooglePhotos(url))
  }

  @Test fun fetchOfAnUnsupportedLinkReturnsNullWithoutNetwork() {
    assertNull(RemoteAlbum.fetch("https://example.invalid/album"))
    assertNull(RemoteAlbum.fetch("   "))
  }

  @Test fun icloudPartitionFollowsTokenAlphabet() {
    assertEquals("01", RemoteAlbum.icloudPartition(""))
    assertEquals("01", RemoteAlbum.icloudPartition("0abc"))
    assertEquals("10", RemoteAlbum.icloudPartition("9"))
    assertEquals("11", RemoteAlbum.icloudPartition("A"))
    assertEquals("62", RemoteAlbum.icloudPartition("z"))
    assertEquals("01", RemoteAlbum.icloudPartition("-x"))
  }

  @Test fun pickBestDerivativeSkipsMalformedEntries() {
    val derivs = JSONObject()
        .put("thumb", JSONObject().put("checksum", "named"))
        .put("4000", "not an object")
        .put("3000", JSONObject().put("checksum", JSONObject.NULL))
        .put("2500", JSONObject().put("checksum", ""))
        .put("2048", JSONObject().put("checksum", "c2048"))
        .put("1920", JSONObject().put("checksum", "c1920"))
        .put("640", JSONObject().put("checksum", "c640"))
    assertEquals("c1920", RemoteAlbum.pickBestDerivative(derivs, 1920, 1080))
    assertEquals("c1920", RemoteAlbum.pickBestDerivative(derivs, 1080, 1920))
    assertEquals("c2048", RemoteAlbum.pickBestDerivative(derivs, 3840, 2160))
    assertEquals("c640", RemoteAlbum.pickBestDerivative(derivs, 100, 100))
    assertNull(RemoteAlbum.pickBestDerivative(JSONObject().put("x", JSONObject().put("checksum", "c")), 10, 10))
  }

  private fun asset(url: Any?, width: Any?) = JSONObject().put("value", JSONObject().put("downloadURL", url ?: JSONObject.NULL)).let { res ->
    res to (width?.let { JSONObject().put("value", it) })
  }

  @Test fun pickBestCloudKitAssetIgnoresVideoAndUnusableBlobs() {
    val fields = JSONObject()
    fun add(prefix: String, url: Any?, width: Any?) {
      val (res, w) = asset(url, width)
      fields.put("${prefix}Res", res)
      if (w != null) fields.put("${prefix}Width", w)
    }
    add("resJPEGFull", "https://cvws.example.invalid/full", 4032)
    add("resJPEGMed", "https://cvws.example.invalid/med", 2048)
    add("resJPEGThumb", "https://cvws.example.invalid/thumb", 320)
    add("resVidMed", "https://cvws.example.invalid/video", 1920)
    add("resBroken", "null", 1950)
    add("resEmpty", "", 1960)
    add("resNoWidth", "https://cvws.example.invalid/nowidth", null)
    fields.put("resOddRes", "string blob")
    fields.put("filenameEnc", JSONObject().put("value", "x"))
    assertEquals("https://cvws.example.invalid/med", RemoteAlbum.pickBestCloudKitAsset(fields, 1920, 1080))
    assertEquals("https://cvws.example.invalid/full", RemoteAlbum.pickBestCloudKitAsset(fields, 8000, 8000))
    val onlyUnsized = JSONObject()
    val (res, _) = asset("https://cvws.example.invalid/only", null)
    onlyUnsized.put("resOriginalRes", res)
    assertEquals("https://cvws.example.invalid/only", RemoteAlbum.pickBestCloudKitAsset(onlyUnsized, 1920, 1080))
    assertNull(RemoteAlbum.pickBestCloudKitAsset(JSONObject(), 1920, 1080))
  }

  @Test fun cloudKitImageFilterDropsMoviesAndVideos() {
    assertTrue(RemoteAlbum.isCloudKitImage(JSONObject()))
    assertTrue(RemoteAlbum.isCloudKitImage(JSONObject().put("itemType", JSONObject().put("value", ""))))
    assertTrue(RemoteAlbum.isCloudKitImage(JSONObject().put("itemType", "public.jpeg")))
    assertTrue(RemoteAlbum.isCloudKitImage(JSONObject().put("itemType", JSONObject().put("value", "public.heic"))))
    assertFalse(RemoteAlbum.isCloudKitImage(JSONObject().put("itemType", JSONObject().put("value", "com.apple.QuickTime-Movie"))))
    assertFalse(RemoteAlbum.isCloudKitImage(JSONObject().put("itemType", JSONObject().put("value", "public.Video"))))
  }

  @Test fun googleAlbumKeyPrefersUrlThenHtml() {
    assertEquals("AF1QabcDEF", RemoteAlbum.extractGoogleAlbumKey("https://PHOTOS.GOOGLE.COM/share/AF1QabcDEF?key=x", ""))
    assertEquals("AF1Qa1xyz_-9", RemoteAlbum.extractGoogleAlbumKey("https://photos.app.goo.gl/abc", "<script>var a='AF1Qa1xyz_-9';</script>"))
    assertNull(RemoteAlbum.extractGoogleAlbumKey("https://photos.app.goo.gl/abc", "<html>AF1Qa1notquoted</html>"))
  }

  @Test fun googleContinuationTokenNeedsQuotedLongCToken() {
    val token = "C" + "a".repeat(30)
    assertEquals(token, RemoteAlbum.extractGoogleContinuationToken("[null,\"$token\",1]"))
    assertEquals(token, RemoteAlbum.extractGoogleContinuationToken("['$token']"))
    assertNull(RemoteAlbum.extractGoogleContinuationToken("\"C" + "a".repeat(29) + "\""))
    assertNull(RemoteAlbum.extractGoogleContinuationToken(token))
    assertNull(RemoteAlbum.extractGoogleContinuationToken(""))
  }

  @Test fun googleAvatarDetection() {
    assertTrue(RemoteAlbum.isGoogleAvatarUrl("https://lh3.googleusercontent.com/a/ACg8abc=s64"))
    assertTrue(RemoteAlbum.isGoogleAvatarUrl("https://lh3.googleusercontent.com/a-/AOh14abc"))
    assertTrue(RemoteAlbum.isGoogleAvatarUrl("https://lh3.googleusercontent.com/pw/AP1Gcz=s48-c-mo"))
    assertFalse(RemoteAlbum.isGoogleAvatarUrl("https://lh3.googleusercontent.com/pw/AP1Gcz=w1920-h1080"))
    assertFalse(RemoteAlbum.isGoogleAvatarUrl("https://lh3.googleusercontent.com/pw/a/x=s48-c-mono"))
  }

  @Test fun synologyShareBuildsApiUrlUnderTheSharePath() {
    val share = RemoteAlbum.synologyShare("HTTPS://nas.example.invalid:5001/photo/mo/sharing/Tok3n/extra?x=1")!!
    assertEquals("https://nas.example.invalid:5001/photo/mo/sharing/webapi/entry.cgi", share.apiUrl)
    assertEquals("Tok3n", share.passphrase)
    // The route marker is matched case-insensitively; the share's own path casing is kept.
    assertEquals("http://nas.example.invalid/MO/SHARING/webapi/entry.cgi", RemoteAlbum.synologyShare("http://nas.example.invalid/MO/SHARING/t")!!.apiUrl)
    assertNull(RemoteAlbum.synologyShare("https://nas.example.invalid/mo/sharing/"))
    assertNull(RemoteAlbum.synologyShare("https://nas.example.invalid/mo/sharing//x"))
    assertNull(RemoteAlbum.synologyShare("https://nas.example.invalid/photo/share/t"))
    assertNull(RemoteAlbum.synologyShare("ftp://nas.example.invalid/mo/sharing/t"))
  }

  @Test fun synologyShareAcceptsInternationalisedHosts() {
    val share = RemoteAlbum.synologyShare("https://bücher.example/mo/sharing/tok")
    assertEquals("https://xn--bcher-kva.example/mo/sharing/webapi/entry.cgi", share?.apiUrl)
  }

  @Test fun synologySharingCookieTakesFirstNonEmptySession() {
    assertEquals("sharing_sid=abc", RemoteAlbum.synologySharingCookie(listOf(
        "other=1; Path=/", "sharing_sid=; Path=/", " sharing_sid=abc ; HttpOnly", "sharing_sid=def")))
    assertNull(RemoteAlbum.synologySharingCookie(listOf("sharing_sid=", "id=sharing_sid=x")))
    assertNull(RemoteAlbum.synologySharingCookie(emptyList()))
  }

  @Test fun synologyThumbnailUrlEncodesHostileValues() {
    val url = RemoteAlbum.synologyThumbnailUrl("https://nas.example.invalid/mo/sharing/webapi/entry.cgi", "p&q=\"x\"", 42, "k#1")
    val query = url.substringAfter('?')
    assertFalse(query.contains('#'))
    val params = query.split('&').associate { pair ->
      URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8")
    }
    assertEquals("42", params["id"])
    assertEquals("\"k#1\"", params["cache_key"])
    assertEquals(JSONObject.quote("p&q=\"x\""), params["passphrase"])
    assertEquals(JSONObject.quote("p&q=\"x\""), params["_sharing_id"])
    assertEquals("\"SYNO.Foto.Thumbnail\"", params["api"])
    assertEquals("2", params["version"])
    assertEquals(9, params.size)
  }
}
