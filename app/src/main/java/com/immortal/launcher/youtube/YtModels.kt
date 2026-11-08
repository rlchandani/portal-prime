/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.youtube

/**
 * Immutable value types of the official-YouTube music data layer. Nothing here holds a token:
 * credentials live only in [YtStoredAccount] (redacted `toString`) inside the account store.
 */

/** A signed-in Google account, identified by its YouTube channel. [needsSignIn] = refresh token revoked/expired. */
data class YtAccount(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val needsSignIn: Boolean = false,
)

/** One playable video. [durationMs] is only known after [YouTubeMusicLibrary.playable]. */
data class YtTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long? = null,
)

data class YtPlaylist(val id: String, val title: String, val thumbnailUrl: String?, val itemCount: Int?)

/** A subscribed channel, presented as an artist. */
data class YtArtist(val channelId: String, val name: String, val thumbnailUrl: String?)

/**
 * A library value plus its age. [isRefreshing] = this is a cached copy and a network refresh
 * follows in the same flow (stale-while-refresh).
 */
data class YtLoaded<T>(val value: T, val fetchedAtMs: Long, val isRefreshing: Boolean)

/** Search results. [remainingToday] = calls left in today's (Pacific-time) search budget. */
data class YtSearchPage(val tracks: List<YtTrack>, val remainingToday: Int, val fromCache: Boolean)

/** Why a [YtResult.Failed] happened; the UI maps each value to its own copy. */
enum class YtProblem {
  /** The request itself was rejected (bad id, too-short query, HTTP 400). */
  INVALID_REQUEST,
  NOT_FOUND,
  FORBIDDEN,
  SERVER,
  /** Google answered with something that isn't the documented JSON. */
  BAD_RESPONSE,
  UNEXPECTED,
}

/** Typed outcome of every data-layer call. Never carries exception messages or credentials. */
sealed interface YtResult<out T> {
  data class Ok<T>(val value: T) : YtResult<T>

  /** No network (or Google unreachable). */
  data object Offline : YtResult<Nothing>

  /** No active account, or its sign-in was revoked/expired — show the sign-in flow. */
  data object NeedsSignIn : YtResult<Nothing>

  /** Quota spent (our daily search cap or Google's quotaExceeded); try again after Pacific midnight. */
  data object Resting : YtResult<Nothing>

  /** [code] = HTTP status, or 0 when no response was involved. */
  data class Failed(val code: Int, val problem: YtProblem) : YtResult<Nothing>
}

internal inline fun <T, R> YtResult<T>.map(transform: (T) -> R): YtResult<R> =
    flatMap { YtResult.Ok(transform(it)) }

internal inline fun <T, R> YtResult<T>.flatMap(transform: (T) -> YtResult<R>): YtResult<R> =
    when (this) {
      is YtResult.Ok -> transform(value)
      is YtResult.Failed -> this
      YtResult.Offline -> YtResult.Offline
      YtResult.NeedsSignIn -> YtResult.NeedsSignIn
      YtResult.Resting -> YtResult.Resting
    }

/** Wall clock in epoch milliseconds; injected so tests control time. */
fun interface YtClock {
  fun nowMs(): Long

  companion object {
    val SYSTEM = YtClock { System.currentTimeMillis() }
  }
}

/**
 * OAuth client of type "TVs and Limited Input devices". Values come from `local.properties`
 * (`yt.oauth.clientId` / `yt.oauth.clientSecret`) via BuildConfig; empty = not configured.
 */
class YtOAuthClient(val clientId: String, internal val clientSecret: String) {
  val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

  override fun toString(): String = "YtOAuthClient(configured=$isConfigured)"
}

/** Google endpoints and the single scope this layer requests. */
object YtEndpoints {
  const val DEVICE_CODE = "https://oauth2.googleapis.com/device/code"
  const val TOKEN = "https://oauth2.googleapis.com/token"
  const val REVOKE = "https://oauth2.googleapis.com/revoke"
  const val API_BASE = "https://www.googleapis.com/youtube/v3/"
  const val YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"
  const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
}
