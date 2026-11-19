/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import org.json.JSONArray
import org.json.JSONObject

/**
 * The rooms this Portal has heard (persisted so an unplugged room still shows as Offline) and the
 * call history (30 days, on this Portal only). Both are immutable lists swapped atomically.
 * Names, ids and timestamps only: no media, no snapshots, ever.
 */
internal object CallingStore {
  private const val TAG = "HearthpaneCalling"
  private const val PREFS = "hearthpane_calling_data"
  private const val PEERS = "peers"
  private const val HISTORY = "history"

  private val mutablePeers = MutableStateFlow<List<CallPeer>>(emptyList())
  val peers: StateFlow<List<CallPeer>> = mutablePeers.asStateFlow()
  private val mutableHistory = MutableStateFlow<List<CallHistoryEntry>>(emptyList())
  val history: StateFlow<List<CallHistoryEntry>> = mutableHistory.asStateFlow()

  fun load(context: Context) {
    val p = prefs(context)
    mutablePeers.value = CallPeerCodec.decode(p.getString(PEERS, null))
    mutableHistory.value = CallHistory.prune(CallHistory.decode(p.getString(HISTORY, null)), Calling.now())
    importDinnerRoster(context)
  }

  /** Rooms already known to Call everyone show up here at once (as Offline until they answer). */
  fun importDinnerRoster(context: Context) {
    val self = Calling.selfId(context)
    var list = mutablePeers.value
    DinnerCall.peers.value.filter { it.id != self }.forEach { list = CallPeers.learn(list, it.id, it.name, 0L) }
    if (list != mutablePeers.value) save(context, PEERS, CallPeerCodec.encode(list).also { mutablePeers.value = list })
  }

  /** A room spoke (hello or ring). [keepFlags]: a ring carries no presence, keep what we knew. */
  fun heard(context: Context, peer: CallPeer, keepFlags: Boolean = false) {
    if (peer.id == Calling.selfId(context)) return
    val next = mutablePeers.updateAndGet { list ->
      val old = list.firstOrNull { it.id == peer.id }
      CallPeers.merge(list, if (keepFlags && old != null) old.copy(name = peer.name, seenMs = peer.seenMs) else peer)
    }
    save(context, PEERS, CallPeerCodec.encode(next))
  }

  fun setFavourite(context: Context, id: String, on: Boolean) {
    val next = mutablePeers.updateAndGet { CallPeers.setFavourite(it, id, on) }
    save(context, PEERS, CallPeerCodec.encode(next))
  }

  fun log(context: Context, entry: CallHistoryEntry) {
    val next = mutableHistory.updateAndGet { CallHistory.add(it, entry, Calling.now()) }
    save(context, HISTORY, CallHistory.encode(next))
  }

  fun markSeen(context: Context) {
    val before = mutableHistory.value
    val next = mutableHistory.updateAndGet(CallHistory::markSeen)
    if (next != before) save(context, HISTORY, CallHistory.encode(next))
  }

  fun clearHistory(context: Context) {
    mutableHistory.value = emptyList()
    save(context, HISTORY, "[]")
  }

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun save(c: Context, key: String, value: String) {
    runCatching { prefs(c).edit().putString(key, value).apply() }.onFailure { Log.w(TAG, "could not save $key", it) }
  }
}

/** Persisted room directory; stored data is validated like anything off the wire. */
internal object CallPeerCodec {
  fun encode(list: List<CallPeer>): String = JSONArray(list.map { p ->
    JSONObject().put("id", p.id).put("n", p.name).put("s", p.seenMs).put("c", p.inCall).put("q", p.quiet)
        .put("rc", p.acceptsCalls).put("di", p.dropInsOn).put("dp", DropInGrid.encode(p.dropInPolicies)).put("f", p.favourite)
        .apply { if (p.host != null) { put("h", p.host); put("pt", p.port) } }
  }).toString()

  fun decode(raw: String?): List<CallPeer> {
    val arr = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
      val o = arr.optJSONObject(i) ?: return@mapNotNull null
      val id = o.optString("id").takeIf(DinnerWire::isDeviceId) ?: return@mapNotNull null
      val name = DinnerWire.cleanName(o.optString("n")) ?: return@mapNotNull null
      val host = o.optString("h").takeIf(HouseholdListPeerAuth::isPrivateLan)
      val port = o.optInt("pt", 0).takeIf { it in 1..65535 }
      CallPeer(id, name, o.optLong("s", 0L), o.optBoolean("c"), o.optBoolean("q"), o.optBoolean("rc", true),
          o.optBoolean("di"), DropInGrid.decode(o.optString("dp")) ?: emptyMap(), o.optBoolean("f"),
          host = host.takeIf { port != null }, port = if (host != null) port ?: 0 else 0)
    }.distinctBy { it.id }.take(CallPeers.CAPACITY)
  }
}
