/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import java.io.IOException
import java.net.URLDecoder
import kotlin.coroutines.EmptyCoroutineContext

/** Mutable test clock; [delays] records every requested wait (the fake delay advances the clock). */
internal class FakeClock(var now: Long = 1_800_000_000_000L) : YtClock {
  val delays = mutableListOf<Long>()
  override fun nowMs(): Long = now
  suspend fun delay(ms: Long) {
    delays += ms
    now += ms
  }
}

/** Fake HTTP transport: records requests and answers through [handler]. Set [offline] to throw. */
internal class FakeTransport(var handler: (YtHttpRequest) -> YtHttpResponse = { YtHttpResponse(404, "{}") }) :
    YtHttpTransport {
  val requests = mutableListOf<YtHttpRequest>()
  var offline = false
  var offlineMessage = "unreachable"

  override suspend fun execute(request: YtHttpRequest): YtHttpResponse {
    if (offline) throw IOException(offlineMessage)
    requests += request
    return handler(request)
  }

  fun count(pathPart: String): Int = requests.count { it.url.contains(pathPart) }
}

internal class MemorySecureStore(var text: String? = null) : YtSecureStore {
  var saves = 0
  override fun load(): String? = text
  override fun save(text: String) {
    saves++
    this.text = text
  }
  override fun clear() {
    text = null
  }
}

internal class MemoryCacheStore : YtCacheStore {
  val entries = linkedMapOf<String, String>()
  override fun read(key: String): String? = entries[key]
  override fun write(key: String, text: String) {
    entries[key] = text
  }
  override fun delete(key: String) {
    entries.remove(key)
  }
  override fun deletePrefix(prefix: String) {
    entries.keys.filter { it.startsWith(prefix) }.forEach(entries::remove)
  }
}

internal fun YtHttpRequest.param(name: String): String? = query(url.substringAfter('?', ""))[name]

internal fun YtHttpRequest.formValue(name: String): String? = form?.get(name)

private fun query(text: String): Map<String, String> =
    text.split('&').filter { it.contains('=') }.associate {
      URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

internal fun ok(body: String) = YtHttpResponse(200, body)

internal const val TEST_CLIENT_ID = "client-123.apps.googleusercontent.com"
internal const val TEST_CLIENT_SECRET = "GOCSPX-secret-value"
internal val testClient = YtOAuthClient(TEST_CLIENT_ID, TEST_CLIENT_SECRET)

internal fun channelIdentity(id: String, title: String) =
    """{"items":[{"id":"$id","snippet":{"title":"$title","thumbnails":{"default":{"url":"https://yt3/$id.jpg"}}}}]}"""

internal fun tokenGrant(access: String, refresh: String? = null, expiresIn: Int = 3600): String =
    if (refresh == null) """{"access_token":"$access","expires_in":$expiresIn}"""
    else """{"access_token":"$access","expires_in":$expiresIn,"refresh_token":"$refresh"}"""

internal fun oauthError(code: Int, error: String) = YtHttpResponse(code, """{"error":"$error"}""")

/** A secure-store payload holding the given (id, refreshToken) accounts, first one active. */
internal fun storedAccounts(vararg accounts: Pair<String, String>): String {
  val list = accounts.map { (id, token) -> YtStoredAccount(YtAccount(id, "Name $id", null), token) }
  return YtAccountCodec.encode(YtAccountsSnapshot(list, list.firstOrNull()?.account?.id))
}

internal fun newLibrary(
    transport: FakeTransport,
    clock: FakeClock = FakeClock(),
    secure: MemorySecureStore = MemorySecureStore(),
    cache: MemoryCacheStore = MemoryCacheStore(),
    client: YtOAuthClient = testClient,
) = YouTubeMusicLibrary(
    client = client,
    transport = transport,
    secureStore = secure,
    cacheStore = cache,
    clock = clock,
    delay = clock::delay,
    io = EmptyCoroutineContext,
)
