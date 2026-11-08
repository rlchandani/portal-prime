/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

/**
 * Sign-in state machine:
 * `Idle → ShowingCode → Polling → SignedIn | Denied | Expired | Offline | Failed`, or
 * `NotConfigured` when this build has no OAuth client. The code stays on screen while polling.
 */
sealed interface YtSignInState {
  data object Idle : YtSignInState

  /** Show [userCode] and a QR of [verificationUrl] until [expiresAtMs]. */
  data class ShowingCode(val userCode: String, val verificationUrl: String, val expiresAtMs: Long) : YtSignInState

  /** Same code, now waiting for the person to approve on their phone. */
  data class Polling(val userCode: String, val verificationUrl: String, val expiresAtMs: Long) : YtSignInState

  data class SignedIn(val account: YtAccount) : YtSignInState
  data object Denied : YtSignInState
  data object Expired : YtSignInState
  data object Offline : YtSignInState
  data object NotConfigured : YtSignInState
  data class Failed(val code: Int) : YtSignInState

  val isTerminal: Boolean get() = this !is ShowingCode && this !is Polling && this !is Idle
}

/**
 * OAuth 2.0 device flow for TVs and limited-input devices. Honors `interval`, adds 5 s on
 * `slow_down`, stops at the code's expiry, and tolerates brief network drops while polling.
 * On success it resolves the channel identity and hands the grant to [onGranted].
 */
internal class YtDeviceFlow(
    private val oauth: YtOAuthApi,
    private val transport: YtHttpTransport,
    private val clock: YtClock,
    private val delay: suspend (Long) -> Unit,
    private val onGranted: suspend (account: YtAccount, refreshToken: String, grant: YtTokenGrant) -> Unit,
) {
  suspend fun run(emit: (YtSignInState) -> Unit): YtSignInState {
    val code = when (val issued = oauth.requestDeviceCode()) {
      is YtDeviceCodeOutcome.Issued -> issued.code
      YtDeviceCodeOutcome.Offline -> return YtSignInState.Offline
      is YtDeviceCodeOutcome.Failed -> return YtSignInState.Failed(issued.code)
    }
    val expiresAt = clock.nowMs() + code.expiresInSec * 1000
    emit(YtSignInState.ShowingCode(code.userCode, code.verificationUrl, expiresAt))
    val grant = when (val polled = poll(code, expiresAt, emit)) {
      is PollEnd.Granted -> polled.grant
      is PollEnd.Stopped -> return polled.state
    }
    val refresh = grant.refreshToken ?: return YtSignInState.Failed(0)
    val account = identity(grant.accessToken)
    onGranted(account, refresh, grant)
    return YtSignInState.SignedIn(account)
  }

  private sealed interface PollEnd {
    class Granted(val grant: YtTokenGrant) : PollEnd
    class Stopped(val state: YtSignInState) : PollEnd
  }

  private suspend fun poll(code: YtDeviceCode, expiresAt: Long, emit: (YtSignInState) -> Unit): PollEnd {
    var intervalMs = code.intervalSec * 1000
    var offlineStreak = 0
    var announced = false
    while (true) {
      delay(intervalMs)
      if (clock.nowMs() >= expiresAt) return PollEnd.Stopped(YtSignInState.Expired)
      val result = oauth.pollToken(code.deviceCode)
      if (!announced && (result == YtTokenPoll.Pending || result == YtTokenPoll.SlowDown)) {
        emit(YtSignInState.Polling(code.userCode, code.verificationUrl, expiresAt))
        announced = true
      }
      when (result) {
        is YtTokenPoll.Granted -> return PollEnd.Granted(result.grant)
        YtTokenPoll.Pending -> offlineStreak = 0
        YtTokenPoll.SlowDown -> intervalMs += SLOW_DOWN_STEP_MS
        YtTokenPoll.Denied -> return PollEnd.Stopped(YtSignInState.Denied)
        YtTokenPoll.Expired -> return PollEnd.Stopped(YtSignInState.Expired)
        YtTokenPoll.Offline -> if (++offlineStreak >= MAX_OFFLINE_POLLS) return PollEnd.Stopped(YtSignInState.Offline)
        is YtTokenPoll.Failed -> return PollEnd.Stopped(YtSignInState.Failed(result.code))
      }
    }
  }

  /**
   * The channel title + thumbnail for display (youtube.readonly covers it — no extra scope).
   * A channel-less Google account, or a failed lookup, still signs in under a local id.
   */
  private suspend fun identity(accessToken: String): YtAccount {
    val url = ytUrl(YtEndpoints.API_BASE + "channels", mapOf("part" to "snippet", "mine" to "true"))
    val request = YtHttpRequest("GET", url, mapOf("Authorization" to "Bearer $accessToken"))
    val response = (transport.exchange(request) as? YtExchange.Answered)?.response
    val json = response?.takeIf { it.code in 200..299 }?.body?.let(::parseJson)
    return json?.let(YtParsers::channelIdentity)
        ?: YtAccount("$LOCAL_ID_PREFIX${clock.nowMs()}", FALLBACK_TITLE, null)
  }

  private companion object {
    const val SLOW_DOWN_STEP_MS = 5_000L
    const val MAX_OFFLINE_POLLS = 3
    const val LOCAL_ID_PREFIX = "local-"
    const val FALLBACK_TITLE = "YouTube account"
  }
}
