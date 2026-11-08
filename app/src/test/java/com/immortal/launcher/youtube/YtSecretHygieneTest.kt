/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Tokens and the client secret must never surface in strings a log line or crash report could carry. */
class YtSecretHygieneTest {
  private val secrets = listOf("RT-SECRET-1", "AT-SECRET-1", "DEV-SECRET-1", TEST_CLIENT_SECRET)

  private fun assertClean(text: String?) {
    for (secret in secrets) assertFalse("leaked $secret in: $text", text.orEmpty().contains(secret))
  }

  @Test fun valueTypesRedactTheirToString() {
    assertClean(YtHttpRequest("POST", YtEndpoints.TOKEN, mapOf("Authorization" to "Bearer AT-SECRET-1"),
        mapOf("refresh_token" to "RT-SECRET-1", "client_secret" to TEST_CLIENT_SECRET)).toString())
    assertClean(YtHttpResponse(200, tokenGrant("AT-SECRET-1", "RT-SECRET-1")).toString())
    assertClean(testClient.toString())
    assertClean(YtStoredAccount(YtAccount("UC", "Me", null), "RT-SECRET-1").toString())
    assertClean(YtTokenGrant("AT-SECRET-1", 3600, "RT-SECRET-1").toString())
    assertClean(YtAccountsSnapshot(listOf(YtStoredAccount(YtAccount("UC", "Me", null), "RT-SECRET-1")), "UC").toString())
  }

  @Test fun transportFailuresCarryingTokensNeverEscapeAsExceptionsOrStates() = runBlocking {
    val transport = FakeTransport().apply {
      offline = true
      offlineMessage = "failed POST token=RT-SECRET-1 Bearer AT-SECRET-1"
    }
    val library = newLibrary(transport, secure = MemorySecureStore(storedAccounts("UC-A" to "RT-SECRET-1")))
    val results = listOf(library.playlists().last(), library.search("daft punk"), library.signIn())
    results.forEach { assertClean(it.toString()) }
    assertClean(library.signInState.value.toString())
    assertClean(library.accounts.value.toString())
  }

  @Test fun fullFlowNeverPutsSecretsInStatesCacheOrUrls() = runBlocking {
    val cache = MemoryCacheStore()
    val transport = FakeTransport { req ->
      when {
        req.url.startsWith(YtEndpoints.DEVICE_CODE) ->
          ok("""{"device_code":"DEV-SECRET-1","user_code":"ABCD","verification_url":"https://www.google.com/device","expires_in":600,"interval":5}""")
        req.url.startsWith(YtEndpoints.TOKEN) -> ok(tokenGrant("AT-SECRET-1", "RT-SECRET-1"))
        req.url.contains("/channels") -> ok(channelIdentity("UC-ME", "Me"))
        else -> ok("""{"items":[{"id":"PL1","snippet":{"title":"Road"}}]}""")
      }
    }
    val library = newLibrary(transport, cache = cache)
    assertClean(library.signIn().toString())
    assertClean(library.playlists().last().toString())
    assertClean(library.search("road trip").toString())
    cache.entries.forEach { (k, v) -> assertClean(k); assertClean(v) }
    transport.requests.forEach { assertClean(it.url) }
  }

  @Test fun unexpectedTransportExceptionsBecomeTypedFailuresWithoutMessages() = runBlocking {
    val transport = FakeTransport { throw IllegalStateException("boom Bearer AT-SECRET-1") }
    val library = newLibrary(transport, secure = MemorySecureStore(storedAccounts("UC-A" to "RT-SECRET-1")))
    val result = library.playlists().last()
    assertTrue(result is YtResult.Failed)
    assertClean(result.toString())
  }
}
