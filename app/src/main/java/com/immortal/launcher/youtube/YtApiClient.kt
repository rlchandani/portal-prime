/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Short-lived access tokens, held in memory only (never persisted). Refreshes through `/token`;
 * an `invalid_grant` marks the account "needs sign-in again" so it stops retrying.
 */
internal class YtAccessTokens(
    private val store: YtAccountStore,
    private val oauth: YtOAuthApi,
    private val clock: YtClock,
) {
  private class Cached(val token: String, val expiresAtMs: Long) {
    override fun toString(): String = "Cached(expiresAt=$expiresAtMs)"
  }

  private val mutex = Mutex()
  @Volatile private var cache: Map<String, Cached> = emptyMap()

  suspend fun get(accountId: String): YtResult<String> = mutex.withLock {
    cache[accountId]?.takeIf { clock.nowMs() < it.expiresAtMs }?.let { return YtResult.Ok(it.token) }
    val stored = store.snapshot.value.accounts.firstOrNull { it.account.id == accountId }
    if (stored == null || stored.account.needsSignIn) return YtResult.NeedsSignIn
    when (val outcome = oauth.refresh(stored.refreshToken)) {
      is YtRefreshOutcome.Refreshed -> {
        val lifetimeMs = (outcome.grant.expiresInSec * 1000 - EXPIRY_SKEW_MS).coerceAtLeast(0)
        cache = cache + (accountId to Cached(outcome.grant.accessToken, clock.nowMs() + lifetimeMs))
        YtResult.Ok(outcome.grant.accessToken)
      }
      YtRefreshOutcome.InvalidGrant -> {
        store.markNeedsSignIn(accountId)
        YtResult.NeedsSignIn
      }
      YtRefreshOutcome.Offline -> YtResult.Offline
      is YtRefreshOutcome.Failed -> YtResult.Failed(outcome.code, YtProblem.SERVER)
    }
  }

  /** Seeds the cache with a token just obtained from the device flow. */
  suspend fun remember(accountId: String, grant: YtTokenGrant) = mutex.withLock {
    val lifetimeMs = (grant.expiresInSec * 1000 - EXPIRY_SKEW_MS).coerceAtLeast(0)
    cache = cache + (accountId to Cached(grant.accessToken, clock.nowMs() + lifetimeMs))
  }

  fun invalidate(accountId: String) {
    cache = cache - accountId
  }

  private companion object {
    /** Refresh a minute early so a token never expires mid-request. */
    const val EXPIRY_SKEW_MS = 60_000L
  }
}

/**
 * Authorized GETs against the YouTube Data API v3 for one account: Bearer token, a single
 * refresh-and-retry on 401, and HTTP/quota errors mapped to [YtResult].
 */
internal class YtApiClient(private val transport: YtHttpTransport, private val tokens: YtAccessTokens) {

  /**
   * GET `[YtEndpoints.API_BASE][path]?[params]`. [onSent] fires once per request that actually
   * reached the network (search budget accounting).
   */
  suspend fun get(
      accountId: String,
      path: String,
      params: Map<String, String?>,
      onSent: () -> Unit = {},
  ): YtResult<JSONObject> {
    val url = ytUrl(YtEndpoints.API_BASE + path, params)
    val first = attempt(accountId, url, onSent)
    if (first !is Attempt.Unauthorized) return first.result
    tokens.invalidate(accountId)
    val second = attempt(accountId, url, onSent)
    return if (second is Attempt.Unauthorized) YtResult.NeedsSignIn else second.result
  }

  private sealed interface Attempt {
    val result: YtResult<JSONObject>
    class Done(override val result: YtResult<JSONObject>) : Attempt
    data object Unauthorized : Attempt {
      override val result: YtResult<JSONObject> = YtResult.NeedsSignIn
    }
  }

  private suspend fun attempt(accountId: String, url: String, onSent: () -> Unit): Attempt {
    val token = when (val t = tokens.get(accountId)) {
      is YtResult.Ok -> t.value
      else -> return Attempt.Done(t.map { JSONObject() })
    }
    val request = YtHttpRequest("GET", url, mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"))
    val response = when (val ex = transport.exchange(request)) {
      is YtExchange.Answered -> ex.response.also { onSent() }
      YtExchange.Unreachable -> return Attempt.Done(YtResult.Offline)
      YtExchange.Crashed -> return Attempt.Done(YtResult.Failed(0, YtProblem.UNEXPECTED))
    }
    if (response.code == 401) return Attempt.Unauthorized
    return Attempt.Done(interpret(response))
  }

  private fun interpret(response: YtHttpResponse): YtResult<JSONObject> {
    val json = parseJson(response.body)
    if (response.code in 200..299) {
      return json?.let { YtResult.Ok(it) } ?: YtResult.Failed(response.code, YtProblem.BAD_RESPONSE)
    }
    if (response.code == 403 && errorReason(json) in QUOTA_REASONS) return YtResult.Resting
    val problem = when (response.code) {
      400 -> YtProblem.INVALID_REQUEST
      403 -> YtProblem.FORBIDDEN
      404 -> YtProblem.NOT_FOUND
      429 -> return YtResult.Resting
      in 500..599 -> YtProblem.SERVER
      else -> YtProblem.UNEXPECTED
    }
    return YtResult.Failed(response.code, problem)
  }

  private fun errorReason(json: JSONObject?): String? =
      json?.optJSONObject("error")?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason")

  private companion object {
    val QUOTA_REASONS = setOf("quotaExceeded", "dailyLimitExceeded", "rateLimitExceeded", "userRateLimitExceeded")
  }
}
