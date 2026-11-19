/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wire formats for household settings sync. All of it travels signed with the household key:
 * - bus `setting_changed` `{key, value, version, deviceId, ts}` — one change, sent as it happens;
 * - bus `settings_rev` `{d: digest, h: LAN IPv4, p: port, j: joining}` — gossiped on change, on
 *   network return and every [SettingsSync.GOSSIP_MS]; a peer whose digest differs exchanges;
 * - `POST /remote/settingspeer` with `X-Immortal-Household`: a fresh, replay-guarded
 *   `settings_sync` [HouseholdMessage] pinning the SHA-256 of the body `{name, phase, records}`;
 *   the reply `{payload, sig}` is HMAC-signed over `settings_state|nonce|payload`, so it is bound
 *   to that one request. Message types differ from the list's and chores', so none replays as another.
 */
internal object SettingsSyncWire {
  const val CHANGED_TYPE = "setting_changed"
  const val SIGNAL_TYPE = "settings_rev"
  const val REQUEST_TYPE = "settings_sync"
  const val HEADER = HouseholdListPeerAuth.HEADER
  private const val RESPONSE_DOMAIN = "settings_state"
  /** The bus envelope adds ~250 chars to the body; stay well inside its 1,400. */
  const val MAX_CHANGED_BODY = 900
  const val MAX_NAME = 64
  const val MIN_PEER_PORT = 1024
  private val DEVICE = Regex("^[A-Za-z0-9_-]{1,64}$")

  data class Signal(val digest: String, val host: String, val port: Int, val joining: Boolean)
  data class Request(val from: String, val nonce: String)
  data class Payload(val name: String, val joining: Boolean, val records: List<SyncRecord>)

  fun recordJson(r: SyncRecord): JSONObject = JSONObject().put("key", r.key).put("value", r.value)
      .put("version", r.version.counter).put("deviceId", r.version.device).put("ts", r.ts)

  /** A structurally valid record (values are checked against the setting's schema later), or null. */
  fun parseRecord(o: JSONObject?): SyncRecord? {
    o ?: return null
    val key = (o.opt("key") as? String)?.takeIf { it.length in 3..64 } ?: return null
    val value = o.opt("value")?.takeIf { it is Boolean || it is Number || it is String } ?: return null
    val counter = (o.opt("version") as? Number)?.toLong() ?: return null
    val device = (o.opt("deviceId") as? String)?.takeIf(DEVICE::matches) ?: return null
    val ts = (o.opt("ts") as? Number)?.toLong() ?: return null
    return SyncRecord(key, value, SyncVersion(counter, device), ts)
  }

  /** The `setting_changed` body, or null if this value is too big for one bus packet. */
  fun changedBody(r: SyncRecord): JSONObject? = recordJson(r).takeIf { it.toString().length <= MAX_CHANGED_BODY }

  /** A bus change is only taken from the Portal that wrote it (a relay goes through the exchange). */
  fun parseChanged(from: String, body: JSONObject): SyncRecord? =
      parseRecord(body)?.takeIf { it.version.device == from }

  fun signalBody(s: Signal): JSONObject =
      HouseholdListPeerAuth.signalBody(HouseholdListPeerAuth.Signal(s.digest, s.host, s.port)).put("j", s.joining)

  fun parseSignal(body: JSONObject): Signal? {
    val base = HouseholdListPeerAuth.parseSignal(body) ?: return null
    // Peer routes live on the Fleet agent's unprivileged port; never POST to SSH, SMB or the like.
    if (base.port < MIN_PEER_PORT) return null
    return Signal(base.digest, base.host, base.port, body.opt("j") == true)
  }

  fun payload(name: String, joining: Boolean, records: Collection<SyncRecord>): String {
    val list = JSONArray()
    records.sortedBy { it.key }.take(SettingsSyncEngine.MAX_RECORDS).forEach { list.put(recordJson(it)) }
    return JSONObject().put("name", name.take(MAX_NAME)).put("phase", if (joining) "joining" else "synced")
        .put("records", list).toString()
  }

  fun parsePayload(text: String?): Payload? {
    val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
    val list = o.optJSONArray("records") ?: return null
    if (list.length() > SettingsSyncEngine.MAX_RECORDS) return null
    val records = (0 until list.length()).mapNotNull { parseRecord(list.optJSONObject(it)) }
    val name = (o.opt("name") as? String)?.take(MAX_NAME)?.filterNot(Char::isISOControl).orEmpty()
    return Payload(name, o.opt("phase") == "joining", records)
  }

  fun requestHeader(key: ByteArray, from: String, nonce: String, ts: Long, body: String): String? =
      HouseholdMessage.encodeOrNull(HouseholdMessage(REQUEST_TYPE, from, ts, nonce, JSONObject().put("s", sha256(body))), key)

  /** The verified caller, or null for a missing/forged/stale/replayed header or a swapped body. */
  fun verifyRequest(header: String?, body: String, key: ByteArray, nowMs: Long,
                    replay: HouseholdMessage.ReplayGuard, self: String?): Request? {
    if (header == null) return null
    val msg = HouseholdMessage.decode(header, key, nowMs, replay) ?: return null
    if (msg.type != REQUEST_TYPE || msg.from == self) return null
    val pinned = msg.body.opt("s") as? String ?: return null
    if (!MessageDigest.isEqual(pinned.toByteArray(), sha256(body).toByteArray())) return null
    return Request(msg.from, msg.nonce)
  }

  fun signResponse(key: ByteArray, nonce: String, body: String): String = hmacHex(key, "$RESPONSE_DOMAIN|$nonce|$body")

  fun verifyResponse(key: ByteArray, nonce: String, body: String, sig: String?): Boolean =
      sig != null && MessageDigest.isEqual(sig.toByteArray(), signResponse(key, nonce, body).toByteArray())

  private fun sha256(text: String): String =
      MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

  private fun hmacHex(key: ByteArray, text: String): String =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
          .doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
