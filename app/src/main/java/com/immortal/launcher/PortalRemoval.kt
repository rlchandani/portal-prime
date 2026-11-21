/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * "Portal [kid] (household id [deviceId]) was removed from this home at [at]", signed by the
 * device key of the Portal that removed it ([by]). Travels inside the [HomeLinkVault] at
 * revision [rev] like the vault's other removal markers, so every Portal that pinned [by] unpins
 * [kid] too (calling-phase-2.md, "Removing a Portal"). Public data only.
 */
internal data class PortalRemoval(val kid: String, val deviceId: String, val at: Long, val rev: Long, val by: String, val sig: String) {
  override fun toString(): String = "PortalRemoval($kid by $by, r$rev)"
}

/** Signing, checking, merging and coding [PortalRemoval]s. Pure. */
internal object PortalRemovals {
  /** The vault keeps at most this many (newest first); a home has ≤ [PortalRoster.MAX_PINS] Portals. */
  const val MAX = 32
  private const val LABEL = "hearthpane-portal-remove-v1"
  private const val MAX_SIG = 200
  private val KID = Regex("^[0-9a-f]{16}$")

  /** [device]'s signed marker that [pin] was removed at [at], at vault revision [rev]. */
  fun sign(device: HomeLinkSigner, pin: PortalPin, at: Long, rev: Long): PortalRemoval {
    val by = HomeLinkIdentity.fingerprint(device.publicKey)
    return PortalRemoval(pin.kid, pin.deviceId, at, rev, by, device.sign(text(pin.kid, pin.deviceId, at, rev, by)))
  }

  /**
   * The pins in [roster] that [removals] remove: each marker must be signed by a Portal we pinned
   * (or by us, [selfKey]), name a pin we hold under the same household id, never us, never its own
   * signer, and be newer than that pin (a Portal paired again in person after its removal stays).
   * Checked against [roster] as it is, so the order of markers doesn't matter.
   */
  fun toUnpin(roster: PortalRoster, removals: Map<String, PortalRemoval>, selfKey: String): Set<String> {
    val selfKid = HomeLinkIdentity.fingerprint(selfKey)
    return removals.values.filter { r ->
      val pin = roster.pins[r.kid]
      val signer = if (r.by == selfKid) selfKey else roster.keyFor(r.by)
      r.kid != selfKid && r.by != r.kid && pin != null && pin.deviceId == r.deviceId && pin.pinnedAt <= r.at && signer != null &&
          HomeLinkIdentity.verify(signer, text(r.kid, r.deviceId, r.at, r.rev, r.by), r.sig)
    }.map { it.kid }.toSet()
  }

  /** One marker per removed Portal: the higher revision, then the smaller signer (commutative, idempotent), newest [MAX] kept. */
  fun merge(a: Map<String, PortalRemoval>, b: Map<String, PortalRemoval>): Map<String, PortalRemoval> {
    val merged = (a.keys + b.keys).associateWith { kid -> listOfNotNull(a[kid], b[kid]).minWith(ORDER) }
    return capped(merged)
  }

  fun capped(m: Map<String, PortalRemoval>): Map<String, PortalRemoval> =
      if (m.size <= MAX) m else m.values.sortedWith(compareByDescending<PortalRemoval> { it.rev }.thenBy { it.kid }).take(MAX).associateBy { it.kid }

  fun json(r: PortalRemoval): JSONObject =
      JSONObject().put("k", r.kid).put("d", r.deviceId).put("at", r.at).put("r", r.rev).put("by", r.by).put("sig", r.sig)

  /** A stored or synced marker, or null for anything off (the signature is checked only in [toUnpin]). */
  fun parse(o: JSONObject?): PortalRemoval? {
    val kid = (o?.opt("k") as? String)?.takeIf { KID.matches(it) } ?: return null
    val dev = (o.opt("d") as? String)?.takeIf(HomeLinkWire::validDevice) ?: return null
    val at = (o.opt("at") as? Number)?.toLong()?.takeIf { it >= 0 } ?: return null
    val rev = (o.opt("r") as? Number)?.toLong()?.takeIf { it > 0 } ?: return null
    val by = (o.opt("by") as? String)?.takeIf { KID.matches(it) } ?: return null
    val sig = (o.opt("sig") as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_SIG } ?: return null
    return PortalRemoval(kid, dev, at, rev, by, sig)
  }

  private val ORDER = compareByDescending<PortalRemoval> { it.rev }.thenBy { it.by }.thenBy { it.sig }

  private fun text(kid: String, dev: String, at: Long, rev: Long, by: String): ByteArray =
      HomeLinkHash.join(*listOf(LABEL, kid, dev, at.toString(), rev.toString(), by).map(HomeLinkHash::utf8).toTypedArray())
}

/**
 * "Remove this Portal" and what the other Portals do with it. [remove] unpins here first (so the
 * removed Portal can't pull what comes next), then stores a signed marker and a rotated identity
 * in the vault in one change, which sync carries to every Portal still pinned. [apply] runs on each
 * pulled vault *before* it is merged, so no Portal holds the new identity while still pinning the
 * removed one. Never logs keys.
 */
internal class PortalRemover(
    private val store: PortalRosterStore,
    private val repo: HomeLinkRepository,
    private val device: HomeLinkSigner,
    private val clock: () -> Long,
) {
  /** True once [kid] is unpinned here and the marker (and new identity) are stored. False if it wasn't pinned or storing failed. */
  fun remove(kid: String): Boolean {
    val pin = store.roster.pins[kid] ?: return false
    if (!store.unpin(kid)) return false
    val at = clock()
    return repo.removePortal(at) { rev -> PortalRemovals.sign(device, pin, at, rev) }
  }

  companion object {
    /** Unpins what [vault]'s verified markers remove from [store]'s roster; the number unpinned (idempotent). */
    fun apply(store: PortalRosterStore, vault: HomeLinkVault, selfKey: String): Int {
      val kids = PortalRemovals.toUnpin(store.roster, vault.removals, selfKey)
      return if (kids.isNotEmpty() && store.unpinAll(kids)) kids.size else 0
    }
  }
}
