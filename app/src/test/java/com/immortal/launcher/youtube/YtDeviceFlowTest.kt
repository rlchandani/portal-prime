/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class YtDeviceFlowTest {
  private val deviceCode =
      """{"device_code":"DEV-1","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}"""

  /** Answers device/code + channels; token polls are answered from [polls] in order. */
  private fun server(polls: List<YtHttpResponse>, observe: (YtHttpRequest) -> Unit = {}): FakeTransport {
    val queue = ArrayDeque(polls)
    return FakeTransport { req ->
      observe(req)
      when {
        req.url.startsWith(YtEndpoints.DEVICE_CODE) -> ok(deviceCode)
        req.url.startsWith(YtEndpoints.TOKEN) -> queue.removeFirst()
        req.url.contains("/channels") -> ok(channelIdentity("UC-ME", "Rohit"))
        else -> YtHttpResponse(404, "{}")
      }
    }
  }

  @Test fun happyPathShowsCodeThenPollsThenSignsInWithIdentity() = runBlocking {
    val clock = FakeClock()
    val seen = mutableListOf<YtSignInState>()
    lateinit var library: YouTubeMusicLibrary
    val transport = server(listOf(oauthError(428, "authorization_pending"), ok(tokenGrant("AT-1", "RT-1")))) {
      if (it.url.startsWith(YtEndpoints.TOKEN)) seen += library.signInState.value
    }
    library = newLibrary(transport, clock)

    val result = library.signIn()

    val account = (result as YtSignInState.SignedIn).account
    assertEquals(YtAccount("UC-ME", "Rohit", "https://yt3/UC-ME.jpg"), account)
    assertEquals(result, library.signInState.value)
    assertTrue(seen.first() is YtSignInState.ShowingCode)
    assertTrue(seen.last() is YtSignInState.Polling)
    val showing = seen.first() as YtSignInState.ShowingCode
    assertEquals("ABCD-EFGH", showing.userCode)
    assertEquals("https://www.google.com/device", showing.verificationUrl)
    assertEquals(clock.now - clock.delays.sum() + 1_800_000L, showing.expiresAtMs)
    assertEquals(listOf(account), library.accounts.value)
    assertEquals(account, library.activeAccount.value)
    val codeReq = transport.requests.first()
    assertEquals(YtEndpoints.YOUTUBE_READONLY, codeReq.formValue("scope"))
    assertEquals(TEST_CLIENT_ID, codeReq.formValue("client_id"))
    val poll = transport.requests.first { it.url.startsWith(YtEndpoints.TOKEN) }
    assertEquals("urn:ietf:params:oauth:grant-type:device_code", poll.formValue("grant_type"))
    assertEquals("DEV-1", poll.formValue("device_code"))
    assertEquals(TEST_CLIENT_SECRET, poll.formValue("client_secret"))
    val identity = transport.requests.last()
    assertEquals("Bearer AT-1", identity.headers["Authorization"])
    assertEquals("true", identity.param("mine"))
  }

  @Test fun honorsIntervalAndSlowDownAddsFiveSeconds() = runBlocking {
    val clock = FakeClock()
    val transport = server(listOf(
        oauthError(428, "authorization_pending"),
        oauthError(403, "slow_down"),
        oauthError(428, "authorization_pending"),
        ok(tokenGrant("AT-1", "RT-1"))))
    val result = newLibrary(transport, clock).signIn()
    assertTrue(result is YtSignInState.SignedIn)
    assertEquals(listOf(5_000L, 5_000L, 10_000L, 10_000L), clock.delays)
  }

  @Test fun accessDeniedEndsInDenied() = runBlocking {
    val transport = server(listOf(oauthError(428, "authorization_pending"), oauthError(403, "access_denied")))
    val library = newLibrary(transport)
    assertEquals(YtSignInState.Denied, library.signIn())
    assertTrue(library.accounts.value.isEmpty())
  }

  @Test fun expiredTokenEndsInExpired() = runBlocking {
    val transport = server(listOf(oauthError(400, "expired_token")))
    assertEquals(YtSignInState.Expired, newLibrary(transport).signIn())
  }

  @Test fun codeLifetimeRunningOutEndsInExpiredWithoutMorePolls() = runBlocking {
    val clock = FakeClock()
    val transport = server(List(1000) { oauthError(428, "authorization_pending") })
    assertEquals(YtSignInState.Expired, newLibrary(transport, clock).signIn())
    assertEquals(1_800_000L, clock.delays.sum())
    assertEquals(359, transport.count(YtEndpoints.TOKEN))
  }

  @Test fun missingClientIdIsNotConfiguredAndNeverTouchesTheNetwork() = runBlocking {
    val transport = server(emptyList())
    val library = newLibrary(transport, client = YtOAuthClient("", ""))
    assertFalse(library.isConfigured)
    assertEquals(YtSignInState.NotConfigured, library.signIn())
    assertTrue(transport.requests.isEmpty())
  }

  @Test fun offlineWhenRequestingCodeIsOffline() = runBlocking {
    val transport = server(emptyList()).apply { offline = true }
    assertEquals(YtSignInState.Offline, newLibrary(transport).signIn())
  }

  @Test fun resetReturnsToIdle() = runBlocking {
    val library = newLibrary(server(listOf(oauthError(403, "access_denied"))))
    library.signIn()
    library.resetSignIn()
    assertEquals(YtSignInState.Idle, library.signInState.value)
  }

  @Test fun reSigningTheSameChannelReplacesTokenAndClearsNeedsSignIn() = runBlocking {
    val secure = MemorySecureStore(storedAccounts("UC-ME" to "RT-OLD"))
    val marked = YtAccountCodec.decode(secure.text!!)!!.let { snap ->
      snap.copy(accounts = snap.accounts.map { it.copy(account = it.account.copy(needsSignIn = true)) })
    }
    secure.text = YtAccountCodec.encode(marked)
    val transport = server(listOf(ok(tokenGrant("AT-1", "RT-NEW"))))
    val library = newLibrary(transport, secure = secure)
    library.signIn()
    assertEquals(1, library.accounts.value.size)
    assertFalse(library.accounts.value.single().needsSignIn)
    assertTrue(secure.text!!.contains("RT-NEW"))
    assertFalse(secure.text!!.contains("RT-OLD"))
  }
}
