/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Another Portal this one has heard on the household bus. Portals hold no roster of each other
 * (and never each other's tokens); the directory is learned from signed dinner_hello / dinner_call
 * / dinner_ack traffic and persisted so a room that is offline still gets an honest "No answer" row.
 */
internal data class DinnerPeer(val id: String, val name: String, val seenMs: Long, val quiet: Boolean?, val receive: Boolean?)

internal object DinnerPeers {
  const val CAPACITY = 8

  /** Newest first; a re-sighting replaces the old entry, keeping known flags when the new one has none. */
  fun merge(list: List<DinnerPeer>, peer: DinnerPeer): List<DinnerPeer> {
    val old = list.firstOrNull { it.id == peer.id }
    val merged = peer.copy(quiet = peer.quiet ?: old?.quiet, receive = peer.receive ?: old?.receive)
    return (listOf(merged) + list.filterNot { it.id == peer.id }).take(CAPACITY)
  }

  fun encode(list: List<DinnerPeer>): String = JSONArray(list.map { p ->
    JSONObject().put("id", p.id).put("name", p.name).put("seen", p.seenMs)
        .put("quiet", p.quiet ?: JSONObject.NULL).put("receive", p.receive ?: JSONObject.NULL)
  }).toString()

  /** Stored data is still validated: corrupt prefs must not crash the sheet. */
  fun decode(raw: String?): List<DinnerPeer> {
    val arr = runCatching { JSONArray(raw ?: "[]") }.getOrNull() ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
      val o = arr.optJSONObject(i) ?: return@mapNotNull null
      val id = o.optString("id").takeIf(DinnerWire::isDeviceId) ?: return@mapNotNull null
      val name = DinnerWire.cleanName(o.optString("name")) ?: return@mapNotNull null
      fun flag(key: String): Boolean? = (o.opt(key) as? Boolean)
      DinnerPeer(id, name, o.optLong("seen", 0L), flag("quiet"), flag("receive"))
    }.distinctBy { it.id }.take(CAPACITY)
  }
}
