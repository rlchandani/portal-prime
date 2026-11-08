/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.immortal.launcher.youtube.YtAndroid
import com.immortal.launcher.youtube.YtResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Music shared from a paired phone ("Play on a Portal"), Portal side. [MusicShareRoute] validates
 * and answers; this applies the share to the official-player queue on the main thread, wakes and
 * opens Music the same way "Move to…" does ([MusicHandoff.openMusicScreen]), shows the arrival
 * line or the right-pane toast, and remembers the last share so the phone can learn about a
 * refusal by the official player (error 101/150/age) on its next status GET.
 */
internal object MusicShare {
  private const val TAG = "CalderMusicShare"
  private const val MAIN_WAIT_MS = 2_000L
  private const val LIBRARY_WAIT_MS = 8_000L
  private const val PLAYER_WAIT_MS = 15_000L
  private const val LOOKUP_WAIT_MS = 15_000L
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
  private val arrivalFlow = MutableStateFlow<String?>(null)
  private val clearArrival = Runnable { arrivalFlow.value = null }
  private val last = AtomicReference<LastShare?>(null)

  /** "From Rohit's phone · just now", shown in the now-playing line for [MusicSharePolicy.ARRIVAL_MS]. */
  val arrival: StateFlow<String?> = arrivalFlow.asStateFlow()

  /** What the phone's confirmation can still learn after the POST answered. */
  data class LastShare(val shareId: String?, val entryIds: List<Long>, val count: Int?, val failure: String?)

  fun install() {
    RemoteFeatureRoutes.register(MusicShareRoute.PATH, MusicShareRoute)
  }

  fun lastShare(): LastShare? = last.get()

  /** Why the official player refused a shared entry, if it did. */
  fun skippedReason(share: LastShare, queue: YtQueue): String? =
      queue.entries.firstOrNull { it.id in share.entryIds && it.unplayableReason != null }?.unplayableReason

  /** Applies a single song now (HTTP thread → main); null when the main thread didn't answer. */
  fun shareVideo(context: Context, req: MusicShareRequest, quietly: Boolean): MusicShareOutcome? {
    val track = YtTrack(req.id, req.title.ifBlank { "Shared song" }, req.artist,
        req.thumb ?: MusicShareQueue.thumbnailFor(req.id))
    val label = MusicShareQueue.label(MusicShareKind.VIDEO, track.title, 1)
    return onMainBlocking { apply(context, req, listOf(track), label, quietly, needsMeta = req.title.isBlank()) }
  }

  /** What was playing when the request arrived (cueing a playlist on the player stops it). */
  private data class Before(val playing: Boolean, val positionMs: Long)

  /**
   * Opens a playlist in the background and applies it: the signed-in data layer, else the ONE
   * visible engine player on the Music screen (never a hidden player). [source] comes from
   * [MusicSharePolicy.playlistSource]; the route declines NEEDS_SIGN_IN before calling this.
   */
  fun sharePlaylist(context: Context, req: MusicShareRequest, quietly: Boolean, source: MusicSharePolicy.PlaylistSource) {
    last.set(LastShare(req.shareId, emptyList(), null, null))
    val engine = YouTubeMusicEngine.state.value
    val before = Before(engine.isPlaying, MusicV3NowPlaying.livePositionMs(engine, SystemClock.elapsedRealtime()))
    scope.launch {
      val resolved = playlistTracks(context, req, source, before)
      if (resolved == null || resolved.tracks.isEmpty()) {
        Log.i(TAG, "playlist ${req.id.take(6)}… couldn't be opened")
        last.set(LastShare(req.shareId, emptyList(), 0, "This playlist couldn't be opened on this Portal."))
        return@launch
      }
      val prepared = MusicShareQueue.prepare(resolved.tracks, req.shuffle, System.nanoTime())
      val label = MusicShareQueue.label(MusicShareKind.PLAYLIST, req.title, prepared.size)
      // After a player lookup the cue stopped the song; apply as of when the request arrived.
      apply(context, req, prepared, label, quietly, needsMeta = resolved.placeholders, playlistCount = prepared.size,
          before = before.takeIf { resolved.placeholders })
    }
  }

  private data class Resolved(val tracks: List<YtTrack>, val placeholders: Boolean)

  private suspend fun playlistTracks(context: Context, req: MusicShareRequest,
      source: MusicSharePolicy.PlaylistSource, before: Before): Resolved? {
    var from = source
    if (from == MusicSharePolicy.PlaylistSource.LIBRARY) {
      val result = withTimeoutOrNull(LIBRARY_WAIT_MS) { YtAndroid.library(context).playlistTracks(req.id).first() }
      if (result is YtResult.Ok) return Resolved(result.value.value.map { it.toQueueTrack() }, placeholders = false)
      from = MusicSharePolicy.libraryFallback(before.playing, req.action)
      Log.i(TAG, "library couldn't list the playlist; fallback=$from")
    }
    if (from != MusicSharePolicy.PlaylistSource.PLAYER) return null
    val ids = lookUpWithPlayer(context, req.id) ?: return null
    return Resolved(MusicShareQueue.placeholders(ids, req.title.ifBlank { "Playlist" }), placeholders = true)
  }

  /** Opens Music (Follow-me's path), waits for its visible player, cues the list there and reads its ids. */
  private suspend fun lookUpWithPlayer(context: Context, listId: String): List<String>? {
    if (!YouTubeMusicEngine.state.value.foreground) MusicHandoff.openMusicScreen(context)
    withTimeoutOrNull(PLAYER_WAIT_MS) { YouTubeMusicEngine.state.first { it.foreground && it.ready && it.sizeOk } }
        ?: return null
    YouTubeMusicEngine.dispatch(YtAction.LookUpPlaylist(listId))
    val lookup = withTimeoutOrNull(LOOKUP_WAIT_MS) {
      YouTubeMusicEngine.state.mapNotNull { it.playlistLookup?.takeIf { l -> l.listId == listId } }.first()
    }
    return lookup?.videoIds?.takeIf { it.isNotEmpty() }
  }

  /** Main thread only. */
  private fun apply(context: Context, req: MusicShareRequest, tracks: List<YtTrack>, label: String,
      quietly: Boolean, needsMeta: Boolean, playlistCount: Int? = null, before: Before? = null): MusicShareOutcome? {
    val s = YouTubeMusicEngine.state.value
    val playing = before?.playing ?: s.isPlaying
    val position = before?.positionMs ?: MusicV3NowPlaying.livePositionMs(s, SystemClock.elapsedRealtime())
    val outcome = MusicShareQueue.apply(s.queue, playing, position, tracks, req.action, req.from, label) ?: return null
    val queue = if (!needsMeta) outcome.queue else outcome.queue.copy(entries = outcome.queue.entries.map {
      if (it.id in outcome.sharedIds) it.copy(needsMeta = true) else it })
    YouTubeMusicEngine.dispatch(YtAction.ApplyShared(queue, outcome.restart))
    if (quietly) lowerVolume(context)
    if (outcome.restart) {
      if (!s.foreground) MusicHandoff.openMusicScreen(context)
      showArrival("From ${req.from} · just now")
    }
    MusicShareQueue.toast(outcome, req.from, label)?.let(MusicHandoff::announce)
    last.set(LastShare(req.shareId, outcome.sharedIds, playlistCount ?: 1, null))
    return outcome.copy(queue = queue)
  }

  private fun lowerVolume(context: Context) {
    val volume = MusicVolume(context)
    val now = volume.read()
    volume.setStep(MusicSharePolicy.quietStep(now.step, now.maxStep))
  }

  private fun showArrival(text: String) {
    main.removeCallbacks(clearArrival)
    arrivalFlow.value = text
    main.postDelayed(clearArrival, MusicSharePolicy.ARRIVAL_MS)
  }

  private fun <T> onMainBlocking(block: () -> T): T? {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val result = AtomicReference<T?>(null)
    val done = CountDownLatch(1)
    main.post { try { result.set(block()) } finally { done.countDown() } }
    return if (done.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) result.get() else null
  }
}
