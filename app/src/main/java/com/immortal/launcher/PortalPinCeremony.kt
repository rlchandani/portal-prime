/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.SecureRandom

/**
 * One Portal's side of "Pair this Portal with another Portal", behind the screens: shows a code
 * (hosting its mailbox) or types one ([PortalPinJoin]), pins the other Portal's device key when
 * people say the pictures match ([PortalPinning.confirm]), then swaps and records attestation
 * chains ([PortalAttestation]). Everything Android lives in the injected parts, so this runs on
 * the JVM. [join] and [confirm] block on the network: call them off the main thread.
 */
internal class PortalPinCeremony(
    private val self: HomeLinkSelf,
    private val store: PortalRosterStore,
    private val mailbox: PortalPinMailbox,
    private val transport: PortalPinTransport,
    private val peers: () -> List<PortalPinPeer>,
    private val ownChain: () -> List<ByteArray>,
    private val random: SecureRandom,
    private val clock: () -> Long,
    private val challenge: ByteArray? = null,
) {
  private val lock = Any()
  private var session: HomeLinkInviterSession? = null
  private var joined: PortalPinJoin.Result.Comparing? = null
  /** The chain the typing Portal sent, by its household id, until (or after) we pin it. */
  private var peerChain: Pair<String, List<ByteArray>>? = null
  private var pinned: HomeLinkPeer? = null

  /**
   * Ends any live code and shows a new one, its mailbox open on our route. The mailbox is never
   * called under [lock] (its chain callback takes [lock] from the route's thread).
   */
  fun show(): HomeLinkInvite {
    end()
    val invite = HomeLinkInvite(HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN), clock(), HomeLinkInvite.PIN_LIFETIME_MS)
    val next = HomeLinkInviterSession.start(invite, self, random)
    synchronized(lock) { session = next }
    mailbox.open(PortalPinMailbox.Host(next, ownChain, ::onPeerChain), invite.expiresAt)
    return invite
  }

  /** The showing side's protocol state (expires it when its time is up), or null if no code is up. */
  fun inviterState(): HomeLinkInviterState? = session?.tick(clock())

  /** The typing side's one LAN round with [code]. */
  fun join(code: HomeLinkCode): PortalPinJoin.Result {
    val result = PortalPinJoin(transport, self, random).run(code, peers())
    synchronized(lock) { joined = result as? PortalPinJoin.Result.Comparing }
    return result
  }

  /**
   * "They match" on [side]: pin the other Portal (an explicit [repair] replaces a different key
   * for the same Portal). The peer pinned is the protocol's own, never one passed in by a screen.
   */
  fun confirm(side: PortalPinSide, repair: Boolean = false): PortalPinOutcome {
    val peer = when (side) {
      PortalPinSide.SHOWED_CODE -> synchronized(lock) {
        val s = session ?: return PortalPinOutcome.REFUSED
        (s.state as? HomeLinkInviterState.Comparing)?.let { s.confirm(true) }
        (s.state as? HomeLinkInviterState.Linked)?.peer
      }
      PortalPinSide.TYPED_CODE -> synchronized(lock) { joined?.peer }
    } ?: return PortalPinOutcome.REFUSED
    val outcome = PortalPinning.confirm(store, peer, self, clock(), repair)
    if (outcome == PortalPinOutcome.PINNED || outcome == PortalPinOutcome.ALREADY_PINNED) attest(side, peer)
    return outcome
  }

  /** "They don't match", Cancel, or leaving: nothing is kept and the code stops working. */
  fun end() {
    val ended = synchronized(lock) {
      val s = session
      session = null
      joined = null
      peerChain = null
      pinned = null
      s
    } ?: return
    if (ended.state is HomeLinkInviterState.Comparing) ended.confirm(false)
    mailbox.close(ended.hello.mailbox)
  }

  private fun attest(side: PortalPinSide, peer: HomeLinkPeer) {
    when (side) {
      PortalPinSide.TYPED_CODE -> {
        val host = synchronized(lock) { joined } ?: return
        val theirs = PortalPinJoin(transport, self, random).attest(host.host, host.mailbox, ownChain())
        record(peer, theirs)
      }
      PortalPinSide.SHOWED_CODE -> {
        val chain = synchronized(lock) {
          pinned = peer
          peerChain?.takeIf { it.first == peer.deviceId }?.second
        }
        if (chain != null) record(peer, chain)
      }
    }
  }

  /** The typing Portal's chain arrived through our mailbox: record it now if we already pinned it, else keep it for [confirm]. */
  private fun onPeerChain(from: String, chain: List<ByteArray>) {
    val peer = synchronized(lock) {
      peerChain = from to chain
      pinned?.takeIf { it.deviceId == from }
    }
    if (peer != null) record(peer, chain)
  }

  private fun record(peer: HomeLinkPeer, chain: List<ByteArray>) {
    val result = if (chain.isEmpty()) PortalAttestationCheck(PortalAttestationLevel.UNCHECKED, outOfDate = false)
    else PortalAttestation.check(chain, peer.publicKey, challenge = challenge, now = clock())
    store.attested(HomeLinkIdentity.fingerprint(peer.publicKey), result.level, result.outOfDate)
  }
}
