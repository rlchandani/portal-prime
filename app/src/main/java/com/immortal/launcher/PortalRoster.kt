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
 * [deviceId] is the household device id the other Portal signed during pinning: one key per id,
 * one id per key. [attestation] is what its Keystore attestation chain showed (recorded, not
 * required). Public key material only.
 */
internal data class PortalPin(val kid: String, val name: String, val publicKey: String, val pinnedAt: Long,
                              val deviceId: String = "", val attestation: PortalAttestationLevel = PortalAttestationLevel.UNCHECKED,
                              /** A certificate in its attestation chain was outside its validity dates (flagged, never blocking). */
                              val attestationOutOfDate: Boolean = false)

/** What a pin attempt would do. */
internal enum class PortalPinOutcome {
  PINNED,
  /** This key is already pinned for this Portal: nothing changes. */
  ALREADY_PINNED,
  /** This Portal (device id) is pinned with another key, or this key with another id: only an explicit re-pair replaces it. */
  NEEDS_REPAIR,
  /** Bad key, name or device id, our own key or id, or a full roster. */
  REFUSED,
  /** Allowed, but the sealed file couldn't be written. */
  NOT_STORED,
}

/**
 * The Portals this Portal will share the household vault with. Only the in-person pinning step
 * adds to it (never a sync, never a phone), so the household key alone can't extend it. Pure.
 */
internal data class PortalRoster(val pins: Map<String, PortalPin> = emptyMap()) {
  val isEmpty: Boolean get() = pins.isEmpty()

  /** The pinned key for [kid], or null. */
  fun keyFor(kid: String): String? = pins[kid]?.publicKey

  /** The pin bound to household device [id], or null. */
  fun byDevice(id: String): PortalPin? = pins.values.firstOrNull { it.deviceId == id }

  /** Pins sorted by name, for the list on screen. */
  val sorted: List<PortalPin> get() = pins.values.sortedWith(compareBy({ it.name.lowercase() }, { it.kid }))

  /** What pinning [peer] would do, without changing anything. [selfDevice] is our own household id. */
  fun check(peer: HomeLinkPeer, selfKey: String, selfDevice: String = ""): PortalPinOutcome {
    if (!HomeLinkWire.validName(peer.name) || !HomeLinkWire.validDevice(peer.deviceId)) return PortalPinOutcome.REFUSED
    if (peer.publicKey == selfKey || peer.deviceId == selfDevice) return PortalPinOutcome.REFUSED
    HomeLinkIdentity.decodePublic(peer.publicKey) ?: return PortalPinOutcome.REFUSED
    val kid = HomeLinkIdentity.fingerprint(peer.publicKey)
    val sameKey = pins[kid]
    val sameDevice = byDevice(peer.deviceId)
    return when {
      sameKey != null && sameKey.publicKey == peer.publicKey && sameKey.deviceId == peer.deviceId -> PortalPinOutcome.ALREADY_PINNED
      sameKey != null || sameDevice != null -> PortalPinOutcome.NEEDS_REPAIR
      pins.size >= MAX_PINS -> PortalPinOutcome.REFUSED
      else -> PortalPinOutcome.PINNED
    }
  }

  /**
   * [peer] after people confirmed the pairing. Null (nothing changes) when [check] refuses, or
   * says [PortalPinOutcome.NEEDS_REPAIR] and this isn't an explicit [repair]; a re-pair first
   * forgets the old key for that device id (and the old id for that key). Pinning the same key
   * for the same device again keeps the first pin.
   */
  fun pin(peer: HomeLinkPeer, selfKey: String, now: Long, selfDevice: String = "", repair: Boolean = false): PortalRoster? {
    val kid = HomeLinkIdentity.fingerprint(peer.publicKey)
    return when (check(peer, selfKey, selfDevice)) {
      PortalPinOutcome.ALREADY_PINNED -> this
      PortalPinOutcome.PINNED -> copy(pins = pins + (kid to PortalPin(kid, peer.name, peer.publicKey, now, peer.deviceId)))
      PortalPinOutcome.NEEDS_REPAIR -> if (!repair) null else {
        val kept = pins.filterValues { it.kid != kid && it.deviceId != peer.deviceId }
        copy(pins = kept + (kid to PortalPin(kid, peer.name, peer.publicKey, now, peer.deviceId)))
      }
      PortalPinOutcome.REFUSED, PortalPinOutcome.NOT_STORED -> null
    }
  }

  /** Records what [kid]'s attestation chain showed. Unknown kid: unchanged. */
  fun attested(kid: String, level: PortalAttestationLevel, outOfDate: Boolean = false): PortalRoster =
      pins[kid]?.let { copy(pins = pins + (kid to it.copy(attestation = level, attestationOutOfDate = outOfDate))) } ?: this

  /** Forgets a Portal (it was removed from the home): it gets nothing from here again. */
  fun unpin(kid: String): PortalRoster = copy(pins = pins - kid)

  override fun toString(): String = "PortalRoster(${pins.keys.sorted()})"

  companion object {
    /** A home has a handful of Portals; the cap only bounds what a bad file can grow to. */
    const val MAX_PINS = 16
  }
}

/** `{v, pins: [{key, name, at, dev, att, old?}]}`. Untrusted on the way in: anything off is dropped. Pure. */
internal object PortalRosterCodec {
  private const val VERSION = 1

  fun encode(r: PortalRoster): String = JSONObject().put("v", VERSION).put("pins", JSONArray().apply {
    r.pins.values.sortedBy { it.kid }.forEach {
      put(JSONObject().put("key", it.publicKey).put("name", it.name).put("at", it.pinnedAt).put("dev", it.deviceId)
          .put("att", it.attestation.name).apply { if (it.attestationOutOfDate) put("old", true) })
    }
  }).toString()

  /** A decoded file: the roster, and how many pins were dropped for having no device id (saved by older test builds). */
  data class Decoded(val roster: PortalRoster, val withoutDevice: Int)

  /** The roster, or null for a file that isn't one (wrong version, not JSON). Bad pins (and a second pin for one device) are skipped. */
  fun parse(text: String?): PortalRoster? = decode(text)?.roster

  /** Like [parse], also counting the pins dropped for having no device id (a count only: nothing about them is kept). */
  fun decode(text: String?): Decoded? {
    val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    if (o.opt("v") != VERSION) return null
    val arr = o.optJSONArray("pins") ?: return null
    val raw = (0 until minOf(arr.length(), PortalRoster.MAX_PINS)).map { i -> arr.optJSONObject(i) }
    val withoutDevice = raw.count { it != null && !it.has("dev") }
    val pins = raw.mapNotNull(::pin).distinctBy { it.deviceId }
    return Decoded(PortalRoster(pins.associateBy { it.kid }), withoutDevice)
  }

  private fun pin(o: JSONObject?): PortalPin? {
    val key = o?.opt("key") as? String ?: return null
    val name = o.opt("name") as? String ?: return null
    val at = (o.opt("at") as? Number)?.toLong() ?: return null
    val dev = (o.opt("dev") as? String)?.takeIf(HomeLinkWire::validDevice) ?: return null
    val att = PortalAttestationLevel.entries.firstOrNull { it.name == o.opt("att") } ?: PortalAttestationLevel.UNCHECKED
    if (!HomeLinkWire.validName(name) || HomeLinkIdentity.decodePublic(key) == null) return null
    return PortalPin(HomeLinkIdentity.fingerprint(key), name, key, at, dev, att, attestationOutOfDate = o.opt("old") == true)
  }
}
