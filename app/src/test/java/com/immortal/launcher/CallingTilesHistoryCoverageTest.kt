/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Group tiles, call history pruning/codec, and the calling enums' wire fallbacks. */
class CallingTilesHistoryCoverageTest {
  private val kitchen = CallContact("22222222-2222-2222-2222-222222222222", "Kitchen")
  private val den = CallContact("33333333-3333-3333-3333-333333333333", "Den")
  private val attic = CallContact("44444444-4444-4444-4444-444444444444", "Attic")
  private val day = 24 * 60 * 60_000L
  private val now = 100 * day

  private fun call(phase: CallPhase = CallPhase.LIVE, legs: List<CallLeg> = emptyList()) =
      ActiveCall("0123456789abcdef", kitchen, CallDirection.OUTGOING, CallKind.CALL, true, phase, 0, 0, cameraOn = false,
          micOn = true, legs = legs, remoteCameraOn = false, remoteMicOn = true)

  private fun leg(c: CallContact, phase: LegPhase) = CallLeg(c, phase, 0, offerer = true, remoteCameraOn = true, remoteMicOn = false)

  private fun entry(i: Int, at: Long, dir: CallDirection = CallDirection.INCOMING, reason: CallEndReason = CallEndReason.MISSED,
                    ms: Long = 0, kind: CallKind = CallKind.CALL, seen: Boolean = false) =
      CallHistoryEntry("%016x".format(i), kitchen.deviceId, "Kitchen", dir, kind, reason, at, ms, seen)

  @Test fun firstTileFollowsTheCallPhase() {
    fun first(p: CallPhase) = (CallTiles.of(call(p)).first() as CallTile.Member).state
    assertEquals(CallTile.State.LIVE, first(CallPhase.LIVE))
    assertEquals(CallTile.State.RECONNECTING, first(CallPhase.RECONNECTING))
    for (p in listOf(CallPhase.DIALING, CallPhase.RINGING, CallPhase.COUNTDOWN, CallPhase.CONNECTING)) {
      assertEquals(p.name, CallTile.State.CONNECTING, first(p))
    }
  }

  @Test fun memberTilesCarryEachLegsStateAndRemoteMedia() {
    val tiles = CallTiles.of(call(legs = listOf(leg(den, LegPhase.RINGING), leg(attic, LegPhase.JOINING))))
    assertEquals(4, tiles.size)
    assertEquals(CallTile.Member(kitchen, CallTile.State.LIVE, false, true), tiles[0])
    assertEquals(CallTile.Member(den, CallTile.State.RINGING, true, false), tiles[1])
    assertEquals(CallTile.State.CONNECTING, (tiles[2] as CallTile.Member).state)
    assertEquals(CallTile.Self(false), tiles[3])
    val reconnecting = CallTiles.of(call(legs = listOf(leg(den, LegPhase.RECONNECTING), leg(attic, LegPhase.LIVE))))
    assertEquals(listOf(CallTile.State.RECONNECTING, CallTile.State.LIVE), reconnecting.drop(1).take(2).map { (it as CallTile.Member).state })
  }

  @Test fun theLoadProbeHoldsATileOnlyWhileThereIsRoom() {
    val withProbe = CallTiles.of(call(), probe = true)
    assertEquals(3, withProbe.size)
    val probe = withProbe[1] as CallTile.Member
    assertEquals(CallTiles.PROBE_NAME, probe.contact.name)
    assertEquals(CallTile.State.LIVE, probe.state)
    val full = CallTiles.of(call(legs = listOf(leg(den, LegPhase.LIVE), leg(attic, LegPhase.LIVE))), probe = true)
    assertTrue(full.none { it is CallTile.Member && it.contact.name == CallTiles.PROBE_NAME })
    assertEquals(2, CallTiles.of(call()).size)
  }

  @Test fun arrangementByHeadcountAndOrientation() {
    assertEquals(TileArrangement.SOLO, CallTiles.arrangement(1, LayoutClass.LARGE))
    assertEquals(TileArrangement.SOLO, CallTiles.arrangement(2, LayoutClass.COMPACT_PORTRAIT))
    assertEquals(TileArrangement.THREE_SIDE, CallTiles.arrangement(3, LayoutClass.LARGE))
    assertEquals(TileArrangement.THREE_SIDE, CallTiles.arrangement(3, LayoutClass.COMPACT_LANDSCAPE))
    assertEquals(TileArrangement.THREE_STACKED, CallTiles.arrangement(3, LayoutClass.COMPACT_PORTRAIT))
    assertEquals(TileArrangement.FOUR_GRID, CallTiles.arrangement(4, LayoutClass.COMPACT_PORTRAIT))
    assertEquals(TileArrangement.FOUR_GRID, CallTiles.arrangement(5, LayoutClass.LARGE))
  }

  @Test fun groupLiveBarCountsPeople() {
    assertEquals("Camera off · Mic on · 3 of 4", CallTiles.liveBar(call(legs = listOf(leg(den, LegPhase.LIVE)))))
  }

  @Test fun historyOfAnUnconnectedIncomingCallIsUnseenAndTimedAtTheEnd() {
    val e = CallHistory.of(EndedCall("0123456789abcdef", kitchen, CallDirection.INCOMING, CallKind.DROP_IN, CallEndReason.MISSED, null, 5_000))
    assertEquals(5_000L, e.atMs)
    assertEquals(0L, e.durationMs)
    assertFalse(e.seen)
    assertTrue(e.missed)
    val m = CallHistory.missed("0123456789abcdef", den, CallKind.CALL, now, CallEndReason.QUIET)
    assertEquals(CallDirection.INCOMING, m.direction)
    assertEquals(den.deviceId, m.peerId)
    assertTrue(m.missed)
    assertTrue(CallHistory.missed("0123456789abcdef", den, CallKind.CALL, now, CallEndReason.BUSY).missed)
  }

  @Test fun pruneKeepsExactlyThirtyDaysAndTheNewestTwoHundred() {
    val edge = entry(1, now - CallTimings.HISTORY_KEEP_MS)
    val old = entry(2, now - CallTimings.HISTORY_KEEP_MS - 1)
    assertEquals(listOf(edge), CallHistory.prune(listOf(old, edge), now))
    val many = (1..CallHistory.CAPACITY + 5).map { entry(it, now - it * 1_000L) }
    val pruned = CallHistory.prune(many.shuffled(java.util.Random(7)), now)
    assertEquals(CallHistory.CAPACITY, pruned.size)
    assertEquals(many.take(CallHistory.CAPACITY), pruned)
  }

  @Test fun filtersSeenAndLatest() {
    val list = listOf(entry(1, now, seen = true), entry(2, now - 1), entry(3, now - 2, dir = CallDirection.OUTGOING, reason = CallEndReason.HUNG_UP, ms = 1))
    assertEquals(list, CallHistory.filter(list, CallHistoryFilter.ALL))
    assertEquals(emptyList<CallHistoryEntry>(), CallHistory.filter(list, CallHistoryFilter.MESSAGES))
    assertEquals("%016x".format(2), CallHistory.latestUnseenMissed(list)!!.id)
    assertNull(CallHistory.latestUnseenMissed(CallHistory.markSeen(list)))
    assertEquals(listOf("All", "Missed", "Drop-ins", "Messages"), CallHistoryFilter.entries.map { it.label })
  }

  @Test fun decodeRejectsEachBadFieldAndDedupes() {
    val good = JSONArray(CallHistory.encode(listOf(entry(9, now, dir = CallDirection.OUTGOING, reason = CallEndReason.HUNG_UP, ms = 4_000))))
        .getJSONObject(0)
    fun with(key: String, value: Any?) = JSONObject(good.toString()).apply { if (value == null) remove(key) else put(key, value) }
    val arr = JSONArray().put(good).put(JSONObject(good.toString())).put(42)
        .put(with("p", "nope")).put(with("n", "")).put(with("d", "x")).put(with("k", "z"))
        .put(with("r", "??")).put(with("t", 0)).put(with("t", "soon"))
    val decoded = CallHistory.decode(arr.toString())
    assertEquals(1, decoded.size)
    assertEquals(CallDirection.OUTGOING, decoded.single().direction)
    assertEquals(4_000L, decoded.single().durationMs)
    // A missing or negative duration reads as zero; a missing seen flag as unseen.
    val loose = CallHistory.decode(JSONArray().put(with("ms", -5).apply { remove("s") }).put(
        JSONObject(good.toString()).put("i", "%016x".format(10)).apply { remove("ms") }).toString())
    assertEquals(listOf(0L, 0L), loose.map { it.durationMs })
    assertFalse(loose[0].seen)
    assertEquals(emptyList<CallHistoryEntry>(), CallHistory.decode(null))
  }

  @Test fun decodeCapsAtCapacity() {
    val many = (1..CallHistory.CAPACITY + 3).map { entry(it, now - it) }
    assertEquals(CallHistory.CAPACITY, CallHistory.decode(CallHistory.encode(many)).size)
  }

  @Test fun addPeopleNoticeCopyForEveryGroupEvent() {
    fun n(reason: CallEndReason, to: String? = null, move: Boolean = false) = CallAddPeople.notice(CallNotice("Walt", reason, 0, to, move))
    assertEquals("Walt didn’t pick up · the call stays here", n(CallEndReason.HUNG_UP, move = true))
    assertEquals("Walt moved the call to Sam’s phone", n(CallEndReason.MOVED, to = "Sam’s phone"))
    assertEquals("Walt handed the call over", n(CallEndReason.MOVED))
    assertEquals("Walt is in quiet hours · we didn’t ring", n(CallEndReason.QUIET))
    assertEquals("Walt is in another call", n(CallEndReason.BUSY))
    assertEquals("Walt can’t join right now", n(CallEndReason.DECLINED))
    assertEquals("Walt doesn’t take calls right now", n(CallEndReason.CALLS_OFF))
    assertEquals("Walt is offline", n(CallEndReason.OFFLINE))
    assertEquals("Walt didn’t answer", n(CallEndReason.NO_ANSWER))
    assertEquals("Walt couldn’t connect", n(CallEndReason.FAILED))
    assertEquals("Walt lost the connection", n(CallEndReason.NETWORK_LOST))
    assertEquals("Walt left the call", n(CallEndReason.LEFT))
    assertEquals("Walt left the call", n(CallEndReason.HUNG_UP))
  }

  @Test fun addSheetSubtitleSaysWhenTheCallIsFull() {
    assertEquals("Up to 4 people · 3 in the call now", CallAddPeople.subtitle(call(legs = listOf(leg(den, LegPhase.LIVE)))))
    assertEquals("4 of 4 · this call is full", CallAddPeople.subtitle(call(legs = listOf(leg(den, LegPhase.LIVE), leg(attic, LegPhase.RINGING)))))
  }

  @Test fun enumWireFallbacks() {
    assertEquals(CallQuality.AUTO, CallQuality.fromWire(null))
    assertEquals(CallQuality.BEST, CallQuality.fromWire("best"))
    assertEquals(CallQuality.SAVE, CallQuality.LOW_MEMORY)
    assertEquals(CallRingtone.HEARTH, CallRingtone.fromWire("polka"))
    assertEquals(CallRingtone.MARIMBA, CallRingtone.fromWire("marimba"))
    assertEquals(CallWhoCanCall.HOME, CallWhoCanCall.fromWire(""))
    assertEquals(CallWhoCanCall.NOBODY, CallWhoCanCall.fromWire("nobody"))
    assertEquals(CallQuietMode.BLOCK, CallQuietMode.fromWire("loud"))
    assertEquals(CallQuietMode.VISUAL, CallQuietMode.fromWire("visual"))
    assertNull(CallKind.fromWire("x"))
    assertNull(CallEndReason.fromWire(null))
    for (r in CallEndReason.entries) assertEquals(r, CallEndReason.fromWire(r.wire))
  }
}
