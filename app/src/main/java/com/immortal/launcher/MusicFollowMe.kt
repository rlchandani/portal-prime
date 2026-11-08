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
import android.util.Log
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * "Music follows you": when someone walks into this room from one playing music, offer to continue
 * it here (Ask first) or bring it over (Automatic, with Undo). [FollowMePolicy] decides; the move
 * is [MusicHandoff.follow], the "Bring it back" recall, so queue, position and the visible-player
 * rule are the existing handoff's. [FollowMeBroadcaster] tells the other rooms about this one.
 *
 * Every decision is logged under [TAG] (and the move under `HearthpaneMusicMove`) for device tests.
 * Bus and presence callbacks hop to the main thread; all state here is main-thread confined.
 */
internal object MusicFollowMe {
  private const val TAG = "HearthpaneFollowMe"
  /** Re-evaluate this often during the arrival window (the source's camera may notice late). */
  const val TICK_MS = 5_000L
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
  @Volatile private var installed = false
  private var app: Context? = null
  private var rooms: Map<String, FollowMeRoom> = emptyMap()
  private var cooldowns: Map<String, Long> = emptyMap()
  private var presence = Presence.UNKNOWN
  private var arrivedMs: Long? = null
  private var lastReason: String? = null
  private var pendingAuto: PendingAuto? = null
  private val tickRunnable = Runnable { evaluate("tick") }

  /** An Automatic follow in flight: show "followed you" + Undo once [recallId] lands here. */
  private data class PendingAuto(val recallId: String, val sourceId: String, val sourceName: String)

  /** Call after [HouseholdBus.start], [MusicHandoff.install] and [PresenceHub.init]. */
  fun install(context: Context) {
    if (installed) return
    installed = true
    val c = context.applicationContext
    app = c
    HouseholdBus.register(FollowMeWire.PRESENCE) { _, m ->
      FollowMeWire.decodePresence(m.body)?.let { n -> main.post { onRoomPresence(m.from, n) } }
    }
    HouseholdBus.register(FollowMeWire.LIVE) { _, m ->
      FollowMeWire.decodeLive(m.body)?.let { n -> main.post { onRoomLive(m.from, n) } }
    }
    FollowMeBroadcaster.start(c)
    PresenceHub.addListener { s -> main.post { onPresenceHere(s.presence) } }
    scope.launch {
      var previous = MusicHandoff.move.value
      MusicHandoff.move.collect { s -> onMove(previous, s); previous = s }
    }
  }

  // ---- inputs ----

  private fun onPresenceHere(p: Presence) {
    if (p == presence) return
    val previous = presence
    presence = p
    val now = now()
    Log.i(TAG, "presence here $previous -> $p")
    FollowMeBroadcaster.onPresence(p, now)
    if (previous == Presence.ABSENT && p == Presence.PRESENT) {
      arrivedMs = now
      lastReason = null
      evaluate("arrival")
    } else if (p != Presence.PRESENT) {
      stopWatching()
    }
  }

  private fun onRoomPresence(from: String, note: FollowMeWire.PresenceNote) {
    if (rooms[from]?.presence != note.presence) Log.i(TAG, "${note.name} is ${note.presence} (for ${note.forMs / 1000} s)")
    rooms = FollowMeRoster.onPresence(rooms, from, note, now())
    evaluate("presence from ${note.name}")
  }

  private fun onRoomLive(from: String, note: FollowMeWire.LiveNote) {
    val wasPlaying = rooms[from]?.live != null
    if (wasPlaying != (note.track != null)) {
      Log.i(TAG, "${note.name} music ${if (note.track != null) "playing" else "stopped"}; room ${note.presence}")
    }
    rooms = FollowMeRoster.onLive(rooms, from, note, now())
    evaluate("music from ${note.name}")
  }

  // ---- deciding ----

  private fun evaluate(trigger: String) {
    val arrived = arrivedMs ?: return
    val c = app ?: return
    val self = DinnerCall.selfId(c) ?: return
    val now = now()
    rooms = FollowMeRoster.prune(rooms, now)
    cooldowns = cooldowns.filterValues { now - it < FollowMePolicy.COOLDOWN_MS }
    val mode = FollowMeConfig.mode(c)
    when (val d = FollowMePolicy.decide(mode, here(c, self, arrived), rooms.values.toList(), cooldowns, now)) {
      is FollowMeDecision.None -> {
        if (d.reason != lastReason) Log.i(TAG, "no follow ($trigger, mode=${mode.wire}): ${d.reason}")
        lastReason = d.reason
        if (d.reason == FollowMePolicy.WINDOW_OVER || d.reason == FollowMePolicy.OFF) stopWatching() else scheduleTick()
      }
      is FollowMeDecision.Ask -> claim(self, d.source, now, "ask").also { ask(c, d.source) }
      is FollowMeDecision.Follow -> claim(self, d.source, now, "automatic").also { follow(c, d.source, auto = true) }
    }
  }

  private fun here(c: Context, self: String, arrived: Long): FollowMeHere {
    val move = MusicHandoff.move.value
    return FollowMeHere(
        id = self,
        arrivedMs = arrived,
        playing = YouTubeMusicEngine.state.value.isPlaying,
        moving = move is MusicMoveState.Offering || move is MusicMoveState.Recalling,
        quiet = DinnerQuiet.isQuiet(ChimeConfig.load(c), System.currentTimeMillis(), TimeZone.getDefault()),
        routine = RoutineScreenState.ownsScreen(),
        inCall = RoutineScheduler.busy(c),
    )
  }

  /** One decision per arrival; the pair then cools down in both directions. */
  private fun claim(self: String, source: FollowMeRoom, now: Long, what: String) {
    Log.i(TAG, "follow-me $what: music from ${source.name}")
    stopWatching()
    cooldowns = cooldowns + (FollowMePolicy.pairKey(self, source.id) to now)
  }

  private fun scheduleTick() {
    main.removeCallbacks(tickRunnable)
    main.postDelayed(tickRunnable, TICK_MS)
  }

  private fun stopWatching() {
    arrivedMs = null
    main.removeCallbacks(tickRunnable)
  }

  // ---- acting ----

  private fun ask(c: Context, source: FollowMeRoom) {
    val track = source.live ?: return
    ScreenControl.wake(c)
    val card = FollowMeCardOverlay.Ask(FollowMeCopy.askTitle(track.title), FollowMeCopy.askLine(track.artist, source.name))
    val shown = FollowMeCardOverlay.showAsk(card,
        onContinue = { Log.i(TAG, "ask: Continue"); follow(c, source, auto = false) },
        onNotNow = { Log.i(TAG, "ask: Not now (tapped or timed out)") })
    if (!shown) Log.w(TAG, "ask card not shown: the accessibility overlay host isn't attached")
  }

  private fun follow(c: Context, source: FollowMeRoom, auto: Boolean) {
    val id = MusicHandoff.follow(c, source.id, source.name)
    Log.i(TAG, "pull from ${source.name} (auto=$auto): started=${id != null}")
    if (auto && id != null) pendingAuto = PendingAuto(id, source.id, source.name)
  }

  /** An Automatic pull finished: if the music now plays here, say so and offer Undo. */
  private fun onMove(previous: MusicMoveState, s: MusicMoveState) {
    val p = pendingAuto ?: return
    if (previous !is MusicMoveState.Recalling || previous.id != p.recallId || s is MusicMoveState.Recalling) return
    pendingAuto = null
    val playing = s is MusicMoveState.Idle && YouTubeMusicEngine.state.value.isPlaying
    Log.i(TAG, "automatic pull from ${p.sourceName} ended: playing here=$playing")
    if (playing) FollowMeCardOverlay.showFollowed(FollowMeCopy.followed(p.sourceName)) { undo(p) }
  }

  private fun undo(p: PendingAuto) {
    val c = app ?: return
    Log.i(TAG, "undo: moving the music back to ${p.sourceName}")
    MusicHandoff.moveTo(c, MusicMoveRoom(p.sourceId, p.sourceName, enabled = true, note = null))
  }

  private fun now(): Long = System.currentTimeMillis()
}
