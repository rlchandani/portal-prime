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
 * resumes. The player is released [RELEASE_GRACE_MS] after its surface leaves composition.
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
  private val releaseLater = Runnable { release() }

  /** Engine snapshot for the UI (queue, player status, halt/skip notices, unknown-item flag). */
  val state: StateFlow<YtEngineState> = mutableState.asStateFlow()

  /** Hosts the player in [container] (creating it, and restoring the saved queue, on first use). */
  @MainThread
  fun attach(container: ViewGroup) {
    main.removeCallbacks(releaseLater)
    obtainHost(container.context).attach(container)
  }

  /**
   * Loads the saved queue into an engine whose player was never created this process (e.g. a
   * phone "play" right after a restart). A no-op once restored or when a queue is already loaded.
   */
  fun restoreSaved(context: Context) {
    val application = context.applicationContext as Application
    onMain { ensureStore(application) }
  }

  /** Un-hosts the player from [container]; it is released after [RELEASE_GRACE_MS] unless re-attached. */
  @MainThread
  fun detach(container: ViewGroup) {
    host?.detach(container)
    main.removeCallbacks(releaseLater)
    main.postDelayed(releaseLater, RELEASE_GRACE_MS)
  }

  /** Called by the music screen: false pauses (no background playback); true never resumes. */
  fun setForeground(visible: Boolean) = dispatch(YtAction.Foreground(visible))

  /** Replaces the queue with [tracks] and plays [startIndex]; [label] e.g. "Playlist · Road trip". */
  fun playNow(tracks: List<YtTrack>, startIndex: Int = 0, label: String = "") =
      dispatch(YtAction.PlayNow(tracks, startIndex, label))

  /** Takes over a queue moved here from another Portal; it plays once the music screen is visible. */
  fun adopt(queue: YtQueue, positionMs: Long) = dispatch(YtAction.Adopt(queue, positionMs))

  fun playNext(track: YtTrack) = dispatch(YtAction.PlayNext(track))
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
    h.release()
    if (MusicTransports.youtube === this) MusicTransports.youtube = null
    app?.unregisterComponentCallbacks(memory)
    reduce(YtAction.PlayerLost)
    clearPublished()
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
      @Suppress("DEPRECATION")
      if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) main.post { release() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = Unit
  }

  private inline fun onMain(crossinline block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
  }
}
