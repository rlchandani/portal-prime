/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-level holder for the latest now-playing state broadcast by the ImmortalCast
 * companion — the consumer twin of [PresenceHub]. Initialised once from
 * [ImmortalApp], it keeps the current [NowPlayingState] so anything that comes up
 * later (the screensaver, a header chip) sees the track that's already playing,
 * and notifies in-process listeners on change.
 *
 * If ImmortalCast isn't installed, no broadcast ever arrives and [current] stays
 * null — so every consumer naturally stays dormant. That's the whole gating story.
 */
object NowPlayingHub {
  private const val TAG = "ImmortalNowPlaying"

  fun interface Listener {
    fun onNowPlaying(state: NowPlayingState?)
  }

  // A live track is only shown while broadcasts keep arriving. ImmortalCast resends
  // the current track every ~30s; if we hear nothing for this long the companion has
  // gone away (killed, uninstalled, crashed) and we drop the card rather than show a
  // track that may no longer be playing.
  private const val STALE_AFTER_MS = 90_000L

  private val listeners = CopyOnWriteArrayList<Listener>()
  private var app: Context? = null
  private val handler = Handler(Looper.getMainLooper())
  private val expire = Runnable {
    if (current != null) {
      current = null
      Log.i(TAG, "now-playing: stale, clearing card")
      listeners.forEach { runCatching { it.onNowPlaying(null) } }
    }
  }

  @Volatile
  var current: NowPlayingState? = null
    private set

  /** Register the receiver. Called from [ImmortalApp.onCreate]; safe to call once. */
  fun init(context: Context) {
    if (app != null) return
    app = context.applicationContext
    val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(c: Context, intent: Intent) {
            val state = NowPlayingState.fromIntent(intent)
            current = if (state.active) state else null
            // (Re)arm the staleness timer only while something's actually playing.
            handler.removeCallbacks(expire)
            if (current != null) handler.postDelayed(expire, STALE_AFTER_MS)
            Log.i(TAG, "now-playing: ${state.state} ${state.artist} — ${state.title}")
            listeners.forEach { runCatching { it.onNowPlaying(current) } }
          }
        }
    val filter = IntentFilter(NowPlaying.ACTION_NOW_PLAYING)
    // Cross-app broadcast from the companion (targeted at us), so it must be
    // exported. Spoofing is only cosmetic here (LAN/home device); a signature gate
    // can come later if it ever matters.
    if (Build.VERSION.SDK_INT >= 33)
        app!!.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    else @Suppress("UnspecifiedRegisterReceiverFlag") app!!.registerReceiver(receiver, filter)
  }

  /** Subscribe (in-process). Immediately replays [current] to the new listener. */
  fun addListener(l: Listener) {
    listeners.add(l)
    l.onNowPlaying(current)
  }

  fun removeListener(l: Listener) = listeners.remove(l)
}
