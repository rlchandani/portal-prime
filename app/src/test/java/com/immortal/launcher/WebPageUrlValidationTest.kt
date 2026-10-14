/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebPageUrlValidationTest {
  private val oldUrl = "https://fixture.invalid/saved/?keep=%2F#panel"
  private fun stored() = ScreensaverConfig.Settings(source = ScreensaverConfig.SOURCE_WEBURL, webUrl = oldUrl)

  @Test fun missingHostAndRelativeAddressesAreRejected() {
    listOf("", " ", "http://", "https://", "https:///photos", "https://?view=1",
        "https://#photos", "http:dashboard", "//dashboard/photos", "dashboard.local/photos")
        .forEach { assertNull(it, WebPageUrl.normalize(it)) }
  }
  @Test fun onlyHttpAndHttpsAreAccepted() {
    listOf("file:///sdcard/photos", "javascript:alert(1)", "ftp://photos.local/", "data:text/html,frame")
        .forEach { assertNull(it, WebPageUrl.normalize(it)) }
  }
  @Test fun malformedPortsAndIpv6AreRejected() {
    listOf("http://photos.local:bad/", "http://photos.local:-1/", "http://photos.local:65536/",
        "http://[not-an-ip]/", "http://[2001:db8::1/photos", "http://:8123/photos")
        .forEach { assertNull(it, WebPageUrl.normalize(it)) }
  }
  @Test fun malformedEscapesAndUnescapedSpacesAreRejected() {
    listOf("https://bad host/photos", "https://photos.local/living room", "https://photos.local/%",
        "https://photos.local/%GG", "http://bad%20host/photos", "http://photos.local\\other/")
        .forEach { assertNull(it, WebPageUrl.normalize(it)) }
  }
  @Test fun controlCharactersAreRejectedBeforeTrimming() {
    for (control in listOf('\n', '\r', '\t', '\u0000', '\u007f')) {
      assertNull(WebPageUrl.normalize("${control}https://photos.local/"))
      assertNull(WebPageUrl.normalize("https://photos.local/view${control}extra"))
    }
  }
  @Test fun lanNamesIpv4AndValidPortsRemainCompatible() {
    listOf("http://photos/", "http://photos.local:8123/dashboard/", "http://192.0.2.10:2283/",
        "https://photos.local:65535/", "http://photos_server:8080/", "HTTPS://PHOTOS.local/frame")
        .forEach { assertEquals(it, WebPageUrl.normalize(it)) }
  }
  @Test fun ipv6AndScopedIpv6RemainCompatible() {
    listOf("http://[2001:db8::1]:8123/frame", "http://[fe80::1%25wlan0]:8080/frame")
        .forEach { assertEquals(it, WebPageUrl.normalize(it)) }
  }
  @Test fun unicodeHostsAndPathsRemainCompatible() {
    listOf("https://bücher.example/照片", "https://xn--bcher-kva.example/照片")
        .forEach { assertEquals(it, WebPageUrl.normalize(it)) }
  }
  @Test fun signedQueriesFragmentsCredentialsAndPathRemainUnmodified() {
    val url = "https://reader:fixture%40pass@photos.local:8443/a/../frame/?token=%252F%26%3D&label=two+words#view"
    assertEquals(url, WebPageUrl.normalize(url))
  }
  @Test fun outerSpacesAreTrimmedWithoutRewritingUrl() {
    assertEquals(oldUrl, WebPageUrl.normalize("  $oldUrl  "))
  }
  @Test fun invalidSetterPreservesWorkingSourceAndPerformsZeroWrites() {
    val prefs = ScreensaverTestPrefs(mapOf("source" to ScreensaverConfig.SOURCE_WEBURL, "web_url" to oldUrl))
    val before = ScreensaverConfig.load(prefs.context)
    listOf("https://", "file:///photos", "https://photos.local:70000/").forEach {
      ScreensaverConfig.setWebUrl(prefs.context, it)
      assertEquals(before, ScreensaverConfig.load(prefs.context))
    }
    assertEquals(0, prefs.writeCount)
  }
  @Test fun validSetterCommitsNormalizedAddressAndSourceTogether() {
    val prefs = ScreensaverTestPrefs(mapOf("source" to ScreensaverConfig.SOURCE_DEFAULT))
    ScreensaverConfig.setWebUrl(prefs.context, "  $oldUrl  ")
    val saved = ScreensaverConfig.load(prefs.context)
    assertEquals(oldUrl, saved.webUrl)
    assertEquals(ScreensaverConfig.SOURCE_WEBURL, saved.source)
    assertEquals(1, prefs.writeCount)
  }
  @Test fun invalidFleetValuesCannotReplaceExistingSource() {
    for (value in listOf<Any>("https://", true, 42, JSONObject.NULL, JSONObject(), JSONArray())) {
      val prefs = ScreensaverTestPrefs(mapOf("source" to ScreensaverConfig.SOURCE_WEBURL, "web_url" to oldUrl))
      val before = ScreensaverConfig.load(prefs.context)
      assertFalse(FleetScreensaver.apply(prefs.context, JSONObject().put("webUrl", value)).contains("webUrl"))
      assertEquals(before, ScreensaverConfig.load(prefs.context))
      assertEquals(0, prefs.writeCount)
    }
  }
  @Test fun validFleetAddressUsesSharedNormalization() {
    val prefs = ScreensaverTestPrefs()
    assertTrue(FleetScreensaver.apply(prefs.context, JSONObject().put("webUrl", "  $oldUrl  ")).contains("webUrl"))
    assertEquals(oldUrl, ScreensaverConfig.load(prefs.context).webUrl)
  }
  @Test fun remoteRejectsMalformedAddressAndNonTextBeforePreparingAnyWrite() {
    for (value in listOf<Any>("https://", "http://:8080/", true, 42, JSONObject.NULL, JSONObject(), JSONArray())) {
      val result = RemoteCompanionSources.prepare(JSONObject().put("webUrl", value), stored())
      assertNull(result.body)
      assertEquals(if (value is String) "invalid_web_url" else "invalid_source_field", result.error)
    }
  }
  @Test fun remoteCopiesAndNormalizesValidDraftWithoutMutatingInput() {
    val input = JSONObject().put("webUrl", "  $oldUrl  ")
    val result = RemoteCompanionSources.prepare(input, stored())
    assertNull(result.error)
    assertEquals(oldUrl, result.body!!.getString("webUrl"))
    assertEquals("  $oldUrl  ", input.getString("webUrl"))
  }
  @Test fun remotePartialUpdatesDoNotValidateOrReplaceAnUnrelatedSavedWebAddress() {
    val result = RemoteCompanionSources.prepare(JSONObject().put("source", "default"), stored().copy(webUrl = "https://"))
    assertNull(result.error)
    assertFalse(result.body!!.has("webUrl"))
    assertEquals("default", result.body!!.getString("source"))
  }
}
