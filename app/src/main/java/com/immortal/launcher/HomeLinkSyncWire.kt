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
 * Keeping every Portal in one home on the same [HomeLinkVault] (slice S3): the household identity
 * key and the linked homes, including removals. The reminders scheme ([ReminderPeerAuth]) with
 * HA share's per-request sealing ([HaShareWire]), because this one carries a private key:
 *
 * - bus signal `home_link_rev` `{d, h, p}`: a keyed digest of the vault and where to reach us;
 * - `POST /remote/homelinkpeer` with a fresh, replay-guarded `home_link_sync` [HouseholdMessage]
 *   header pinning the SHA-256 of the body `{v, s}`;
 * - `s = hex(iv ‖ AES-256-GCM)` of the vault, under `HMAC(householdKey, label|key|requester|nonce|direction)`
 *   with AAD `label|requester|nonce|direction`: a different key for every request, requester and
 *   direction, so a sealed vault can't be replayed or reflected;
 * - the reply `{payload, sig}` is signed over `home_link_state|nonce|payload`.
 *
 * Phones hold the household key too, so the route refuses phone ids and unannounced addresses
 * ([HomeLinkSyncRoute]). Everything is untrusted; anything off decodes to null.
 */
internal object HomeLinkSyncWire {
  const val PATH = "/remote/homelinkpeer"
  const val HEADER = HouseholdListPeerAuth.HEADER
  const val REQUEST_TYPE = "home_link_sync"
  const val SIGNAL_TYPE = "home_link_rev"
  const val REQUEST = "req"
  const val RESPONSE = "res"
  const val MAX_BODY = 64 * 1024
  private const val VERSION = 1
  private const val RESPONSE_DOMAIN = "home_link_state"
  private const val LABEL = "hearthpane-homelink-sync|v1"
  private const val IV_BYTES = 12
  private const val TAG_BITS = 128

  data class Request(val from: String, val nonce: String)

  fun requestHeader(key: ByteArray, from: String, nonce: String, ts: Long, body: String): String? =
      HouseholdMessage.encodeOrNull(HouseholdMessage(REQUEST_TYPE, from, ts, nonce, JSONObject().put("s", sha256(body))), key)

  /** The verified caller, or null for a missing/forged/stale/replayed header or a swapped body. */
  fun verifyRequest(header: String?, body: String, key: ByteArray, nowMs: Long, replay: HouseholdMessage.ReplayGuard,
                    self: String?): Request? {
    if (header == null) return null
    val msg = HouseholdMessage.decode(header, key, nowMs, replay) ?: return null
    if (msg.type != REQUEST_TYPE || msg.from == self) return null
    val pinned = msg.body.opt("s") as? String ?: return null
    if (!MessageDigest.isEqual(pinned.toByteArray(), sha256(body).toByteArray())) return null
    return Request(msg.from, msg.nonce)
  }

  fun signResponse(key: ByteArray, nonce: String, payload: String): String =
      HouseholdKey.toHex(HomeLinkHash.hmac(key, HomeLinkHash.utf8("$RESPONSE_DOMAIN|$nonce|$payload")))

  fun verifyResponse(key: ByteArray, nonce: String, payload: String, sig: String?): Boolean =
      sig != null && MessageDigest.isEqual(sig.toByteArray(), signResponse(key, nonce, payload).toByteArray())

  /** `{v, s}`: [vault] sealed for [requester]'s exchange [nonce], one [direction]. Null if sealing failed. */
  fun seal(householdKey: ByteArray, requester: String, nonce: String, direction: String, vault: HomeLinkVault,
           random: SecureRandom): String? {
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val sealed = try {
      cipher(Cipher.ENCRYPT_MODE, householdKey, iv, requester, nonce, direction)
          .doFinal(HomeLinkVaultCodec.encode(vault).toByteArray(Charsets.UTF_8))
    } catch (e: GeneralSecurityException) { return null }
    return JSONObject().put("v", VERSION).put("s", HouseholdKey.toHex(iv + sealed)).toString()
  }

  /** The vault inside a `{v, s}` body if it opens for exactly this requester/nonce/direction and validates. */
  fun open(householdKey: ByteArray, requester: String, nonce: String, direction: String, body: String?): HomeLinkVault? {
    val o = body?.takeIf { it.length <= MAX_BODY }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    if (o.opt("v") != VERSION) return null
    val bytes = (o.opt("s") as? String)?.let(HouseholdKey::fromHex)?.takeIf { it.size > IV_BYTES + TAG_BITS / 8 } ?: return null
    val plain = try {
      cipher(Cipher.DECRYPT_MODE, householdKey, bytes.copyOfRange(0, IV_BYTES), requester, nonce, direction)
          .doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
    } catch (e: GeneralSecurityException) { return null } catch (e: IllegalArgumentException) { return null }
    return HomeLinkVaultCodec.parse(String(plain, Charsets.UTF_8))
  }

  /** What the bus signal carries: keyed, so it says nothing about the vault to anyone without the key. */
  fun digest(vault: HomeLinkVault, householdKey: ByteArray): String =
      HouseholdKey.toHex(HomeLinkHash.hmac(HomeLinkHash.hmac(householdKey, HomeLinkHash.utf8("$LABEL|digest")),
          HomeLinkHash.utf8(HomeLinkVaultCodec.encode(vault)))).take(DIGEST_HEX)

  private const val DIGEST_HEX = 32

  private fun cipher(mode: Int, householdKey: ByteArray, iv: ByteArray, requester: String, nonce: String, direction: String): Cipher =
      Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(sealKey(householdKey, requester, nonce, direction), "AES"), GCMParameterSpec(TAG_BITS, iv))
        updateAAD(HomeLinkHash.utf8("$LABEL|$requester|$nonce|$direction"))
      }

  internal fun sealKey(householdKey: ByteArray, requester: String, nonce: String, direction: String): ByteArray =
      HomeLinkHash.hmac(householdKey, HomeLinkHash.utf8("$LABEL|key|$requester|$nonce|$direction"))

  private fun sha256(text: String): String = HomeLinkHash.hex(HomeLinkHash.sha256(HomeLinkHash.utf8(text)))
}
