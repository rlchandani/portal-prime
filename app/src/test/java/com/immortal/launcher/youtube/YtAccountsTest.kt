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

class YtAccountsTest {
  private val playlistsBody = """{"items":[{"id":"PL1","snippet":{"title":"Road"},"contentDetails":{"itemCount":3}}]}"""

  @Test fun refreshesAccessTokenOnceOn401AndRetries() = runBlocking {
    var tokenCalls = 0
    val transport = FakeTransport { req ->
      when {
        req.url.startsWith(YtEndpoints.TOKEN) -> ok(tokenGrant("AT-${++tokenCalls}"))
        req.headers["Authorization"] == "Bearer AT-1" -> YtHttpResponse(401, "{}")
        else -> ok(playlistsBody)
      }
    }
    val library = newLibrary(transport, secure = MemorySecureStore(storedAccounts("UC-A" to "RT-A")))
    val result = library.playlists().last()
    assertEquals("PL1", ((result as YtResult.Ok).value.value.single()).id)
    assertEquals(2, tokenCalls)
    val refresh = transport.requests.first()
    assertEquals("refresh_token", refresh.formValue("grant_type"))
    assertEquals("RT-A", refresh.formValue("refresh_token"))
  }

  @Test fun persistent401AfterRefreshIsNeedsSignInWithoutLooping() = runBlocking {
    val transport = FakeTransport { req ->
      if (req.url.startsWith(YtEndpoints.TOKEN)) ok(tokenGrant("AT")) else YtHttpResponse(401, "{}")
    }
    val library = newLibrary(transport, secure = MemorySecureStore(storedAccounts("UC-A" to "RT-A")))
    assertEquals(YtResult.NeedsSignIn, library.playlists().last())
    assertEquals(2, transport.count("/playlists"))
  }

  @Test fun accessTokenIsReusedUntilItExpires() = runBlocking {
    val clock = FakeClock()
    val transport = FakeTransport { req ->
      if (req.url.startsWith(YtEndpoints.TOKEN)) ok(tokenGrant("AT", expiresIn = 3600)) else ok(playlistsBody)
    }
    val library = newLibrary(transport, clock, MemorySecureStore(storedAccounts("UC-A" to "RT-A")))
    library.playlists().last()
    clock.now += 11 * 60_000L
    library.playlists().last()
    assertEquals(1, transport.count(YtEndpoints.TOKEN))
    clock.now += 60 * 60_000L
    library.playlists().last()
    assertEquals(2, transport.count(YtEndpoints.TOKEN))
  }

  @Test fun invalidGrantMarksAccountNeedsSignInAndPersistsIt() = runBlocking {
    val secure = MemorySecureStore(storedAccounts("UC-A" to "RT-A"))
    val transport = FakeTransport { oauthError(400, "invalid_grant") }
    val library = newLibrary(transport, secure = secure)
    assertEquals(YtResult.NeedsSignIn, library.playlists().last())
    assertTrue(library.accounts.value.single().needsSignIn)
    assertTrue(library.activeAccount.value!!.needsSignIn)
    val reloaded = newLibrary(FakeTransport(), secure = secure)
    assertTrue(reloaded.accounts.value.single().needsSignIn)
    // A marked account doesn't keep hammering /token.
    library.playlists().last()
    assertEquals(1, transport.count(YtEndpoints.TOKEN))
  }

  @Test fun offlineRefreshIsOfflineAndDoesNotMarkAccount() = runBlocking {
    val transport = FakeTransport().apply { offline = true }
    val library = newLibrary(transport, secure = MemorySecureStore(storedAccounts("UC-A" to "RT-A")))
    assertEquals(YtResult.Offline, library.playlists().last())
    assertFalse(library.accounts.value.single().needsSignIn)
  }

  @Test fun noAccountMeansNeedsSignIn() = runBlocking {
    assertEquals(YtResult.NeedsSignIn, newLibrary(FakeTransport()).playlists().last())
  }

  @Test fun signOutRevokesThenDeletesLocally() = runBlocking {
    val secure = MemorySecureStore(storedAccounts("UC-A" to "RT-A", "UC-B" to "RT-B"))
    val cache = MemoryCacheStore().apply { write("a.UC-A.playlists", "x"); write("a.UC-B.playlists", "y") }
    val transport = FakeTransport { ok("{}") }
    val library = newLibrary(transport, secure = secure, cache = cache)
    assertTrue(library.signOut("UC-A"))
    val revoke = transport.requests.single()
    assertTrue(revoke.url.startsWith(YtEndpoints.REVOKE))
    assertEquals("POST", revoke.method)
    assertEquals("RT-A", revoke.formValue("token"))
    assertFalse("token never in the URL", revoke.url.contains("RT-A"))
    assertEquals(listOf("UC-B"), library.accounts.value.map { it.id })
    assertEquals("UC-B", library.activeAccount.value?.id)
    assertFalse(secure.text!!.contains("RT-A"))
    assertNull(cache.read("a.UC-A.playlists"))
    assertEquals("y", cache.read("a.UC-B.playlists"))
  }

  @Test fun signOutWhileOfflineStillDeletesLocally() = runBlocking {
    val secure = MemorySecureStore(storedAccounts("UC-A" to "RT-A"))
    val library = newLibrary(FakeTransport().apply { offline = true }, secure = secure)
    assertFalse("revoke didn't reach Google", library.signOut("UC-A"))
    assertTrue(library.accounts.value.isEmpty())
    assertNull(library.activeAccount.value)
    assertFalse(secure.text.orEmpty().contains("RT-A"))
  }

  @Test fun switchingAccountIsPersistedAndUsedForRequests() = runBlocking {
    val secure = MemorySecureStore(storedAccounts("UC-A" to "RT-A", "UC-B" to "RT-B"))
    val transport = FakeTransport { req ->
      if (req.url.startsWith(YtEndpoints.TOKEN)) ok(tokenGrant("AT-for-" + req.formValue("refresh_token")))
      else ok(playlistsBody)
    }
    val library = newLibrary(transport, secure = secure)
    assertEquals("UC-A", library.activeAccount.value?.id)
    assertTrue(library.switchAccount("UC-B"))
    assertFalse(library.switchAccount("UC-NOPE"))
    assertEquals("UC-B", library.activeAccount.value?.id)
    assertEquals("UC-B", newLibrary(FakeTransport(), secure = secure).activeAccount.value?.id)
    library.playlists().last()
    assertEquals("Bearer AT-for-RT-B", transport.requests.last().headers["Authorization"])
  }

  @Test fun corruptOrUnreadableStoreStartsEmptyInsteadOfThrowing() {
    val library = newLibrary(FakeTransport(), secure = MemorySecureStore("{not json"))
    assertTrue(library.accounts.value.isEmpty())
    val throwing = object : YtSecureStore {
      override fun load(): String = throw IllegalStateException("keystore gone")
      override fun save(text: String) = Unit
      override fun clear() = Unit
    }
    val other = YouTubeMusicLibrary(testClient, FakeTransport(), throwing, MemoryCacheStore(), FakeClock())
    assertTrue(other.accounts.value.isEmpty())
  }
}
