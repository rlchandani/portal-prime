/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import java.net.URI
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `POST /remote/hapeer`: one household Portal hands its Home Assistant connection to another
 * ("Use Family Area's connection"). Settings sync never copies secrets; this is the one explicit,
 * operator- or person-initiated path that does.
 *
 * - Request: a fresh, replay-guarded `ha_share` [HouseholdMessage] in `X-Immortal-Household`,
 *   pinning the SHA-256 of the body `{v:1, op:"probe"|"share"}` (the same scheme as every other
 *   peer route, so it passes [PeerRequestGate] before the body is read).
 * - Reply: `{payload, sig}`, `sig = HMAC(householdKey, "ha_share_reply|<request nonce>|payload")`,
 *   so a reply is bound to the one request that asked.
 * - `probe` payload: `{v, fn, c:true}` — the source's name and that it is connected. No secret.
 * - `share` payload: `{v, fn, s}`, `s = hex(iv ‖ AES-256-GCM(ciphertext+tag))` of `{u: url, t: token}`.
 *   The AES key is `HMAC(householdKey, "hearthpane-ha-share|v1|key|<requester>|<nonce>")`, so it is
 *   different for every request and every requesting Portal; the AAD is
 *   `hearthpane-ha-share|v1|<requester>|<source>|<nonce>`. HTTP on the LAN is plain text: the seal is
 *   what keeps the token off the wire.
 *
 * Every field is treated as untrusted on both ends; anything malformed decodes to null.
 */
internal object HaShareWire {
  const val PATH = "/remote/hapeer"
  const val HEADER = HouseholdListPeerAuth.HEADER
  const val REQUEST_TYPE = "ha_share"
  const val VERSION = 1
  /** `{"v":1,"op":"share"}` is 20 bytes; nothing bigger is ever needed. */
  const val MAX_BODY = 256
  const val MAX_REPLY = 8 * 1024
  const val MAX_URL = 512
  const val MIN_TOKEN = 16
  const val MAX_TOKEN = 2_048
  private const val RESPONSE_DOMAIN = "ha_share_reply"
  private const val LABEL = "hearthpane-ha-share|v1"
  private const val IV_BYTES = 12
  private const val TAG_BITS = 128
  private const val MAX_SEALED_HEX = 6_000
  private const val PRINTABLE_FIRST = 0x21
  private const val PRINTABLE_LAST = 0x7e

  enum class Op(val wire: String) {
    PROBE("probe"),
    SHARE("share");

    companion object {
      fun fromWire(text: String?): Op? = values().firstOrNull { it.wire == text }
    }
  }

  data class Request(val from: String, val nonce: String, val op: Op)

  /** A Home Assistant connection. [toString] never prints either field. */
  class Connection private constructor(val url: String, val token: String) {
    override fun toString(): String = "Connection(<redacted>)"
    override fun equals(other: Any?): Boolean = other is Connection && same(this, other)
    override fun hashCode(): Int = url.hashCode()

    companion object {
      /** A normalized connection if [url] is an http(s) address with a host and [token] a bearer token; else null. */
      fun of(url: String?, token: String?): Connection? {
        val u = url?.trim()?.trimEnd('/')?.takeIf { it.length in 1..MAX_URL && isHttpUrl(it) } ?: return null
        val t = token?.trim()?.takeIf(::isToken) ?: return null
        return Connection(u, t)
      }
    }
  }

  data class Probe(val name: String, val connected: Boolean)
  data class Shared(val name: String, val connection: Connection)

  fun requestBody(op: Op): String = JSONObject().put("v", VERSION).put("op", op.wire).toString()

  fun requestHeader(key: ByteArray, from: String, nonce: String, ts: Long, body: String): String? =
      HouseholdMessage.encodeOrNull(HouseholdMessage(REQUEST_TYPE, from, ts, nonce, JSONObject().put("s", sha256(body))), key)

  /** The verified caller and op, or null for a missing/forged/stale/replayed header, a swapped body or a bad op. */
  fun verifyRequest(header: String?, body: String, key: ByteArray, nowMs: Long,
                    replay: HouseholdMessage.ReplayGuard, self: String?): Request? {
    if (header == null) return null
    val msg = HouseholdMessage.decode(header, key, nowMs, replay) ?: return null
    if (msg.type != REQUEST_TYPE || msg.from == self) return null
    val pinned = msg.body.opt("s") as? String ?: return null
    if (!MessageDigest.isEqual(pinned.toByteArray(), sha256(body).toByteArray())) return null
    val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
    if (o.optInt("v") != VERSION) return null
    val op = Op.fromWire(o.opt("op") as? String) ?: return null
    return Request(msg.from, msg.nonce, op)
  }

  fun signResponse(key: ByteArray, nonce: String, payload: String): String = hmacHex(key, "$RESPONSE_DOMAIN|$nonce|$payload")

  fun verifyResponse(key: ByteArray, nonce: String, payload: String, sig: String?): Boolean =
      sig != null && MessageDigest.isEqual(sig.toByteArray(), signResponse(key, nonce, payload).toByteArray())

  fun probePayload(name: String): String =
      JSONObject().put("v", VERSION).put("fn", name.take(DinnerWire.MAX_NAME)).put("c", true).toString()

  fun parseProbe(text: String?): Probe? {
    val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    if (o.optInt("v") != VERSION) return null
    val name = DinnerWire.cleanName(o.opt("fn") as? String) ?: return null
    return Probe(name, o.opt("c") == true)
  }

  /** The sealed `share` payload for [requester], or null if sealing failed. */
  fun sharePayload(connection: Connection, name: String, key: ByteArray, requester: String, source: String,
                   nonce: String, random: SecureRandom): String? {
    val plain = JSONObject().put("u", connection.url).put("t", connection.token).toString()
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val sealed = runCatching {
      cipher(Cipher.ENCRYPT_MODE, key, iv, requester, source, nonce).doFinal(plain.toByteArray(Charsets.UTF_8))
    }.getOrNull() ?: return null
    return JSONObject().put("v", VERSION).put("fn", name.take(DinnerWire.MAX_NAME))
        .put("s", HouseholdKey.toHex(iv + sealed)).toString()
  }

  /** The connection if [text] opens for this [requester]/[source]/[nonce] and every field is well-formed; else null. */
  fun openShare(text: String?, key: ByteArray, requester: String, source: String, nonce: String): Shared? {
    val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    if (o.optInt("v") != VERSION) return null
    val name = DinnerWire.cleanName(o.opt("fn") as? String) ?: return null
    val hex = (o.opt("s") as? String)?.takeIf { it.length <= MAX_SEALED_HEX } ?: return null
    val bytes = HouseholdKey.fromHex(hex)?.takeIf { it.size > IV_BYTES + TAG_BITS / 8 } ?: return null
    val plain = try {
      String(cipher(Cipher.DECRYPT_MODE, key, bytes.copyOfRange(0, IV_BYTES), requester, source, nonce)
          .doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
      return null
    } catch (_: IllegalArgumentException) {
      return null
    }
    val inner = runCatching { JSONObject(plain) }.getOrNull() ?: return null
    val connection = Connection.of(inner.opt("u") as? String, inner.opt("t") as? String) ?: return null
    return Shared(name, connection)
  }

  /** Constant-time: the same address and the same token. */
  fun same(a: Connection, b: Connection): Boolean {
    val url = MessageDigest.isEqual(a.url.toByteArray(Charsets.UTF_8), b.url.toByteArray(Charsets.UTF_8))
    val token = MessageDigest.isEqual(a.token.toByteArray(Charsets.UTF_8), b.token.toByteArray(Charsets.UTF_8))
    return url && token
  }

  fun isHttpUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
  }.getOrDefault(false)

  /** A bearer token: printable ASCII without spaces (HA's long-lived tokens are JWTs). */
  fun isToken(value: String): Boolean =
      value.length in MIN_TOKEN..MAX_TOKEN && value.all { it.code in PRINTABLE_FIRST..PRINTABLE_LAST }

  private fun cipher(mode: Int, householdKey: ByteArray, iv: ByteArray, requester: String, source: String, nonce: String): Cipher =
      Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(sealKey(householdKey, requester, nonce), "AES"), GCMParameterSpec(TAG_BITS, iv))
        updateAAD("$LABEL|$requester|$source|$nonce".toByteArray(Charsets.UTF_8))
      }

  /** Per request and per requesting Portal: `HMAC-SHA256(householdKey, label|key|requester|nonce)`. */
  internal fun sealKey(householdKey: ByteArray, requester: String, nonce: String): ByteArray =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(householdKey, "HmacSHA256")) }
          .doFinal("$LABEL|key|$requester|$nonce".toByteArray(Charsets.UTF_8))

  private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun hmacHex(key: ByteArray, text: String): String =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          .doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
