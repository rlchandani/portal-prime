/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Pinning another Portal in this home, in person (calling-phase-2.md, "Portal-only device keys").
 * It is the home-linking protocol ([HomeLinkInviter]/[HomeLinkJoiner]: code on one Portal's screen
 * typed on the other, PAKE, commit-then-reveal, then the four pictures + 12 digits) run with each
 * Portal's [PortalDeviceKey] instead of the household identity. Both Portals stand in the same
 * room, so people compare the pictures side by side.
 *
 * Why a phone can't take part: the code is shown only on a Portal's screen and the PAKE caps
 * guesses at 3 per code, so the household key (which phones hold) doesn't help; and the key that
 * ends up pinned is the one that signed the reveal/open, which for a Portal never leaves its
 * Keystore. Nothing here syncs a roster: every Portal pins each other Portal it shares with.
 */
internal object PortalPinning {
  /** This Portal as it introduces itself in a pinning: its device key, room name and household device id. */
  fun self(device: HomeLinkSigner, portalName: String, deviceId: String): HomeLinkSelf = HomeLinkSelf(device, portalName, deviceId)

  /**
   * People said the pictures match: pin [peer] to the device id it signed. A Portal already pinned
   * with another key needs [repair] (an explicit "Pair again"); see [PortalPinOutcome].
   */
  fun confirm(store: PortalRosterStore, peer: HomeLinkPeer, self: HomeLinkSelf, now: Long, repair: Boolean = false): PortalPinOutcome =
      store.pin(peer, self.identity.publicKey, now, self.deviceId, repair)
}
