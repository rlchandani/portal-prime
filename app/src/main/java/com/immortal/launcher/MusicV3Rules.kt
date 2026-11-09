/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtAccount
import com.immortal.launcher.youtube.YtSignInState
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

/** Which full screen the music entry shows (music v3, official YouTube route). */
internal enum class MusicV3Gate {
  NOT_CONFIGURED, SIGN_IN, ADD_ACCOUNT, NO_ONE, PLAYER,
  /** No usable account, but a queue (e.g. moved here from another Portal): play it, no Library/Search. */
  GUEST,
}

/** What the sign-in screen shows; derived from [YtSignInState] and the wall clock. */
internal sealed interface MusicV3SignInView {
  data object Starting : MusicV3SignInView
  data class Code(val userCode: String, val qrText: String, val shortUrl: String, val remaining: String) :
      MusicV3SignInView
  data object Expired : MusicV3SignInView
  data object Denied : MusicV3SignInView
  data object Offline : MusicV3SignInView
  data object Failed : MusicV3SignInView
  data class Success(val name: String) : MusicV3SignInView
  data object NotConfigured : MusicV3SignInView
}

/** Pure UI rules for music v3. Everything here is unit-tested; the composables only render. */
internal object MusicV3Rules {
  /** Recommended answer 3: up to four accounts per Portal. */
  const val MAX_ACCOUNTS = 4
  /** Recommended answer 4: show the remaining count only at this many or fewer. */
  const val SEARCH_LOW_AT = 10
  const val MAX_RECENT_SEARCHES = 6
  const val MIN_QUERY = 2

  fun gate(
      configured: Boolean,
      accounts: List<YtAccount>,
      active: YtAccount?,
      adding: Boolean,
      signedOutLast: Boolean,
      /** The sign-in just succeeded: keep "Signed in as …" up although an account now exists. */
      showingSuccess: Boolean = false,
      /** The engine holds a queue; public videos play without an account. */
      hasQueue: Boolean = false,
  ): MusicV3Gate = when {
    showingSuccess && adding -> MusicV3Gate.ADD_ACCOUNT
    showingSuccess -> MusicV3Gate.SIGN_IN
    adding && configured -> MusicV3Gate.ADD_ACCOUNT
    hasQueue && (active == null || active.needsSignIn) -> MusicV3Gate.GUEST
    accounts.isEmpty() && !configured -> MusicV3Gate.NOT_CONFIGURED
    accounts.isEmpty() && signedOutLast -> MusicV3Gate.NO_ONE
    accounts.isEmpty() -> MusicV3Gate.SIGN_IN
    active?.needsSignIn == true && configured -> MusicV3Gate.SIGN_IN
    else -> MusicV3Gate.PLAYER
  }

  fun canAddAccount(count: Int): Boolean = count < MAX_ACCOUNTS

  /** Sign-in can be backed out of when there's something to go back to (an account or a guest queue). */
  fun canCancelSignIn(accounts: Int, hasQueue: Boolean): Boolean = accounts > 0 || hasQueue

  fun signInView(state: YtSignInState, nowMs: Long): MusicV3SignInView = when (state) {
    YtSignInState.Idle -> MusicV3SignInView.Starting
    is YtSignInState.ShowingCode -> code(state.userCode, state.verificationUrl, state.expiresAtMs, nowMs)
    is YtSignInState.Polling -> code(state.userCode, state.verificationUrl, state.expiresAtMs, nowMs)
    is YtSignInState.SignedIn -> MusicV3SignInView.Success(state.account.title)
    YtSignInState.Denied -> MusicV3SignInView.Denied
    YtSignInState.Expired -> MusicV3SignInView.Expired
    YtSignInState.Offline -> MusicV3SignInView.Offline
    YtSignInState.NotConfigured -> MusicV3SignInView.NotConfigured
    is YtSignInState.Failed -> MusicV3SignInView.Failed
  }

  private fun code(userCode: String, url: String, expiresAtMs: Long, nowMs: Long): MusicV3SignInView {
    val left = expiresAtMs - nowMs
    if (left <= 0L) return MusicV3SignInView.Expired
    return MusicV3SignInView.Code(userCode, url, shortUrl(url), formatTime(left))
  }

  /** "https://www.google.com/device" → "google.com/device" for the "Or open …" line. */
  fun shortUrl(url: String): String =
      url.trim().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

  /** m:ss (or h:mm:ss); negative values read as 0:00. */
  fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) + 999L) / 1000L
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
  }

  /** Elapsed position (floored, unlike the countdown) as m:ss. */
  fun formatPosition(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    return formatTime(total * 1000L)
  }

  /** "8 searches left today" only at ≤ [SEARCH_LOW_AT]; nothing above, nothing at 0 (resting). */
  fun searchHint(remaining: Int): String? = when {
    remaining <= 0 || remaining > SEARCH_LOW_AT -> null
    remaining == 1 -> "1 search left today"
    else -> "$remaining searches left today"
  }

  fun searchResting(remaining: Int): Boolean = remaining <= 0

  /** Submit only: at least [MIN_QUERY] characters after trimming. */
  fun canSubmit(query: String, remaining: Int, recent: List<String>): Boolean {
    val q = query.trim()
    if (q.length < MIN_QUERY) return false
    return !searchResting(remaining) || recent.any { it.equals(q, ignoreCase = true) }
  }

  /** Newest first, case-insensitive de-duplication, at most [MAX_RECENT_SEARCHES]. */
  fun withRecent(recent: List<String>, query: String): List<String> {
    val q = query.trim().replace(Regex("\\s+"), " ")
    if (q.length < MIN_QUERY) return recent
    return (listOf(q) + recent.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT_SEARCHES)
  }

  /** Where [tapped] landed in the list `playable()` returned, or null when it can't be embedded. */
  fun startIndex(tapped: YtDataTrack, playable: List<YtDataTrack>): Int? =
      playable.indexOfFirst { it.videoId == tapped.videoId }.takeIf { it >= 0 }

  fun blockedToast(title: String): String =
      "“$title” can't play here. The artist doesn't allow playing it outside YouTube."

  fun skipToast(notice: YtSkipNotice): String = "Skipped “${notice.title}”. ${skipSentence(notice.reason)}"

  private fun skipSentence(reason: String): String = when {
    reason.contains("allow", ignoreCase = true) -> "The artist doesn't allow playing it outside YouTube."
    reason.isBlank() -> "It can't play here."
    else -> reason.trimEnd('.') + "."
  }

  fun upNextCount(count: Int): String = if (count == 1) "1 song" else "$count songs"

  fun songCount(count: Int?): String = when (count) {
    null -> "Playlist"
    1 -> "1 song"
    else -> "$count songs"
  }

  fun signOutBody(name: String, stopsMusic: Boolean): String {
    val base = "This removes $name's access from this Portal. Nothing changes in their Google account."
    return if (stopsMusic) "$base Music playing from this account stops." else base
  }

  fun initial(name: String): String = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

/** The one mapping between the data layer's track and the engine's queue track. */
internal fun YtDataTrack.toQueueTrack(): YtTrack =
    YtTrack(videoId = videoId, title = title, artist = artist, thumbnailUrl = thumbnailUrl.orEmpty(),
        durationMs = durationMs)
