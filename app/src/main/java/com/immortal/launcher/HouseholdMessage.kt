/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A signed Portal↔Portal household message (shared list, dinner call, routine progress).
 *
 * Portals never hold each other's pairing tokens (see [RemoteRoster]); instead the phone, which
 * is paired with every Portal, provisions one shared [HouseholdKey]. Messages are HMAC-SHA256
 * signed with it, must be fresh (±[MAX_SKEW_MS]) and unseen (nonce [ReplayGuard]); anything else is
 * dropped. Wire form: `MAGIC|json|hexHmac`; the HMAC covers `MAGIC|json` (split on the last `|`).
 */
internal data class HouseholdMessage(
    val type: String,
    val from: String,
    val ts: Long,
    val nonce: String,
    val body: JSONObject,
) {
  companion object {
    const val MAGIC = "IMMORTAL_HH/1"
    const val MAX_WIRE_CHARS = 1_400 // fits one UDP datagram on any LAN
    const val MAX_SKEW_MS = 120_000L
    private val TYPE = Regex("^[a-z][a-z0-9_]{1,31}$")
    private val TOKEN = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun encode(message: HouseholdMessage, key: ByteArray): String =
        requireNotNull(encodeOrNull(message, key)) { "invalid household message" }

    fun encodeOrNull(message: HouseholdMessage, key: ByteArray): String? {
      if (!HouseholdKey.isValidKey(key) || !TYPE.matches(message.type) || !TOKEN.matches(message.from) ||
          !TOKEN.matches(message.nonce)) return null
      val json = JSONObject().put("t", message.type).put("f", message.from).put("ts", message.ts)
          .put("n", message.nonce).put("b", message.body).toString()
      val signed = "$MAGIC|$json"
      val wire = "$signed|" + hex(hmac(key, signed))
      return wire.takeIf { it.length <= MAX_WIRE_CHARS }
    }

    /** The message if [wire] is well-formed, signed with [key], fresh and not a replay; else null. */
    fun decode(wire: String, key: ByteArray, nowMs: Long, replay: ReplayGuard): HouseholdMessage? {
      if (wire.length > MAX_WIRE_CHARS || !wire.startsWith("$MAGIC|") || !HouseholdKey.isValidKey(key)) return null
      val cut = wire.lastIndexOf('|')
      if (cut <= MAGIC.length) return null
      val signed = wire.substring(0, cut)
      val given = wire.substring(cut + 1)
      val expected = hex(hmac(key, signed))
      if (!MessageDigest.isEqual(given.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) return null
      val json = runCatching { JSONObject(signed.substring(MAGIC.length + 1)) }.getOrNull() ?: return null
      val message = runCatching {
        HouseholdMessage(json.getString("t"), json.getString("f"), json.getLong("ts"), json.getString("n"),
            json.getJSONObject("b"))
      }.getOrNull() ?: return null
      if (!TYPE.matches(message.type) || !TOKEN.matches(message.from) || !TOKEN.matches(message.nonce)) return null
      if (kotlin.math.abs(nowMs - message.ts) > MAX_SKEW_MS) return null
      if (!replay.firstSighting(message.from, message.nonce)) return null
      return message
    }

    private fun hmac(key: ByteArray, text: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
            .doFinal(text.toByteArray(Charsets.UTF_8))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
  }

  /** Remembers recent (sender, nonce) pairs; a repeat inside the window is a replay. Thread-safe. */
  class ReplayGuard(private val capacity: Int = 512) {
    private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, false) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }
    @Synchronized fun firstSighting(from: String, nonce: String): Boolean = seen.put("$from:$nonce", Unit) == null
  }
}
