/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * The LAN mailbox the Portal showing a pinning code hosts ([PortalPinWire]), in memory only.
 * The same relay rules as calling-phase-2.md S4, enforced here because there is no relay:
 *
 * - one box per mailbox id, opened by our own inviter only (a remote peer can't open one), with a
 *   short life ([TTL_MS], never past the invite's own expiry);
 * - at most [MAX_JOINS] commits per box, whatever they hold; the next closes it (the inviter's own
 *   3-try limit stands as well);
 * - once a commit gets an answer, only that Portal (household id) may send the open or swap
 *   attestation chains, and only once each;
 * - one message ≤ [HomeLinkWire.MAX_BYTES]; at most [MAX_BOXES] boxes;
 * - misses (asking for a box that isn't here) are rate-limited per caller ([MAX_MISSES] a minute),
 *   so walking the 1,024 pinning nameplates is slow and shows in the log.
 *
 * Thread-safe (one lock); the inviter session itself is also locked ([HomeLinkInviterSession]).
 */
internal class PortalPinMailbox(private val clock: () -> Long) {
  /** What our own Portal does with a box's messages: its inviter, its chain, and where the other's chain goes. */
  class Host(val session: HomeLinkInviterSession, val ownChain: () -> List<ByteArray>,
             val onPeerChain: (from: String, chain: List<ByteArray>) -> Unit)

  private data class Box(val host: Host, val hello: String, val expiresAt: Long, val joins: Int = 0,
                         val joiner: String? = null, val opened: Boolean = false, val attested: Boolean = false)

  /** The HTTP answer for one request: status, error code (null on 200) and the reply body. */
  data class Outcome(val status: Int, val error: String? = null, val reply: PortalPinWire.Reply? = null)

  private val lock = Any()
  private var boxes: Map<String, Box> = emptyMap()
  private var misses: Map<String, List<Long>> = emptyMap()

  /** Our inviter starts showing a code: its hello waits in box [HomeLinkMessage.mailbox]. False if that box is taken or we're full. */
  fun open(host: Host, inviteExpiresAt: Long): Boolean = synchronized(lock) {
    prune()
    val hello = host.session.hello
    val id = hello.mailbox
    if (id in boxes || boxes.size >= MAX_BOXES) return false
    boxes = boxes + (id to Box(host, HomeLinkWire.encode(hello), minOf(inviteExpiresAt, clock() + TTL_MS)))
    true
  }

  /** The screen left the code (done, cancelled, new code): the box and anything in it is gone. */
  fun close(mailbox: String) = synchronized(lock) { boxes = boxes - mailbox }

  fun isOpen(mailbox: String): Boolean = synchronized(lock) { prune(); mailbox in boxes }

  fun handle(from: String, request: PortalPinWire.Request): Outcome = synchronized(lock) {
    prune()
    val box = boxes[request.mailbox] ?: return miss(from)
    when (request.op) {
      PortalPinWire.Op.HELLO -> Outcome(200, reply = PortalPinWire.Reply(message = box.hello))
      PortalPinWire.Op.SEND -> send(box, from, request)
      PortalPinWire.Op.ATTEST -> attest(box, from, request)
    }
  }

  private fun send(box: Box, from: String, request: PortalPinWire.Request): Outcome {
    val text = request.message ?: return Outcome(400, "bad_message")
    val m = HomeLinkWire.parse(text)?.takeIf { it.mailbox == request.mailbox } ?: return Outcome(400, "bad_message")
    return when (m) {
      is HomeLinkMessage.Commit -> commit(box, from, m)
      is HomeLinkMessage.Open -> openMessage(box, from, m)
      else -> Outcome(400, "unexpected")
    }
  }

  private fun commit(box: Box, from: String, m: HomeLinkMessage.Commit): Outcome {
    val id = m.mailbox
    if (box.joiner != null) return Outcome(409, "in_use")
    if (box.joins >= MAX_JOINS) { boxes = boxes - id; return Outcome(429, "too_many_joins") }
    val step = box.host.session.receive(m, clock())
    val reveal = step.outgoing
    val next = box.copy(joins = box.joins + 1, joiner = if (reveal != null) from else null)
    // A burned or expired invite has nothing more to give: close the box with it.
    boxes = if (step.state is HomeLinkInviterState.Failed) boxes - id else boxes + (id to next)
    return if (reveal == null) Outcome(403, "no_answer") else Outcome(200, reply = PortalPinWire.Reply(HomeLinkWire.encode(reveal)))
  }

  private fun openMessage(box: Box, from: String, m: HomeLinkMessage.Open): Outcome {
    if (box.joiner != from || box.opened) return Outcome(403, "not_joiner")
    // The device id it signs must be the household id it called from (the announced id it is pinned to).
    if (m.device != from) return Outcome(403, "device_mismatch")
    val step = box.host.session.receive(m, clock())
    if (step.state !is HomeLinkInviterState.Comparing) {
      if (step.state is HomeLinkInviterState.Failed) boxes = boxes - m.mailbox
      return Outcome(403, "no_answer")
    }
    boxes = boxes + (m.mailbox to box.copy(opened = true))
    return Outcome(200, reply = PortalPinWire.Reply())
  }

  private fun attest(box: Box, from: String, request: PortalPinWire.Request): Outcome {
    if (box.joiner != from || !box.opened || box.attested) return Outcome(403, "not_joiner")
    boxes = boxes + (request.mailbox to box.copy(attested = true))
    box.host.onPeerChain(from, request.chain)
    return Outcome(200, reply = PortalPinWire.Reply(chain = box.host.ownChain().take(PortalAttestation.MAX_CHAIN)))
  }

  private fun miss(from: String): Outcome {
    val now = clock()
    val recent = misses[from].orEmpty().filter { now - it < MISS_WINDOW_MS }
    if (recent.size >= MAX_MISSES) return Outcome(429, "slow_down")
    misses = (misses + (from to recent + now)).let { m -> if (m.size > MAX_CALLERS) m.entries.drop(1).associate { it.toPair() } else m }
    return Outcome(404, "no_mailbox")
  }

  private fun prune() {
    val now = clock()
    if (boxes.values.any { now >= it.expiresAt }) boxes = boxes.filterValues { now < it.expiresAt }
  }

  companion object {
    const val MAX_JOINS = 3
    const val MAX_BOXES = 2
    const val TTL_MS = 5 * 60_000L
    const val MAX_MISSES = 20
    const val MISS_WINDOW_MS = 60_000L
    private const val MAX_CALLERS = 64
  }
}
