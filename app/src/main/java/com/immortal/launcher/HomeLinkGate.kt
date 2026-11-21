/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * What of linking homes runs. The vault ([HomeLinkSync], `/remote/homelinkpeer`) carries the
 * household identity's private key, and paired phones hold the household key, so the household
 * key alone must never be enough to get it (calling-phase-2.md, "Portal-only device keys").
 *
 * - [syncs] is the **security condition**: the vault moves only between Portals whose Keystore
 *   device keys are pinned in each other's [PortalRoster] (pinned in person, never via a phone).
 *   A Portal that pinned nobody neither asks, answers nor signals; per request the wire refuses
 *   any device key that isn't pinned ([HomeLinkSyncWire.admit]).
 * - [VAULT_SYNC] is the **build switch**: still measurement-only (`-PportalCallLoopback=true`)
 *   until the device-key exchange and the in-person pinning step are reviewed and shipped. Once
 *   they are, it can become `true` everywhere, because [syncs] no longer depends on the build.
 */
internal object HomeLinkGate {
  /** Register `/remote/homelinkpeer` and start [HomeLinkSync]. */
  val VAULT_SYNC: Boolean = BuildConfig.PORTAL_CALL_LOOPBACK

  /** Sync the vault at all, given this Portal's pinned Portals. */
  fun syncs(roster: PortalRoster, buildSwitch: Boolean = VAULT_SYNC): Boolean = buildSwitch && !roster.isEmpty
}
