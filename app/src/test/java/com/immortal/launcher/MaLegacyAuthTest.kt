/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Scripted replies only; no real server, account, provider credential, or device is used. */
class MaLegacyAuthTest {
  private fun reply(request: JSONObject, result: Any?, id: String = request.getString("message_id")) =
      JSONObject().put("message_id", id).put("result", result ?: JSONObject.NULL).toString()

  private fun failure(reply: MaMusicProtocol.Reply<Unit>): MaMusicProtocol.Reply.Failure {
    assertTrue(reply is MaMusicProtocol.Reply.Failure)
    return reply as MaMusicProtocol.Reply.Failure
  }

  @Test fun configuredApiTokenTakesPrecedenceAndRequiresAuthOnEverySocket() {
    val auth = MaLegacyAuth()
    val ids = HashSet<String>()
    for (token in listOf("fixture-api-a", "fixture-api-b", "fixture-api-a")) {
      var exchanged = false
      val result = auth.authorize(token, "legacy-user", "legacy-password") { request ->
        exchanged = true
        assertEquals("auth", request.getString("command"))
        assertTrue(ids.add(request.getString("message_id")))
        val args = request.getJSONObject("args")
        assertEquals(token, args.getString("token"))
        assertFalse(args.has("username"))
        assertFalse(args.has("password"))
        reply(request, JSONObject().put("authenticated", true))
      }
      assertTrue(exchanged)
      assertTrue(result is MaMusicProtocol.Reply.Success)
    }
  }

  @Test fun blankCredentialsAreAuthRequiredAndNeverSendAnything() {
    val auth = MaLegacyAuth()
    for (token in listOf("", " ")) {
      var exchanges = 0
      val result = auth.authorize(token, "", "") { exchanges++; "{}" }
      assertEquals(MusicConnection.AUTH_REQUIRED, failure(result).connection)
      assertEquals(0, exchanges)
      assertNotNull(auth.configurationFailure(token, "", ""))
    }
  }

  @Test fun unsupportedLegacyLoginCannotReusePriorAuthorizedToken() {
    val auth = MaLegacyAuth()
    assertTrue(auth.authorize("fixture-api", "user-a", "password-a") {
      reply(it, JSONObject().put("authenticated", true))
    } is MaMusicProtocol.Reply.Success)
    for ((user, password) in listOf("user-a" to "password-a", "user-b" to "password-b",
        "user-a" to "changed-password", "user-a" to "", "" to "password-a")) {
      var exchanges = 0
      val result = auth.authorize("", user, password) { exchanges++; "{}" }
      assertEquals(MusicConnection.AUTH_REQUIRED, failure(result).connection)
      assertTrue(failure(result).problem.contains("unsupported"))
      assertEquals(0, exchanges)
    }
  }

  @Test fun onlyExplicitBooleanAuthenticatedTrueCanSucceed() {
    val auth = MaLegacyAuth()
    for (value in listOf<Any>(JSONObject().put("authenticated", false),
        JSONObject().put("authenticated", "true"), JSONObject().put("success", true),
        JSONObject.NULL, JSONObject())) {
      failure(auth.authorize("fixture-api", "", "") { reply(it, value) })
    }
    failure(auth.authorize("fixture-api", "", "") {
      reply(it, JSONObject().put("authenticated", true), id = "another-request")
    })
    failure(auth.authorize("fixture-api", "", "") {
      JSONObject(reply(it, JSONObject().put("authenticated", true))).put("partial", true).toString()
    })
    failure(auth.authorize("fixture-api", "", "") { """{"server_version":"2.10.7","schema_version":65}""" })
  }

  @Test fun serverErrorOverridesApparentSuccessAndNeverExposesServerDetails() {
    val auth = MaLegacyAuth()
    val result = auth.authorize("fixture-api", "", "") { request ->
      JSONObject(reply(request, JSONObject().put("authenticated", true)))
          .put("error_code", 20).put("details", "private-fixture-detail").toString()
    }
    assertEquals(MusicConnection.AUTH_REQUIRED, failure(result).connection)
    assertFalse(result.toString().contains("private-fixture-detail"))
  }

  @Test fun timeoutTransportFailureAndMalformedReplyNeverExposeExceptionOrResponse() {
    val auth = MaLegacyAuth()
    assertEquals(MusicConnection.OFFLINE,
        failure(auth.authorize("fixture-api", "", "") { null }).connection)
    val exception = auth.authorize("fixture-api", "", "") { throw IOException("private-fixture-detail") }
    assertEquals(MusicConnection.OFFLINE, failure(exception).connection)
    assertFalse(exception.toString().contains("private-fixture-detail"))
    val malformed = auth.authorize("fixture-api", "", "") { "private-fixture-detail" }
    assertEquals(MusicConnection.INCOMPATIBLE, failure(malformed).connection)
    assertFalse(malformed.toString().contains("private-fixture-detail"))
  }

  @Test fun previouslyAuthenticatedSocketCannotAuthorizeNewSocketWithoutItsOwnSuccess() {
    val auth = MaLegacyAuth()
    assertTrue(auth.authorize("fixture-api", "", "") { reply(it, JSONObject().put("authenticated", true)) }
        is MaMusicProtocol.Reply.Success)
    val revoked = auth.authorize("fixture-api", "", "") { reply(it, JSONObject().put("authenticated", false)) }
    assertEquals(MusicConnection.AUTH_REQUIRED, failure(revoked).connection)
  }

  @Test fun invalidTokensFailBeforeExchangeEvenWithLegacyCredentials() {
    val auth = MaLegacyAuth()
    for (token in listOf("x".repeat(8193), "fixture\napi", "fixture\u0000api")) {
      var exchanges = 0
      val result = auth.authorize(token, "legacy-user", "legacy-password") { exchanges++; "{}" }
      assertEquals(MusicConnection.AUTH_REQUIRED, failure(result).connection)
      assertEquals(0, exchanges)
    }
  }
}
