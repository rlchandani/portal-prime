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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tells the other rooms what follow-me needs to know about this one, over [HouseholdBus]:
 * `presence` on every change (coalesced to one per [FollowMeCadence.MIN_GAP_MS]) plus a
 * [FollowMeCadence.PRESENCE_EVERY_MS] heartbeat, and `music_live` while music plays here (on a new
 * song / play state, and every [FollowMeCadence.LIVE_EVERY_MS]), then one "stopped". Main thread only.
 */
internal object FollowMeBroadcaster {
  private const val TAG = "HearthpaneFollowMe"
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
  private var app: Context? = null
  private var presence = Presence.UNKNOWN
  private var presenceSinceMs = System.currentTimeMillis()
  private var lastPresenceSentMs: Long? = null
  private var lastSentPresence: Presence? = null
  private var lastLiveSentMs: Long? = null
  private var liveWasPlaying = false
  private val presenceRunnable = Runnable { sendPresence() }
  private val liveRunnable = Runnable { sendLive() }

  fun start(context: Context) {
    if (app != null) return
    app = context.applicationContext
    scope.launch {
      YouTubeMusicEngine.state.map { it.isPlaying to it.queue.current?.track?.videoId }.distinctUntilChanged().collect {
        schedule(liveRunnable, lastLiveSentMs)
      }
    }
    schedule(presenceRunnable, null)
  }

  /** This room's presence changed (from [MusicFollowMe]). */
  fun onPresence(p: Presence, nowMs: Long) {
    presence = p
    presenceSinceMs = nowMs
    schedule(presenceRunnable, lastPresenceSentMs)
  }

  private fun schedule(r: Runnable, lastSentMs: Long?) {
    main.removeCallbacks(r)
    main.postDelayed(r, FollowMeCadence.waitMs(lastSentMs, now()))
  }

  private fun sendPresence() {
    val c = app ?: return
    val now = now()
    val note = FollowMeWire.PresenceNote(presence, now - presenceSinceMs, DinnerCall.selfName(c))
    val sent = HouseholdBus.send(c, FollowMeWire.PRESENCE, FollowMeWire.encode(note))
    if (note.presence != lastSentPresence) Log.i(TAG, "presence ${note.presence} broadcast sent=$sent")
    lastSentPresence = note.presence
    lastPresenceSentMs = now
    main.removeCallbacks(presenceRunnable)
    main.postDelayed(presenceRunnable, FollowMeCadence.PRESENCE_EVERY_MS)
  }

  private fun sendLive() {
    val c = app ?: return
    val track = trackOf(YouTubeMusicEngine.state.value)
    main.removeCallbacks(liveRunnable)
    if (track == null && !liveWasPlaying) return
    val now = now()
    val note = FollowMeWire.LiveNote(presence, now - presenceSinceMs, DinnerCall.selfName(c), track)
    val sent = HouseholdBus.send(c, FollowMeWire.LIVE, FollowMeWire.encode(note))
    if ((track != null) != liveWasPlaying) Log.i(TAG, "music_live ${if (track != null) "playing" else "stopped"} sent=$sent")
    liveWasPlaying = track != null
    lastLiveSentMs = now
    if (track != null) main.postDelayed(liveRunnable, FollowMeCadence.LIVE_EVERY_MS)
  }

  /** What plays here now, or null when nothing does. */
  private fun trackOf(s: YtEngineState): FollowMeTrack? {
    val t = s.queue.current?.track ?: return null
    if (!s.isPlaying) return null
    val running = if (s.status == YtPlayerStatus.PLAYING) SystemClock.elapsedRealtime() - s.positionAtElapsedMs else 0L
    val position = (s.positionMs + running.coerceAtLeast(0L)).let { if (s.durationMs > 0) it.coerceAtMost(s.durationMs) else it }
    return FollowMeTrack(t.title, t.artist, t.thumbnailUrl, position)
  }

  private fun now(): Long = System.currentTimeMillis()
}
