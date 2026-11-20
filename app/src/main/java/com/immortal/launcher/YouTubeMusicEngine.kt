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
 * Policy: playback happens only while the player is visible in some host ([MusicPlayerHosts]: the
 * Music screen or Home's Now Playing card) and nothing covers it ([MusicCoverRules]). Moving between hosts re-parents the same WebView (no reload, no pause).
 * When no host shows it the engine pauses (after [MusicHostHandoff.SWAP_MS], so a host swap
 * never stutters) and only a user action resumes. The player is released [RELEASE_GRACE_MS] after it
 * stopped being visible, or at once on memory pressure ([MemoryTrimPolicy.releasePlayer]); its
 * release also shrinks the shared art cache to [MemoryTrimPolicy.MUSIC_IDLE_ART_BYTES], since the
 * song lists are gone. Shown again within the grace, the same player is kept (no reload).
 *
 * Audio focus: the WebView's media stack requests and honors focus itself (other apps pause; a
 * transient request such as the dinner chime pauses and later resumes it). We mirror whatever the
 * official player reports, so no second focus request competes with the WebView's own.
 *
 * Every public function may be called from any thread; work is posted to the main thread.
 */
object YouTubeMusicEngine : MusicTransport {
  private const val TAG = "ImmortalYtEngine"
  /** How long a player no host shows is kept before it is torn down. */
  const val RELEASE_GRACE_MS = 30_000L

  private val main = Handler(Looper.getMainLooper())
  private val mutableState = MutableStateFlow(YtEngineState())
  private var host: YouTubePlayerHost? = null
  private var store: YtQueueStore? = null
  private var app: Application? = null
  private var published: NowPlayingState? = null
  /** The composed player slot of each host, if any (cleared when it leaves composition). */
  private val containers = mutableMapOf<MusicPlayerHostKind, ViewGroup>()
  private var hosts = MusicPlayerHosts()
  private val covers = mutableSetOf<MusicCover>()
  private val releaseLater = Runnable { release() }
  private val hideNow = Runnable { confirmHidden() }

  /** The host showing the player now, readable from any thread (null when it is hidden). */
  @Volatile var shownIn: MusicPlayerHostKind? = null
    private set
  private var engineWarmed = false

  /** Engine snapshot for the UI (queue, player status, halt/skip notices, unknown-item flag). */
  val state: StateFlow<YtEngineState> = mutableState.asStateFlow()

  /**
   * A host composed the player's [container]. The player is hosted there (created, and the saved
   * queue restored, on first use) while that host is also [setActive] and best-ranked.
   */
  @MainThread
  fun attach(kind: MusicPlayerHostKind, container: ViewGroup) {
    val previous = containers.put(kind, container)
    // A new surface for the host already showing the player (its screen was rebuilt): move it there.
    if (previous != null && previous !== container && hosts.shownIn == kind) host?.attach(container)
    updateHosts(hosts.withAttached(kind, true))
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

  /** [container] left composition: un-hosted; with no other host the player pauses and is released later. */
  @MainThread
  fun detach(kind: MusicPlayerHostKind, container: ViewGroup) {
    if (containers[kind] !== container) return
    host?.detach(container)
    containers.remove(kind)
    updateHosts(hosts.withAttached(kind, false))
  }

  /** The screen around [kind]'s surface came to (true) or left (false) the foreground. */
  fun setActive(kind: MusicPlayerHostKind, on: Boolean) = onMain { updateHosts(hosts.withActive(kind, on)) }

  /**
   * [cover] is drawn over the player (true) or gone (false); see [MusicCoverRules]. Covering pauses
   * at once; uncovering waits one main-loop turn, so a card replacing another never blips the music.
   */
  fun setCovered(cover: MusicCover, on: Boolean) = onMain {
    if (on) covers.add(cover) else covers.remove(cover)
    main.removeCallbacks(applyUncover)
    if (covers.isEmpty()) main.post(applyUncover) else applyCovered()
  }

  @MainThread
  private fun applyCovered() {
    if (mutableState.value.covered || covers.isEmpty()) return
    Log.i(TAG, "covered by ${covers.joinToString()}: paused while it shows")
    reduce(YtAction.Covered(true))
  }

  private val applyUncover = Runnable {
    if (covers.isEmpty() && mutableState.value.covered) {
      Log.i(TAG, "uncovered")
      reduce(YtAction.Covered(false))
    }
  }

  @MainThread
  private fun updateHosts(next: MusicPlayerHosts) {
    val effect = hosts.effectTo(next)
    hosts = next
    when (effect) {
      is PlayerHostEffect.Show -> show(effect.host)
      is PlayerHostEffect.Move -> containers[effect.to]?.let { target ->
        main.removeCallbacks(hideNow)
        Log.i(TAG, "host ${effect.from} -> ${effect.to}: same player, no reload")
        shownIn = effect.to
        obtainHost(target.context).attach(target)
      }
      PlayerHostEffect.Hide -> {
        shownIn = null
        main.removeCallbacks(hideNow)
        main.postDelayed(hideNow, MusicHostHandoff.SWAP_MS)
      }
      PlayerHostEffect.None -> Unit
    }
  }

  /** Cancels a pending hide/release and hosts the player in [kind] (creating it if it was released). */
  @MainThread
  private fun show(kind: MusicPlayerHostKind) {
    val target = containers[kind] ?: return
    main.removeCallbacks(hideNow)
    main.removeCallbacks(releaseLater)
    shownIn = kind
    Log.i(TAG, if (host == null) "shown in $kind: creating player" else "shown in $kind: keeping player")
    obtainHost(target.context).attach(target)
    // A play issued in the same turn as the re-attach is dropped by the WebView (measured on the
    // Portal: the card's Play after ✕ never started); say "visible" once the view has laid out.
    target.post { if (shownIn == kind && !mutableState.value.foreground) reduce(YtAction.Foreground(true)) }
  }

  /** No host took the player back within the swap wait: pause and start the release grace. */
  @MainThread
  private fun confirmHidden() {
    if (hosts.visible) return
    val s = mutableState.value
    if (MusicHostHandoff.explainsPause(s)) MusicV3Session.leftWhilePlaying()
    if (s.foreground) reduce(YtAction.Foreground(false))
    if (host != null) {
      Log.i(TAG, "hidden: paused; release in ${RELEASE_GRACE_MS / 1000} s unless shown again")
      main.removeCallbacks(releaseLater)
      main.postDelayed(releaseLater, RELEASE_GRACE_MS)
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
    YtVideoScreenState.playingOnScreen = YtVideoScreenState.holds(step.state)
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
      main.post { if (MemoryTrimPolicy.releasePlayer(level, hosts.visible)) release() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) = Unit
    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = Unit
  }

  private inline fun onMain(crossinline block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
  }
}
