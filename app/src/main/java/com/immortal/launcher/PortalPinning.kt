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
  /** This Portal as it introduces itself in a pinning: its device key and its room name. */
  fun self(device: HomeLinkSigner, portalName: String): HomeLinkSelf = HomeLinkSelf(device, portalName)

  /** People said the pictures match: pin [peer]. False if refused (bad key, ourselves, full) or not stored. */
  fun confirm(store: PortalRosterStore, peer: HomeLinkPeer, device: HomeLinkSigner, now: Long): Boolean =
      store.pin(peer, device.publicKey, now)
}
