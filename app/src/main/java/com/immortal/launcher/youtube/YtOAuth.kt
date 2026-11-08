/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

import org.json.JSONObject

/** Tokens from `/token`. [toString] is redacted. */
internal class YtTokenGrant(val accessToken: String, val expiresInSec: Long, val refreshToken: String?) {
  override fun toString(): String = "YtTokenGrant(expiresIn=$expiresInSec, hasRefresh=${refreshToken != null})"
}

/** `/device/code` answer. [deviceCode] is a credential, so [toString] omits it. */
internal class YtDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSec: Long,
    val intervalSec: Long,
) {
  override fun toString(): String = "YtDeviceCode(userCode=$userCode, expiresIn=$expiresInSec)"
}

internal sealed interface YtDeviceCodeOutcome {
  data class Issued(val code: YtDeviceCode) : YtDeviceCodeOutcome
  data object Offline : YtDeviceCodeOutcome
  data class Failed(val code: Int) : YtDeviceCodeOutcome
}

internal sealed interface YtTokenPoll {
  data class Granted(val grant: YtTokenGrant) : YtTokenPoll
  data object Pending : YtTokenPoll
  data object SlowDown : YtTokenPoll
  data object Denied : YtTokenPoll
  data object Expired : YtTokenPoll
  data object Offline : YtTokenPoll
  data class Failed(val code: Int) : YtTokenPoll
}

internal sealed interface YtRefreshOutcome {
  data class Refreshed(val grant: YtTokenGrant) : YtRefreshOutcome
  /** Refresh token revoked or expired — the account must sign in again. */
  data object InvalidGrant : YtRefreshOutcome
  data object Offline : YtRefreshOutcome
  data class Failed(val code: Int) : YtRefreshOutcome
}

/** Google OAuth 2.0 device-flow, refresh and revoke calls. Pure parsing; no state. */
internal class YtOAuthApi(private val transport: YtHttpTransport, private val client: YtOAuthClient) {

  suspend fun requestDeviceCode(): YtDeviceCodeOutcome {
    val form = mapOf("client_id" to client.clientId, "scope" to YtEndpoints.YOUTUBE_READONLY)
    val response = when (val ex = post(YtEndpoints.DEVICE_CODE, form)) {
      is YtExchange.Answered -> ex.response
      YtExchange.Unreachable -> return YtDeviceCodeOutcome.Offline
      YtExchange.Crashed -> return YtDeviceCodeOutcome.Failed(0)
    }
    val json = parseJson(response.body)
    if (response.code !in 200..299 || json == null) return YtDeviceCodeOutcome.Failed(response.code)
    val deviceCode = json.optString("device_code")
    val userCode = json.optString("user_code")
    val url = json.optString("verification_url").ifEmpty { json.optString("verification_uri") }
    if (deviceCode.isEmpty() || userCode.isEmpty() || url.isEmpty()) return YtDeviceCodeOutcome.Failed(response.code)
    return YtDeviceCodeOutcome.Issued(YtDeviceCode(
        deviceCode, userCode, url,
        json.optLong("expires_in", DEFAULT_EXPIRES_SEC),
        json.optLong("interval", DEFAULT_INTERVAL_SEC).coerceAtLeast(1)))
  }

  suspend fun pollToken(deviceCode: String): YtTokenPoll {
    val form = credentials() + mapOf("device_code" to deviceCode, "grant_type" to YtEndpoints.DEVICE_GRANT)
    val response = when (val ex = post(YtEndpoints.TOKEN, form)) {
      is YtExchange.Answered -> ex.response
      YtExchange.Unreachable -> return YtTokenPoll.Offline
      YtExchange.Crashed -> return YtTokenPoll.Failed(0)
    }
    if (response.code in 200..299) {
      return parseGrant(response.body)?.let { YtTokenPoll.Granted(it) } ?: YtTokenPoll.Failed(response.code)
    }
    return when (errorCode(response.body)) {
      "authorization_pending" -> YtTokenPoll.Pending
      "slow_down" -> YtTokenPoll.SlowDown
      "access_denied" -> YtTokenPoll.Denied
      "expired_token" -> YtTokenPoll.Expired
      else -> YtTokenPoll.Failed(response.code)
    }
  }

  suspend fun refresh(refreshToken: String): YtRefreshOutcome {
    val form = credentials() + mapOf("refresh_token" to refreshToken, "grant_type" to "refresh_token")
    val response = when (val ex = post(YtEndpoints.TOKEN, form)) {
      is YtExchange.Answered -> ex.response
      YtExchange.Unreachable -> return YtRefreshOutcome.Offline
      YtExchange.Crashed -> return YtRefreshOutcome.Failed(0)
    }
    if (response.code in 200..299) {
      return parseGrant(response.body)?.let { YtRefreshOutcome.Refreshed(it) } ?: YtRefreshOutcome.Failed(response.code)
    }
    return if (errorCode(response.body) == "invalid_grant") YtRefreshOutcome.InvalidGrant
    else YtRefreshOutcome.Failed(response.code)
  }

  /**
   * Revokes [token] at Google. Sent in the form body (Google accepts body or query) so the token
   * never appears in a URL. True when Google confirmed, or said the token was already invalid.
   */
  suspend fun revoke(token: String): Boolean {
    val ex = post(YtEndpoints.REVOKE, mapOf("token" to token))
    if (ex !is YtExchange.Answered) return false
    return ex.response.code in 200..299 || errorCode(ex.response.body) == "invalid_token"
  }

  private fun credentials() = mapOf("client_id" to client.clientId, "client_secret" to client.clientSecret)

  private suspend fun post(url: String, form: Map<String, String>): YtExchange =
      transport.exchange(YtHttpRequest("POST", url, form = form))

  private fun parseGrant(body: String): YtTokenGrant? {
    val json = parseJson(body) ?: return null
    val access = json.optString("access_token").ifEmpty { return null }
    val refresh = json.optString("refresh_token").ifEmpty { null }
    return YtTokenGrant(access, json.optLong("expires_in", DEFAULT_TOKEN_SEC), refresh)
  }

  private fun errorCode(body: String): String? = parseJson(body)?.optString("error")?.ifEmpty { null }

  private companion object {
    const val DEFAULT_EXPIRES_SEC = 1800L
    const val DEFAULT_INTERVAL_SEC = 5L
    const val DEFAULT_TOKEN_SEC = 3600L
  }
}

/** Parses [text] as a JSON object, or null when it isn't one. */
internal fun parseJson(text: String): JSONObject? =
    try {
      if (text.trimStart().startsWith("{")) JSONObject(text) else null
    } catch (e: org.json.JSONException) {
      null
    }
