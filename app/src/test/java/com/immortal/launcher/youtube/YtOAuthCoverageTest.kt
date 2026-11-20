/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device-flow, refresh and revoke parsing against hostile or odd OAuth server replies. */
class YtOAuthCoverageTest {
  private fun api(handler: (YtHttpRequest) -> YtHttpResponse) = YtOAuthApi(FakeTransport(handler), testClient)

  private fun offline() = YtOAuthApi(FakeTransport().apply { offline = true }, testClient)

  private fun crashing() = YtOAuthApi(YtHttpTransport { throw IllegalStateException("boom") }, testClient)

  // --- requestDeviceCode ---

  @Test fun deviceCodeSendsOnlyClientIdAndScope() = runBlocking {
    val transport = FakeTransport { ok("{}") }
    YtOAuthApi(transport, testClient).requestDeviceCode()
    val req = transport.requests.single()
    assertEquals(YtEndpoints.DEVICE_CODE, req.url)
    assertEquals("POST", req.method)
    assertEquals(TEST_CLIENT_ID, req.formValue("client_id"))
    assertEquals(YtEndpoints.YOUTUBE_READONLY, req.formValue("scope"))
    assertNull("secret is not sent for the device code", req.formValue("client_secret"))
  }

  @Test fun deviceCodeOfflineAndCrashMapToTypedOutcomes() = runBlocking {
    assertEquals(YtDeviceCodeOutcome.Offline, offline().requestDeviceCode())
    assertEquals(YtDeviceCodeOutcome.Failed(0), crashing().requestDeviceCode())
  }

  @Test fun deviceCodeNon2xxOrNonJsonFails() = runBlocking {
    val full = """{"device_code":"D","user_code":"U","verification_url":"https://g.example.invalid/device"}"""
    assertEquals(YtDeviceCodeOutcome.Failed(500), api { YtHttpResponse(500, full) }.requestDeviceCode())
    assertEquals(YtDeviceCodeOutcome.Failed(200), api { ok("<html>nope</html>") }.requestDeviceCode())
    assertEquals(YtDeviceCodeOutcome.Failed(200), api { ok("{broken") }.requestDeviceCode())
    assertEquals(YtDeviceCodeOutcome.Failed(199), api { YtHttpResponse(199, full) }.requestDeviceCode())
    assertEquals(YtDeviceCodeOutcome.Failed(300), api { YtHttpResponse(300, full) }.requestDeviceCode())
  }

  @Test fun deviceCodeMissingAnyRequiredFieldFails() = runBlocking {
    val missingDevice = """{"user_code":"U","verification_url":"https://x.example.invalid"}"""
    val missingUser = """{"device_code":"D","verification_url":"https://x.example.invalid"}"""
    val missingUrl = """{"device_code":"D","user_code":"U"}"""
    for (body in listOf(missingDevice, missingUser, missingUrl)) {
      assertEquals(body, YtDeviceCodeOutcome.Failed(200), api { ok(body) }.requestDeviceCode())
    }
  }

  @Test fun deviceCodeAcceptsVerificationUriAndAppliesDefaults() = runBlocking {
    val body = """{"device_code":"D","user_code":"U","verification_uri":"https://alt.example.invalid"}"""
    val issued = api { ok(body) }.requestDeviceCode() as YtDeviceCodeOutcome.Issued
    assertEquals("https://alt.example.invalid", issued.code.verificationUrl)
    assertEquals(1800L, issued.code.expiresInSec)
    assertEquals(5L, issued.code.intervalSec)
    assertEquals("D", issued.code.deviceCode)
    assertFalse("device code is a credential", issued.code.toString().contains("D,"))
    assertTrue(issued.code.toString().contains("userCode=U"))
  }

  @Test fun deviceCodePrefersVerificationUrlAndClampsIntervalToOneSecond() = runBlocking {
    val body = """{"device_code":"D","user_code":"U","verification_url":"https://a.example.invalid",
      "verification_uri":"https://b.example.invalid","expires_in":60,"interval":0}"""
    val issued = api { ok(body) }.requestDeviceCode() as YtDeviceCodeOutcome.Issued
    assertEquals("https://a.example.invalid", issued.code.verificationUrl)
    assertEquals(60L, issued.code.expiresInSec)
    assertEquals(1L, issued.code.intervalSec)
    val negative = api { ok(body.replace("\"interval\":0", "\"interval\":-9")) }.requestDeviceCode()
    assertEquals(1L, (negative as YtDeviceCodeOutcome.Issued).code.intervalSec)
  }

  // --- pollToken ---

  @Test fun pollSendsCredentialsDeviceCodeAndGrantType() = runBlocking {
    val transport = FakeTransport { ok(tokenGrant("AT")) }
    YtOAuthApi(transport, testClient).pollToken("DEV-9")
    val req = transport.requests.single()
    assertEquals(YtEndpoints.TOKEN, req.url)
    assertEquals(TEST_CLIENT_SECRET, req.formValue("client_secret"))
    assertEquals("DEV-9", req.formValue("device_code"))
    assertEquals(YtEndpoints.DEVICE_GRANT, req.formValue("grant_type"))
  }

  @Test fun pollMapsEveryOAuthErrorCode() = runBlocking {
    assertEquals(YtTokenPoll.Pending, api { oauthError(428, "authorization_pending") }.pollToken("d"))
    assertEquals(YtTokenPoll.SlowDown, api { oauthError(428, "slow_down") }.pollToken("d"))
    assertEquals(YtTokenPoll.Denied, api { oauthError(403, "access_denied") }.pollToken("d"))
    assertEquals(YtTokenPoll.Expired, api { oauthError(400, "expired_token") }.pollToken("d"))
    assertEquals(YtTokenPoll.Failed(400), api { oauthError(400, "invalid_client") }.pollToken("d"))
    assertEquals(YtTokenPoll.Failed(400), api { oauthError(400, "") }.pollToken("d"))
    assertEquals(YtTokenPoll.Failed(502), api { YtHttpResponse(502, "Bad gateway") }.pollToken("d"))
    assertEquals(YtTokenPoll.Failed(400), api { YtHttpResponse(400, "{}") }.pollToken("d"))
  }

  @Test fun pollOfflineAndCrash() = runBlocking {
    assertEquals(YtTokenPoll.Offline, offline().pollToken("d"))
    assertEquals(YtTokenPoll.Failed(0), crashing().pollToken("d"))
  }

  @Test fun pollSuccessWithoutAccessTokenFails() = runBlocking {
    assertEquals(YtTokenPoll.Failed(200), api { ok("""{"refresh_token":"R"}""") }.pollToken("d"))
    assertEquals(YtTokenPoll.Failed(200), api { ok("""{"access_token":""}""") }.pollToken("d"))
    assertEquals(YtTokenPoll.Failed(200), api { ok("not json") }.pollToken("d"))
  }

  @Test fun pollGrantUsesDefaultExpiryAndOptionalRefresh() = runBlocking {
    val granted = api { ok("""{"access_token":"AT","refresh_token":""}""") }.pollToken("d") as YtTokenPoll.Granted
    assertEquals("AT", granted.grant.accessToken)
    assertEquals(3600L, granted.grant.expiresInSec)
    assertNull("empty refresh token is absent", granted.grant.refreshToken)
    val withRefresh = api { ok(tokenGrant("AT", "RT", 99)) }.pollToken("d") as YtTokenPoll.Granted
    assertEquals("RT", withRefresh.grant.refreshToken)
    assertEquals(99L, withRefresh.grant.expiresInSec)
    val text = withRefresh.grant.toString()
    assertFalse("redacted", text.contains("AT") || text.contains("RT"))
    assertTrue(text.contains("hasRefresh=true"))
  }

  // --- refresh ---

  @Test fun refreshSendsRefreshGrant() = runBlocking {
    val transport = FakeTransport { ok(tokenGrant("AT2")) }
    val out = YtOAuthApi(transport, testClient).refresh("RT-1")
    assertEquals("AT2", (out as YtRefreshOutcome.Refreshed).grant.accessToken)
    val req = transport.requests.single()
    assertEquals("RT-1", req.formValue("refresh_token"))
    assertEquals("refresh_token", req.formValue("grant_type"))
    assertEquals(TEST_CLIENT_ID, req.formValue("client_id"))
  }

  @Test fun refreshOutcomes() = runBlocking {
    assertEquals(YtRefreshOutcome.InvalidGrant, api { oauthError(400, "invalid_grant") }.refresh("r"))
    assertEquals(YtRefreshOutcome.Failed(401), api { oauthError(401, "invalid_client") }.refresh("r"))
    assertEquals(YtRefreshOutcome.Failed(500), api { YtHttpResponse(500, "") }.refresh("r"))
    assertEquals(YtRefreshOutcome.Failed(200), api { ok("{}") }.refresh("r"))
    assertEquals(YtRefreshOutcome.Offline, offline().refresh("r"))
    assertEquals(YtRefreshOutcome.Failed(0), crashing().refresh("r"))
  }

  // --- revoke ---

  @Test fun revokeSendsTokenInBodyNotUrl() = runBlocking {
    val transport = FakeTransport { ok("") }
    assertTrue(YtOAuthApi(transport, testClient).revoke("TOKEN-X"))
    val req = transport.requests.single()
    assertEquals(YtEndpoints.REVOKE, req.url)
    assertFalse(req.url.contains("TOKEN-X"))
    assertEquals("TOKEN-X", req.formValue("token"))
  }

  @Test fun revokeTreatsAlreadyInvalidAsSuccessAndOtherFailuresAsFalse() = runBlocking {
    assertTrue(api { oauthError(400, "invalid_token") }.revoke("t"))
    assertFalse(api { oauthError(400, "invalid_request") }.revoke("t"))
    assertFalse(api { YtHttpResponse(503, "down") }.revoke("t"))
    assertFalse(offline().revoke("t"))
    assertFalse(crashing().revoke("t"))
  }

  // --- parseJson ---

  @Test fun parseJsonAcceptsOnlyObjects() {
    assertEquals("v", parseJson("""  {"k":"v"}""")!!.getString("k"))
    assertNull(parseJson("[1,2]"))
    assertNull(parseJson(""))
    assertNull(parseJson("{not json"))
    assertNull(parseJson("null"))
  }
}
