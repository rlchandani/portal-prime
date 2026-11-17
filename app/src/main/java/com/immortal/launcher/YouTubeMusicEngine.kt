/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.MainThread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide music engine for the official YouTube IFrame player: native queue
 * ([YtEngineReducer]) + one player WebView ([YouTubePlayerHost]). Implements [MusicTransport]
 * (registered as [MusicTransports.youtube] while the player lives) and publishes
 * [NowPlayingState] with source [SOURCE_YOUTUBE] into [NowPlayingHub].
 *
 * Policy: playback happens only while the music screen is in the foreground with the player
 * visible (see [YouTubePlayerSurface]); leaving the foreground pauses and only a user action
 * resumes. The player is released [RELEASE_GRACE_MS] after it stops being visible
 * ([MusicPlayerVisibility]: its surface left composition, or the screen left the foreground for
 * another app or the screensaver), or at once on memory pressure ([MemoryTrimPolicy.releasePlayer]);
 * its release also shrinks the shared art cache to [MemoryTrimPolicy.MUSIC_IDLE_ART_BYTES], since
 * the song lists are gone. Shown again within the grace, the same player is kept (no reload).
 *
 * Audio focus: the WebView's media stack requests and honors focus itself (other apps pause; a
 * transient request such as the dinner chime pauses and later resumes it). We mirror whatever the
 * official player reports, so no second focus request competes with the WebView's own.
 *
 * Every public function may be called from any thread; work is posted to the main thread.
 */
object YouTubeMusicEngine : MusicTransport {
  private const val TAG = "ImmortalYtEngine"
  /** How long a player whose surface left composition is kept before it is torn down. */
  const val RELEASE_GRACE_MS = 30_000L

  private val main = Handler(Looper.getMainLooper())
  private val mutableState = MutableStateFlow(YtEngineState())
  private var host: YouTubePlayerHost? = null
  private var store: YtQueueStore? = null
  private var app: Application? = null
  private var published: NowPlayingState? = null
  /** The composed player slot of the music screen, if any (cleared when it leaves composition). */
  private var container: ViewGroup? = null
  private var visibility = MusicPlayerVisibility()
  private val releaseLater = Runnable { release() }
  private var engineWarmed = false

  /** Engine snapshot for the UI (queue, player status, halt/skip notices, unknown-item flag). */
  val state: StateFlow<YtEngineState> = mutableState.asStateFlow()

  /**
   * The music screen composed the player's [container]. The player is hosted there (created, and
   * the saved queue restored, on first use) while the screen is also in the foreground.
   */
  @MainThread
  fun attach(container: ViewGroup) {
    this.container = container
    updateVisibility(visibility.withHosted(true))
  }

  /**
   * Loads the saved queue into an engine whose player was never created this process (e.g. a
   * phone "play" right after a restart). A no-op once restored or when a queue is already loaded.
   */
  fun restoreSaved(context: Context) {
    val application = context.applicationContext as Application
    onMain { ensureStore(application) }
  }

  /**
   * Pays Chromium's one-time start-up (measured at 850–1000 ms on the main thread of a Portal+, the
   * whole first-open stall of Music) while the launcher is idle, by building and destroying one
   * throwaway WebView on the application context. Later WebViews, including the player's, then cost
   * tens of ms. Nothing stays loaded: no page, no player, no audio; playback still happens only in
   * the visible player. Once per process; a no-op once the player exists.
   */
  @MainThread
  fun prewarm(context: Context) {
    if (engineWarmed || host != null) return
    engineWarmed = true
    val started = SystemClock.elapsedRealtime()
    runCatching { WebView(context.applicationContext).destroy() }
        .onSuccess { Log.i(TAG, "WebView engine warmed in ${SystemClock.elapsedRealtime() - started} ms") }
        .onFailure { Log.w(TAG, "WebView warm-up failed; the player starts it on first open", it) }
  }

  /** [container] left composition: the player is un-hosted and released after [RELEASE_GRACE_MS]. */
  @MainThread
  fun detach(container: ViewGroup) {
    if (this.container !== container) return
    host?.detach(container)
    this.container = null
    updateVisibility(visibility.withHosted(false))
  }

  /**
   * Called by the music screen: false pauses (no background playback) and starts the release
   * grace; true cancels it but never resumes playback.
   */
  fun setForeground(visible: Boolean) = onMain {
    updateVisibility(visibility.withForeground(visible))
    reduce(YtAction.Foreground(visible))
  }

  /** Shows the player (cancels the grace; hosts or rebuilds it) or starts the release grace. */
  @MainThread
  private fun updateVisibility(next: MusicPlayerVisibility) {
    val effect = visibility.effectTo(next)
    visibility = next
    when (effect) {
      PlayerVisibilityEffect.SHOW -> container?.let { target ->
        main.removeCallbacks(releaseLater)
        Log.i(TAG, if (host == null) "shown: creating player" else "shown: keeping player")
        obtainHost(target.context).attach(target)
      }
      PlayerVisibilityEffect.START_GRACE -> if (host != null) {
        Log.i(TAG, "hidden: release in ${RELEASE_GRACE_MS / 1000} s unless shown again")
        main.removeCallbacks(releaseLater)
        main.postDelayed(releaseLater, RELEASE_GRACE_MS)
      }
      PlayerVisibilityEffect.NONE -> Unit
    }
  }

  /** Replaces the queue with [tracks] and plays [startIndex]; [label] e.g. "Playlist · Road trip". */
  fun playNow(tracks: List<YtTrack>, startIndex: Int = 0, label: String = "") =
      dispatch(YtAction.PlayNow(tracks, startIndex, label))

  /** Takes over a queue moved here from another Portal; it plays once the music screen is visible. */
  fun adopt(queue: YtQueue, positionMs: Long) = dispatch(YtAction.Adopt(queue, positionMs))

  fun playNext(track: YtTrack) = dispatch(YtAction.PlayNext(track))
  /** Plays [track] now, keeping the rest of Up next after it (a search result or history tap). */
  fun playTrackNow(track: YtTrack, label: String) = dispatch(YtAction.PlayTrackNow(track, label))
  fun addToEnd(track: YtTrack) = dispatch(YtAction.AddToEnd(track))
  fun remove(entryId: Long) = dispatch(YtAction.Remove(entryId))
  fun move(from: Int, to: Int) = dispatch(YtAction.Move(from, to))
  /** Plays queue entry [entryId] (a tap on a queue row); retries it if it was marked unplayable. */
  fun select(entryId: Long) = dispatch(YtAction.Select(entryId))
  fun setRepeat(mode: YtRepeat) = dispatch(YtAction.SetRepeat(mode))
  fun setShuffle(on: Boolean) = dispatch(YtAction.SetShuffle(on, SystemClock.elapsedRealtime()))
  fun clear() = dispatch(YtAction.Clear)

  /** Player volume 0–100 (the IFrame player's own level). */
  fun setVolume(level: Int) = onMain { host?.setVolume(level) }

  override fun play() = dispatch(YtAction.Play)
  override fun pause() = dispatch(YtAction.Pause)
  override fun next() = dispatch(YtAction.Next)
  override fun previous() = dispatch(YtAction.Previous)
  override fun seekTo(positionMs: Long) = dispatch(YtAction.SeekTo(positionMs))

  /** Saves the queue, destroys the player and clears Now Playing. The queue stays in memory. */
  @MainThread
  fun release() {
    main.removeCallbacks(releaseLater)
    val h = host ?: return
    host = null
    Log.i(TAG, "released player")
    h.release()
    if (MusicTransports.youtube === this) MusicTransports.youtube = null
    app?.unregisterComponentCallbacks(memory)
    reduce(YtAction.PlayerLost)
    clearPublished()
    MultiRoomArt.trimToBytes(MemoryTrimPolicy.MUSIC_IDLE_ART_BYTES)
  }

  /** Applies [action] on the main thread. */
  fun dispatch(action: YtAction) = onMain { reduce(action) }

  @MainThread
  private fun reduce(action: YtAction) {
    val step = YtEngineReducer.reduce(mutableState.value, action)
    mutableState.value = step.state
    step.effects.forEach(::execute)
    publish(step.state)
  }

  private fun execute(effect: YtEffect) {
    val h = host
    when (effect) {
      is YtEffect.Load -> h?.load(effect.videoId, effect.startMs)
      is YtEffect.Cue -> h?.cue(effect.videoId, effect.startMs)
      YtEffect.Play -> h?.play()
      YtEffect.Pause -> h?.pause()
      YtEffect.Stop -> h?.stop()
      is YtEffect.Seek -> h?.seek(effect.positionMs)
      is YtEffect.CuePlaylist -> h?.cuePlaylist(effect.listId)
      YtEffect.Persist -> mutableState.value.let { store?.save(it.queue, it.positionMs) }
    }
  }

  private fun obtainHost(context: Context): YouTubePlayerHost {
    host?.let { return it }
    engineWarmed = true
    val application = context.applicationContext as Application
    ensureStore(application)
    val h = YouTubePlayerHost(application, hostListener)
    host = h
    app = application.also { it.registerComponentCallbacks(memory) }
    MusicTransports.youtube = this
    return h
  }

  @MainThread
  private fun ensureStore(application: Application) {
    if (store != null) return
    store = YtQueueStore(application).also { s -> s.load()?.let { reduce(YtAction.Restore(it.queue, it.positionMs)) } }
  }

  /** Publishes only meaningful changes; never clears another source's Now Playing. */
  private fun publish(s: YtEngineState) {
    val next = YtNowPlaying.map(s)
    if (next == null) {
      if (published != null) clearPublished()
      return
    }
    val hubLost = NowPlayingHub.current == null
    if (hubLost || YtMusicBridgeParser.shouldPublish(published, next, SystemClock.elapsedRealtime())) {
      published = next
      NowPlayingHub.publish(next)
    }
  }

  private fun clearPublished() {
    published = null
    if (NowPlayingHub.current?.source == SOURCE_YOUTUBE) NowPlayingHub.publish(null)
  }

  private fun onSize(verdict: YtSizeVerdict) {
    when (verdict) {
      YtSizeVerdict.TOO_SMALL -> if (BuildConfig.DEBUG) {
        Log.e(TAG, "player below ${YtPlayerSize.MIN_CSS_PX}x${YtPlayerSize.MIN_CSS_PX} CSS px; refusing playback")
        reduce(YtAction.SizeChanged(ok = false))
      } else {
        Log.w(TAG, "player below the YouTube minimum size")
      }
      YtSizeVerdict.OK -> if (!mutableState.value.sizeOk) reduce(YtAction.SizeChanged(ok = true))
      YtSizeVerdict.NOT_LAID_OUT -> Unit
    }
  }

  private fun openExternally(uri: Uri) {
    val context = app ?: return
    runCatching {
      context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Log.w(TAG, "no handler for player link", it) }
  }

  private val hostListener = object : YouTubePlayerHost.Listener {
    override fun onPlayerEvent(event: YtPlayerEvent) = reduce(event)
    override fun onPlayerLost() = reduce(YtAction.PlayerLost)
    override fun onSizeVerdict(verdict: YtSizeVerdict) = onSize(verdict)
    override fun onExternalLink(uri: Uri) = openExternally(uri)
  }

  private val memory = object : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
      main.post { if (MemoryTrimPolicy.releasePlayer(level, visibility.visible)) release() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = Unit
  }

  private inline fun onMain(crossinline block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
  }
}
