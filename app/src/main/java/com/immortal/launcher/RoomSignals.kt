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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Gathers what the Control Center map fuses into "people in rooms" ([CcOccupancyFusion]) and hands it
 * to the engine as one immutable [CcRoomSignals]:
 * - every Portal's presence: this one from [PresenceHub], the others from follow-me's `presence` /
 *   `music_live` broadcasts (forwarded by [MusicFollowMe], which owns those bus types);
 * - Who's there? recognitions: this Portal's own (from [WhosThere.state]) and other Portals'
 *   ([RoomSeenWire], sealed). Both pass [CcPeoplePolicy] before they are kept or sent.
 *
 * Sending: while a person this Portal recognised is still in view and [CcPeoplePolicy.shareable], one
 * `room_seen` goes out when they're first seen and then every [RESEND_MS]; when they stop being
 * shareable (paused, opted out, deleted, map setting no longer Names) a "forget" goes out once.
 * Nothing is sent for anyone else, and nothing at all unless the map setting is Names.
 * Main-thread confined; bus callbacks hop here. Logs never name people or rooms.
 */
internal object RoomSignals {
  private const val TAG = "HearthpaneRoomSignals"
  /** While someone stays in view their sighting is refreshed this often (well inside [CcOccupancyFusion.SIGHTING_MS]). */
  const val RESEND_MS = 60_000L
  /** Peers silent this long are dropped (follow-me forgets after 30 min; the map stops trusting them after 5). */
  private const val FORGET_PEER_MS = 30 * 60_000L

  private val main by lazy { Handler(Looper.getMainLooper()) }
  private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
  private var app: Context? = null
  private var self: CcPortalPresence? = null
  private var peers: Map<String, CcPortalPresence> = emptyMap()
  private var sightings: Map<String, CcSighting> = emptyMap()
  /** Person ids on this Portal → when their last `room_seen` went out. */
  private var sent: Map<String, Long> = emptyMap()
  private var published: CcRoomSignals? = null
  private val tick = Runnable { evaluateLocal() }

  /** After [HouseholdBus.start], [WhosThere.install] and [MusicFollowMe.install]. */
  fun install(context: Context) {
    if (app != null) return
    val c = context.applicationContext
    app = c
    HouseholdBus.register(RoomSeenWire.TYPE) { ctx, m ->
      val key = HouseholdKey.get(ctx) ?: return@register
      RoomSeenWire.decode(m.body, key, m.from)?.let { seen -> main.post { onRemoteSeen(m.from, seen) } }
    }
    PresenceHub.addListener { s -> main.post { onSelfPresence(FollowMeArrival.local(s.presence, s.confident), s.sinceMs) } }
    scope.launch {
      combine(WhosThere.state, WhosThereStore.settingsState) { _, _ -> }.collect { evaluateLocal() }
    }
  }

  /** Settings changed (the map setting or this Portal's room): re-decide what is shared and republish. */
  fun refresh() = main.post {
    val c = app ?: return@post
    self = self?.copy(portalName = DinnerCall.selfName(c), roomHint = selfRoom(c))
    evaluateLocal()
  }

  /** The plan room this Portal chose for itself, or null to be matched by its name. */
  fun selfRoom(c: Context): String? = runCatching { ControlCenterConfig.load(c).planRoom }.getOrNull()?.takeIf { it.isNotEmpty() }

  // ---- presence ----

  private fun onSelfPresence(p: Presence, sinceMs: Long) {
    val c = app ?: return
    val id = DinnerCall.selfId(c) ?: return
    val now = System.currentTimeMillis()
    self = CcPortalPresence(id, DinnerCall.selfName(c), p, sinceMs.takeIf { it > 0 } ?: now, now, selfRoom(c), self = true)
    publish()
  }

  /** A presence note from another Portal (main thread). */
  fun onPeerPresence(from: String, note: FollowMeWire.PresenceNote) {
    val now = System.currentTimeMillis()
    val prev = peers[from]
    val since = if (prev != null && prev.presence == note.presence) prev.sinceMs else now - note.forMs
    peers = (peers + (from to CcPortalPresence(from, note.name, note.presence, since, now, note.room ?: prev?.roomHint)))
        .filterValues { now - it.seenMs <= FORGET_PEER_MS }
    publish()
  }

  // ---- recognitions ----

  private fun mode(c: Context): CcPeoplePolicy.Mode = CcPeoplePolicy.mode(ControlCenterConfig.load(c))

  /** This Portal's view of who it recognised: refresh their sightings, send what may be sent, revoke the rest. */
  private fun evaluateLocal() {
    main.removeCallbacks(tick)
    val c = app ?: return
    val selfId = DinnerCall.selfId(c) ?: return
    val now = System.currentTimeMillis()
    val mode = mode(c)
    val ui = WhosThere.state.value
    val prefix = "$selfId:"
    val sighted = sightings.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    val step = CcPeoplePolicy.step(ui.view, ui.people, WhosThereStore.settingsState.value, mode, sent, sighted, now, RESEND_MS)
    val name = DinnerCall.selfName(c)
    val room = selfRoom(c)
    step.forget.forEach { id -> send(c, RoomSeenWire.Seen(id, "", 0, name, null, 0L, forget = true)) }
    sent = sent - step.forget
    sightings = sightings - step.drop.map { prefix + it }.toSet()
    for (p in step.share) sightings = sightings + ((prefix + p.id) to CcSighting(prefix + p.id, p.name, p.colorIndex, selfId, name, now, room))
    for (p in step.send) if (send(c, RoomSeenWire.Seen(p.id, p.name, p.colorIndex, name, room, 0L))) sent = sent + (p.id to now)
    if (step.forget.isNotEmpty() || step.drop.isNotEmpty()) Log.i(TAG, "recognition shares revoked: ${step.forget.size} sent, ${step.drop.size} here")
    if (step.share.isNotEmpty()) main.postDelayed(tick, RESEND_MS)
    else sent = sent.filterValues { now - it < CcOccupancyFusion.SIGHTING_MS } // nothing in view: let receivers age out
    publish()
  }

  private fun send(c: Context, seen: RoomSeenWire.Seen): Boolean {
    val key = HouseholdKey.get(c) ?: return false
    val from = DinnerCall.selfId(c) ?: return false
    val body = RoomSeenWire.encode(seen, key, from) ?: return false
    return HouseholdBus.send(c, RoomSeenWire.TYPE, body)
  }

  private fun onRemoteSeen(from: String, seen: RoomSeenWire.Seen) {
    val c = app ?: return
    val key = "$from:${seen.personId}"
    if (seen.forget) {
      if (key in sightings) { sightings = sightings - key; publish() }
      return
    }
    val now = System.currentTimeMillis()
    val local = WhosThereStore.householdState.value.people
    if (!CcPeoplePolicy.accept(seen.name, seen.ageMs, local, mode(c), now)) {
      if (key in sightings) { sightings = sightings - key; publish() }
      return
    }
    sightings = sightings + (key to CcSighting(key, seen.name, seen.colorIndex, from, seen.portalName, now - seen.ageMs, seen.roomId))
    publish()
  }

  // ---- publishing ----

  private fun publish() {
    val c = app ?: return
    val now = System.currentTimeMillis()
    sightings = sightings.filterValues { now - it.seenMs < CcOccupancyFusion.SIGHTING_MS }
    // Names leave this object only while the household allows them, and aren't even held otherwise.
    val named = mode(c) == CcPeoplePolicy.Mode.NAMES
    if (!named) sightings = emptyMap()
    val next = CcRoomSignals(listOfNotNull(self) + peers.values.sortedBy { it.portalId }, sightings.values.sortedBy { it.key })
    if (next == published) return
    published = next
    ControlCenter.setSignals(c, next)
  }

  /** Operator-only status for the fleet debug action: counts, never names or rooms. */
  fun debugCounts(): Map<String, Int> = mapOf("peers" to peers.size, "sightings" to sightings.size, "sharing" to sent.size,
      "selfPresent" to if (self?.presence == Presence.PRESENT) 1 else 0)
}
