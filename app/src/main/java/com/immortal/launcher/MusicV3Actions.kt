/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YouTubeMusicLibrary
import com.immortal.launcher.youtube.YtResult
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

/**
 * Library/Search taps → engine queue. Every list is checked with [YouTubeMusicLibrary.playable]
 * first, so a blocked song never interrupts what is playing; it gets one toast instead.
 * Runs on [scope] (the screen's main-thread scope); [toast] shows a right-pane message.
 */
internal class MusicV3Actions(
    private val library: YouTubeMusicLibrary,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
) {
  /** Tap on a track: plays it and builds the queue from the list (recommended flow 3). */
  fun playFrom(list: List<YtDataTrack>, tapped: YtDataTrack, label: String) = withPlayable(list) { ok ->
    val start = MusicV3Rules.startIndex(tapped, ok)
    if (start == null) toast(MusicV3Rules.blockedToast(tapped.title))
    else YouTubeMusicEngine.playNow(ok.map { it.toQueueTrack() }, start, label)
  }

  /** Play / Shuffle buttons on a playlist, liked songs or an artist. */
  fun playAll(list: List<YtDataTrack>, label: String, shuffle: Boolean) = withPlayable(list) { ok ->
    if (ok.isEmpty()) return@withPlayable toast(NOTHING_PLAYABLE)
    val start = if (shuffle) Random.nextInt(ok.size) else 0
    YouTubeMusicEngine.playNow(ok.map { it.toQueueTrack() }, start, label)
    if (shuffle) YouTubeMusicEngine.setShuffle(true)
  }

  /** Search result tap: plays now and keeps the rest of Up next after it (recommended answer 8). */
  fun playSearchResult(track: YtDataTrack, query: String) = withOne(track) { t ->
    if (YouTubeMusicEngine.state.value.queue.isEmpty) {
      YouTubeMusicEngine.playNow(listOf(t), 0, "Search · $query")
    } else {
      YouTubeMusicEngine.playNext(t)
      YouTubeMusicEngine.next()
    }
  }

  fun playNext(track: YtDataTrack) = withOne(track) { YouTubeMusicEngine.playNext(it); toast("Plays next: ${it.title}") }

  /** Shows [text] in the right pane's toast. */
  fun notify(text: String) = toast(text)

  fun addToQueue(track: YtDataTrack) = withOne(track) { YouTubeMusicEngine.addToEnd(it); toast("Added to the queue") }

  private fun withOne(track: YtDataTrack, then: (YtTrack) -> Unit) = withPlayable(listOf(track)) { ok ->
    val first = ok.firstOrNull()
    if (first == null) toast(MusicV3Rules.blockedToast(track.title)) else then(first.toQueueTrack())
  }

  private fun withPlayable(list: List<YtDataTrack>, then: (List<YtDataTrack>) -> Unit) {
    scope.launch {
      when (val result = library.playable(list)) {
        is YtResult.Ok -> then(result.value)
        YtResult.Offline -> toast("This Portal is offline. Try again when it reconnects.")
        YtResult.NeedsSignIn -> toast("Sign in again to play from this account.")
        YtResult.Resting -> toast("YouTube's daily limit is used up. Try again tomorrow.")
        is YtResult.Failed -> toast("YouTube didn't answer. Try again in a minute.")
      }
    }
  }

  private companion object {
    const val NOTHING_PLAYABLE = "None of these songs can play outside YouTube."
  }
}
