/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Keeping every Portal in one home on the same [HomeLinkVault] (slice S3, wire v2): the household
 * identity key and the linked homes, including removals. Because the vault carries a private key
 * and paired phones hold the household key, v2 releases it only to a **pinned Portal device key**
 * ([PortalRoster], [PortalDeviceKey]), never to anything the household key alone can make:
 *
 * - bus signal `home_link_rev` `{d, h, p}`: a household-keyed digest of the vault and where to reach us;
 * - `POST /remote/homelinkpeer`, a **pull**: `{v, kid, eph, n, ts, d, back, sig}` — a fresh in-memory
 *   ECDH key `eph` signed by the requester's device key `kid` (plus the household `home_link_sync`
 *   header as an outer, replay-guarded filter that pins the body's SHA-256);
 * - the answer `{v, kid, eph, s, sig}`: the responder's fresh ECDH key and
 *   `s = hex(iv ‖ AES-256-GCM(vault))` under a key from ECDH(eph, eph) salted with both keys and
 *   the nonce, all signed by the responder's pinned device key over the request it answers.
 *
 * Signed Diffie–Hellman (station-to-station style) because Keystore ECDH needs API 31: device
 * keys only sign. A phone with the household key can't sign as a pinned Portal, so it can neither
 * ask (refused) nor answer (the requester drops it); a recorded exchange stays closed even if a
 * device key leaks later (forward secrecy). Everything parsed is untrusted; anything off is null.
 */
internal object HomeLinkSyncWire {
  const val PATH = "/remote/homelinkpeer"
  const val HEADER = HouseholdListPeerAuth.HEADER
  const val REQUEST_TYPE = "home_link_sync"
  const val SIGNAL_TYPE = "home_link_rev"
  const val MAX_BODY = 64 * 1024
  /** How far a signed pull's clock may be from ours. The household header has its own window too. */
  const val FRESH_MS = 2 * 60_000L
  private const val VERSION = 2
  private const val LABEL = "hearthpane-homelink-sync|v2"
  private const val PULL = "hearthpane-homelink-pull-v2"
  private const val GIVE = "hearthpane-homelink-give-v2"
  private const val DIGEST_LABEL = "hearthpane-homelink-sync|v1"
  private const val NONCE_HEX = 24
  private const val DIGEST_HEX = 32
  private const val IV_BYTES = 12
  private const val TAG_BITS = 128

  /** A parsed (not yet verified) pull. [back] marks the responder's pull-back, which is never pulled back again. */
  data class Pull(val kid: String, val eph: String, val nonce: String, val ts: Long, val digest: String, val back: Boolean,
                  val sig: String)

  /** The requester's side of one exchange: the body to send and the in-memory key that opens the answer. */
  class Asking internal constructor(internal val ephemeral: HomeLinkEphemeral, val pull: Pull, val body: String) {
    override fun toString(): String = "Asking(${pull.kid}, n=${pull.nonce})"
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

  /** A pull signed by [device]: [digest] is our vault's, so the responder knows to pull back. */
  fun ask(device: HomeLinkSigner, nonce: String, ts: Long, digest: String, back: Boolean, random: SecureRandom): Asking {
    val eph = HomeLinkEphemeral.generate(random)
    val kid = HomeLinkIdentity.fingerprint(device.publicKey)
    val sig = device.sign(pullText(kid, eph.publicKey, nonce, ts, digest, back))
    val pull = Pull(kid, eph.publicKey, nonce, ts, digest, back, sig)
    val body = JSONObject().put("v", VERSION).put("kid", kid).put("eph", eph.publicKey).put("n", nonce).put("ts", ts)
        .put("d", digest).put("back", back).put("sig", sig).toString()
    return Asking(eph, pull, body)
  }

  fun parsePull(body: String?): Pull? {
    val o = obj(body) ?: return null
    val kid = (o.opt("kid") as? String)?.takeIf(::isKid) ?: return null
    val eph = (o.opt("eph") as? String)?.takeIf { HomeLinkIdentity.decodePublic(it) != null } ?: return null
    val nonce = (o.opt("n") as? String)?.takeIf { isHex(it, NONCE_HEX) } ?: return null
    val ts = (o.opt("ts") as? Number)?.toLong() ?: return null
    val digest = (o.opt("d") as? String)?.takeIf { isHex(it, DIGEST_HEX) } ?: return null
    val back = o.opt("back") as? Boolean ?: return null
    val sig = o.opt("sig") as? String ?: return null
    return Pull(kid, eph, nonce, ts, digest, back, sig)
  }

  /** The pinned Portal behind [pull], or null: not pinned, ourselves, stale, or not signed by its pinned key. */
  fun admit(pull: Pull, roster: PortalRoster, selfKid: String, nowMs: Long): PortalPin? {
    if (pull.kid == selfKid || kotlin.math.abs(nowMs - pull.ts) > FRESH_MS) return null
    val pin = roster.pins[pull.kid] ?: return null
    val text = pullText(pull.kid, pull.eph, pull.nonce, pull.ts, pull.digest, pull.back)
    return pin.takeIf { HomeLinkIdentity.verify(it.publicKey, text, pull.sig) }
  }

  /** Our answer to an admitted [pull]: [vault] sealed to its ephemeral key, signed by [device]. Null if sealing failed. */
  fun give(device: HomeLinkSigner, pull: Pull, vault: HomeLinkVault, random: SecureRandom): String? {
    val eph = HomeLinkEphemeral.generate(random)
    val kid = HomeLinkIdentity.fingerprint(device.publicKey)
    val shared = eph.agree(pull.eph) ?: return null
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val sealed = try {
      cipher(Cipher.ENCRYPT_MODE, sealKey(shared, pull.eph, eph.publicKey, pull.nonce), iv, pull.kid, pull.nonce, kid)
          .doFinal(HomeLinkVaultCodec.encode(vault).toByteArray(Charsets.UTF_8))
    } catch (e: GeneralSecurityException) { return null } finally { shared.fill(0) }
    val s = HouseholdKey.toHex(iv + sealed)
    val sig = device.sign(giveText(pull.kid, pull.eph, pull.nonce, kid, eph.publicKey, s))
    return JSONObject().put("v", VERSION).put("kid", kid).put("eph", eph.publicKey).put("s", s).put("sig", sig).toString()
  }

  /** The vault in [answer] if a pinned Portal other than us ([selfKid]) signed it for exactly [asking]. */
  fun receive(asking: Asking, answer: String?, roster: PortalRoster, selfKid: String): HomeLinkVault? {
    val o = obj(answer) ?: return null
    val kid = (o.opt("kid") as? String)?.takeIf { it != selfKid } ?: return null
    val key = roster.keyFor(kid) ?: return null
    val eph = o.opt("eph") as? String ?: return null
    val s = o.opt("s") as? String ?: return null
    val sig = o.opt("sig") as? String ?: return null
    val pull = asking.pull
    if (!HomeLinkIdentity.verify(key, giveText(pull.kid, pull.eph, pull.nonce, kid, eph, s), sig)) return null
    val bytes = HouseholdKey.fromHex(s)?.takeIf { it.size > IV_BYTES + TAG_BITS / 8 } ?: return null
    val shared = asking.ephemeral.agree(eph) ?: return null
    val plain = try {
      cipher(Cipher.DECRYPT_MODE, sealKey(shared, pull.eph, eph, pull.nonce), bytes.copyOfRange(0, IV_BYTES), pull.kid, pull.nonce, kid)
          .doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    } catch (e: GeneralSecurityException) { return null } finally { shared.fill(0) }
    return HomeLinkVaultCodec.parse(String(plain, Charsets.UTF_8))
  }

  /** What the bus signal carries: household-keyed, so it says nothing about the vault to anyone without the key. */
  fun digest(vault: HomeLinkVault, householdKey: ByteArray): String =
      HouseholdKey.toHex(HomeLinkHash.hmac(HomeLinkHash.hmac(householdKey, HomeLinkHash.utf8("$DIGEST_LABEL|digest")),
          HomeLinkHash.utf8(HomeLinkVaultCodec.encode(vault)))).take(DIGEST_HEX)

  private fun pullText(kid: String, eph: String, nonce: String, ts: Long, digest: String, back: Boolean): ByteArray =
      HomeLinkHash.join(*listOf(PULL, kid, eph, nonce, ts.toString(), digest, back.toString()).map(HomeLinkHash::utf8).toTypedArray())

  private fun giveText(reqKid: String, reqEph: String, nonce: String, kid: String, eph: String, sealed: String): ByteArray =
      HomeLinkHash.join(*listOf(GIVE, reqKid, reqEph, nonce, kid, eph, sealed).map(HomeLinkHash::utf8).toTypedArray())

  /** HKDF-style: extract with a salt over both ephemeral keys and the nonce, then expand with the label. */
  internal fun sealKey(shared: ByteArray, reqEph: String, resEph: String, nonce: String): ByteArray {
    val salt = HomeLinkHash.sha256(HomeLinkHash.utf8(LABEL), HomeLinkHash.utf8(reqEph), HomeLinkHash.utf8(resEph), HomeLinkHash.utf8(nonce))
    return HomeLinkHash.hmac(HomeLinkHash.hmac(salt, shared), HomeLinkHash.utf8("$LABEL|seal"))
  }

  private fun cipher(mode: Int, key: ByteArray, iv: ByteArray, reqKid: String, nonce: String, resKid: String): Cipher =
      Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        updateAAD(HomeLinkHash.join(*listOf(LABEL, reqKid, nonce, resKid).map(HomeLinkHash::utf8).toTypedArray()))
      }

  private fun obj(text: String?): JSONObject? {
    val o = text?.takeIf { it.length <= MAX_BODY }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    return o.takeIf { it.opt("v") == VERSION }
  }

  private fun isKid(text: String): Boolean = isHex(text, 16)

  private fun isHex(text: String, length: Int): Boolean = text.length == length && text.all { it in '0'..'9' || it in 'a'..'f' }

  private fun sha256(text: String): String = HomeLinkHash.hex(HomeLinkHash.sha256(HomeLinkHash.utf8(text)))
}
