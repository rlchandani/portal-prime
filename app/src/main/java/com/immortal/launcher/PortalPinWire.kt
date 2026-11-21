/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

/**
 * `POST /remote/portalpin`: the LAN mailbox two Portals of one home pin each other through
 * ([PortalPinning]). No relay: the Portal showing the code hosts the mailbox ([PortalPinMailbox]),
 * the Portal typing it asks each household Portal for that mailbox's hello, then posts its
 * commit/open there and gets the reply in the response.
 *
 * Body `{v, op, b, m?, c?}`: [Op.HELLO] reads the host's hello for mailbox `b`; [Op.SEND] posts
 * one [HomeLinkWire] message `m`; [Op.ATTEST] swaps attestation chains `c` (Base64 DER, leaf first)
 * once the pictures are showing. The reply is `{v, m?, c?}`.
 *
 * The household `portal_pin` header (signed, fresh, not replayed, pins the body hash, from a
 * Portal id at its announced address) is an **outer filter only**: phones hold the household key.
 * What makes pinning safe is the PAKE and the pictures inside [HomeLinkWire] messages.
 */
internal object PortalPinWire {
  const val PATH = "/remote/portalpin"
  const val HEADER = HouseholdListPeerAuth.HEADER
  const val REQUEST_TYPE = "portal_pin"
  private const val VERSION = 1
  private const val CERT_B64_MAX = (PortalAttestation.MAX_CERT_BYTES + 2) / 3 * 4
  /** One pairing message, or a full attestation chain plus framing. */
  const val MAX_BODY = PortalAttestation.MAX_CHAIN * (CERT_B64_MAX + 8) + HomeLinkWire.MAX_BYTES
  private val MAILBOX = Regex("^[0-9a-f]{32}$")

  enum class Op(val wire: String) { HELLO("hello"), SEND("send"), ATTEST("attest") }

  data class Request(val op: Op, val mailbox: String, val message: String? = null, val chain: List<ByteArray> = emptyList())

  data class Reply(val message: String? = null, val chain: List<ByteArray> = emptyList())

  fun encode(r: Request): String {
    val o = JSONObject().put("v", VERSION).put("op", r.op.wire).put("b", r.mailbox)
    r.message?.let { o.put("m", it) }
    if (r.chain.isNotEmpty()) o.put("c", chainJson(r.chain))
    return o.toString()
  }

  /** Strict: version, a known op, a mailbox id, and what that op needs. Null for anything else. */
  fun parse(text: String): Request? {
    val o = obj(text) ?: return null
    val op = Op.entries.firstOrNull { it.wire == o.opt("op") } ?: return null
    val mailbox = (o.opt("b") as? String)?.takeIf { MAILBOX.matches(it) } ?: return null
    return when (op) {
      Op.HELLO -> Request(op, mailbox)
      Op.SEND -> Request(op, mailbox, message = (o.opt("m") as? String)?.takeIf { it.length <= HomeLinkWire.MAX_BYTES } ?: return null)
      Op.ATTEST -> Request(op, mailbox, chain = chain(o.opt("c")) ?: return null)
    }
  }

  fun encodeReply(r: Reply): String {
    val o = JSONObject().put("v", VERSION)
    r.message?.let { o.put("m", it) }
    if (r.chain.isNotEmpty()) o.put("c", chainJson(r.chain))
    return o.toString()
  }

  fun parseReply(text: String?): Reply? {
    val o = text?.let(::obj) ?: return null
    val m = o.opt("m")?.let { v -> (v as? String)?.takeIf { it.length <= HomeLinkWire.MAX_BYTES } ?: return null }
    val c = if (o.has("c")) chain(o.opt("c")) ?: return null else emptyList()
    return Reply(m, c)
  }

  fun requestHeader(key: ByteArray, from: String, nonce: String, ts: Long, body: String): String? =
      HouseholdMessage.encodeOrNull(HouseholdMessage(REQUEST_TYPE, from, ts, nonce, JSONObject().put("s", sha256(body))), key)

  /** The household-verified caller id, or null for a missing/forged/stale/replayed header or a swapped body. */
  fun verifyRequest(header: String?, body: String, key: ByteArray, nowMs: Long, replay: HouseholdMessage.ReplayGuard,
                    self: String?): String? {
    if (header == null) return null
    val msg = HouseholdMessage.decode(header, key, nowMs, replay) ?: return null
    if (msg.type != REQUEST_TYPE || msg.from == self) return null
    val pinned = msg.body.opt("s") as? String ?: return null
    if (!MessageDigest.isEqual(pinned.toByteArray(), sha256(body).toByteArray())) return null
    return msg.from
  }

  private fun chainJson(chain: List<ByteArray>): JSONArray = JSONArray().apply { chain.forEach { put(HomeLinkHash.b64(it)) } }

  private fun chain(v: Any?): List<ByteArray>? {
    val arr = v as? JSONArray ?: return null
    if (arr.length() !in 1..PortalAttestation.MAX_CHAIN) return null
    return (0 until arr.length()).map { i ->
      val text = (arr.opt(i) as? String)?.takeIf { it.length <= CERT_B64_MAX } ?: return null
      HomeLinkHash.unb64(text)?.takeIf { it.isNotEmpty() && it.size <= PortalAttestation.MAX_CERT_BYTES } ?: return null
    }
  }

  private fun obj(text: String): JSONObject? {
    if (text.length > MAX_BODY) return null
    val o = try { JSONObject(text) } catch (e: JSONException) { return null }
    return o.takeIf { it.opt("v") == VERSION }
  }

  private fun sha256(text: String): String = HomeLinkHash.hex(HomeLinkHash.sha256(HomeLinkHash.utf8(text)))
}
