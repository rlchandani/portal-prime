/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.security.SecureRandom

/** Another Portal of this home as discovery heard it: household id and the LAN address it announced. */
internal data class PortalPinPeer(val id: String, val host: String, val port: Int)

/** Posts one [PortalPinWire] body to [peer] (household-signed by the implementation): status and body, or null if unreachable. */
internal fun interface PortalPinTransport {
  fun post(peer: PortalPinPeer, body: String): Pair<Int, String?>?
}

/**
 * The typing Portal's side of a pinning over the LAN: find which household Portal hosts the
 * code's mailbox, then hello → commit → reveal → open through it ([HomeLinkJoiner]). Every
 * message is checked by the protocol itself; the transport is untrusted. Blocking: call it off
 * the main thread. Pure apart from [transport].
 */
internal class PortalPinJoin(private val transport: PortalPinTransport, private val self: HomeLinkSelf,
                             private val random: SecureRandom) {
  sealed interface Result {
    /** Both screens now show [safety]; people compare. [host] is the Portal that showed the code. */
    data class Comparing(val peer: HomeLinkPeer, val safety: HomeLinkSafetyNumber, val host: PortalPinPeer,
                         val mailbox: String) : Result
    /** No household Portal is showing this code (typo, expired, or it's on another network). */
    data object NotFound : Result
    /** The Portal showing the code didn't take it (wrong code, used, or too many tries). */
    data object NoAnswer : Result
    /** Someone broke the protocol: nothing is kept. */
    data object Tampered : Result
  }

  /** One attempt with [code] against the Portals in [peers] (ourselves skipped). */
  fun run(code: HomeLinkCode, peers: List<PortalPinPeer>): Result {
    if (code.domain != HomeLinkDomain.PORTAL_PIN) return Result.NotFound
    val mailbox = HomeLinkCode.mailboxId(code)
    val (host, hello) = findHost(mailbox, peers.filter { it.id != self.deviceId }) ?: return Result.NotFound
    var state = HomeLinkJoiner.start(code, random)
    val afterHello = HomeLinkJoiner.receive(state, self, hello, random)
    state = afterHello.state
    val commit = afterHello.outgoing ?: return Result.NoAnswer
    val reveal = send(host, mailbox, commit) as? HomeLinkMessage.Reveal ?: return Result.NoAnswer
    // The device id the host signs must be the household id we reached it under.
    if (reveal.device != host.id) return Result.Tampered
    val afterReveal = HomeLinkJoiner.receive(state, self, reveal, random)
    val comparing = afterReveal.state as? HomeLinkJoinerState.Comparing
        ?: return if (afterReveal.state is HomeLinkJoinerState.Failed) Result.Tampered else Result.NoAnswer
    val open = afterReveal.outgoing ?: return Result.Tampered
    val ack = transport.post(host, PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.SEND, mailbox, HomeLinkWire.encode(open))))
    if (ack?.first != 200) return Result.NoAnswer
    return Result.Comparing(comparing.peer, comparing.safety, host, mailbox)
  }

  /** After the pictures: swap attestation chains with [host]. Theirs, or empty if it gave none. */
  fun attest(host: PortalPinPeer, mailbox: String, ours: List<ByteArray>): List<ByteArray> {
    if (ours.isEmpty()) return emptyList()
    val body = PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.ATTEST, mailbox, chain = ours.take(PortalAttestation.MAX_CHAIN)))
    val (status, text) = transport.post(host, body) ?: return emptyList()
    if (status != 200) return emptyList()
    return PortalPinWire.parseReply(text)?.chain.orEmpty()
  }

  private fun findHost(mailbox: String, peers: List<PortalPinPeer>): Pair<PortalPinPeer, HomeLinkMessage.Hello>? {
    val ask = PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.HELLO, mailbox))
    for (peer in peers.take(MAX_PEERS)) {
      val (status, text) = transport.post(peer, ask) ?: continue
      if (status != 200) continue
      val hello = PortalPinWire.parseReply(text)?.message?.let(HomeLinkWire::parse) as? HomeLinkMessage.Hello ?: continue
      if (hello.mailbox == mailbox) return peer to hello
    }
    return null
  }

  private fun send(host: PortalPinPeer, mailbox: String, m: HomeLinkMessage): HomeLinkMessage? {
    val body = PortalPinWire.encode(PortalPinWire.Request(PortalPinWire.Op.SEND, mailbox, HomeLinkWire.encode(m)))
    val (status, text) = transport.post(host, body) ?: return null
    if (status != 200) return null
    return PortalPinWire.parseReply(text)?.message?.let(HomeLinkWire::parse)?.takeIf { it.mailbox == mailbox }
  }

  companion object {
    /** A home has a handful of Portals; asking more than this many for a mailbox is a bad roster. */
    const val MAX_PEERS = 16
  }
}
