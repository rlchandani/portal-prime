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
 * Another Portal in this home whose [PortalDeviceKey] we pinned in person (calling-phase-2.md,
 * "Portal-only device keys"). [kid] is the key's [HomeLinkIdentity.fingerprint]; lookups by kid
 * always return the pinned key, so a colliding fingerprint still can't sign as this Portal.
 * Public key material only.
 */
internal data class PortalPin(val kid: String, val name: String, val publicKey: String, val pinnedAt: Long)

/**
 * The Portals this Portal will share the household vault with. Only the in-person pinning step
 * adds to it (never a sync, never a phone), so the household key alone can't extend it. Pure.
 */
internal data class PortalRoster(val pins: Map<String, PortalPin> = emptyMap()) {
  val isEmpty: Boolean get() = pins.isEmpty()

  /** The pinned key for [kid], or null. */
  fun keyFor(kid: String): String? = pins[kid]?.publicKey

  /**
   * [peer] after people confirmed the pairing. Null (nothing changes) for an invalid key or name,
   * our own key [selfKey], or a full roster. Pinning the same key again keeps the first pin.
   */
  fun pin(peer: HomeLinkPeer, selfKey: String, now: Long): PortalRoster? {
    if (!HomeLinkWire.validName(peer.name) || peer.publicKey == selfKey) return null
    HomeLinkIdentity.decodePublic(peer.publicKey) ?: return null
    val kid = HomeLinkIdentity.fingerprint(peer.publicKey)
    if (pins[kid]?.publicKey == peer.publicKey) return this
    if (kid !in pins && pins.size >= MAX_PINS) return null
    return copy(pins = pins + (kid to PortalPin(kid, peer.name, peer.publicKey, now)))
  }

  /** Forgets a Portal (it was removed from the home): it gets nothing from here again. */
  fun unpin(kid: String): PortalRoster = copy(pins = pins - kid)

  override fun toString(): String = "PortalRoster(${pins.keys.sorted()})"

  companion object {
    /** A home has a handful of Portals; the cap only bounds what a bad file can grow to. */
    const val MAX_PINS = 16
  }
}

/** `{v, pins: [{key, name, at}]}`. Untrusted on the way in: anything off is dropped. Pure. */
internal object PortalRosterCodec {
  private const val VERSION = 1

  fun encode(r: PortalRoster): String = JSONObject().put("v", VERSION).put("pins", JSONArray().apply {
    r.pins.values.sortedBy { it.kid }.forEach { put(JSONObject().put("key", it.publicKey).put("name", it.name).put("at", it.pinnedAt)) }
  }).toString()

  /** The roster, or null for a file that isn't one (wrong version, not JSON). Bad pins are skipped. */
  fun parse(text: String?): PortalRoster? {
    val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    if (o.opt("v") != VERSION) return null
    val arr = o.optJSONArray("pins") ?: return null
    val pins = (0 until minOf(arr.length(), PortalRoster.MAX_PINS)).mapNotNull { i -> pin(arr.optJSONObject(i)) }
    return PortalRoster(pins.associateBy { it.kid })
  }

  private fun pin(o: JSONObject?): PortalPin? {
    val key = o?.opt("key") as? String ?: return null
    val name = o.opt("name") as? String ?: return null
    val at = (o.opt("at") as? Number)?.toLong() ?: return null
    if (!HomeLinkWire.validName(name) || HomeLinkIdentity.decodePublic(key) == null) return null
    return PortalPin(HomeLinkIdentity.fingerprint(key), name, key, at)
  }
}
