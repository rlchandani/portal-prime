/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONException
import org.json.JSONObject

/**
 * The four pairing messages that cross the relay when two homes link ([HomeLinkInviter]), in a
 * fixed order: [Hello] (seq 0) → [Commit] (1) → [Reveal] (2) → [Open] (3). Each carries its type
 * and [seq]; the session MAC covers both, so a relay can't relabel, reorder or reflect one, and a
 * side rejects anything out of sequence.
 *
 * What the relay sees: the mailbox id (from the public nameplate), two PAKE shares (random curve
 * points), MACs under the PAKE session key, the inviter's key and name, and — only after the
 * inviter has revealed — the joiner's. Nothing it can test a guess at the code's secret against.
 */
internal sealed interface HomeLinkMessage {
  val mailbox: String
  val seq: Int
  val mac: String

  /** Inviter → mailbox, when the invite is made: its PAKE share. Unauthenticated by design; the PAKE key covers it. */
  data class Hello(override val mailbox: String, val share: String, override val seq: Int = SEQ) : HomeLinkMessage {
    override val mac: String get() = ""
    companion object { const val SEQ = 0 }
  }

  /** Joiner → inviter: its PAKE share and a hash of (key, nonce, name), nothing readable. */
  data class Commit(override val mailbox: String, val share: String, val commitment: String, override val mac: String = "",
                    override val seq: Int = SEQ) : HomeLinkMessage {
    companion object { const val SEQ = 1 }
  }

  /** Inviter → joiner: its key and name (pinning: and its device id), signed. */
  data class Reveal(override val mailbox: String, val key: String, val name: String, val sig: String,
                    override val mac: String = "", override val seq: Int = SEQ, val device: String = "") : HomeLinkMessage {
    companion object { const val SEQ = 2 }
  }

  /** Joiner → inviter: what it committed to (pinning: including its device id), signed over the inviter's key. */
  data class Open(override val mailbox: String, val key: String, val nonce: String, val name: String, val sig: String,
                  override val mac: String = "", override val seq: Int = SEQ, val device: String = "") : HomeLinkMessage {
    companion object { const val SEQ = 3 }
  }
}

/** Strict JSON codec: versioned, bounded, every field required (anything off parses to null). */
internal object HomeLinkWire {
  const val VERSION = 2
  const val MAX_NAME = 40
  const val MAX_FIELD = 200
  const val MAX_BYTES = 4_096
  private const val MAX_SEQ = 15
  private val MAILBOX = Regex("^[0-9a-f]{32}$")
  private const val HELLO = "hello"
  private const val COMMIT = "commit"
  private const val REVEAL = "reveal"
  private const val OPEN = "open"

  /** A household device id as Portals announce it (pinning binds the pin to it). Never a phone's. */
  fun validDevice(id: String): Boolean = DinnerWire.isDeviceId(id) && !CallPhoneId.isPhone(id)

  /** A home name fit to show on the other home's screen: 1–40 characters, no control characters. */
  fun validName(name: String): Boolean =
      name.isNotBlank() && name.length <= MAX_NAME && name.none { Character.isISOControl(it) }

  fun encode(m: HomeLinkMessage): String {
    val o = JSONObject().put("v", VERSION).put("q", m.seq).put("b", m.mailbox)
    when (m) {
      is HomeLinkMessage.Hello -> o.put("t", HELLO).put("y", m.share)
      is HomeLinkMessage.Commit -> o.put("t", COMMIT).put("y", m.share).put("c", m.commitment).put("m", m.mac)
      is HomeLinkMessage.Reveal -> o.put("t", REVEAL).put("k", m.key).put("n", m.name).put("s", m.sig).put("m", m.mac)
      is HomeLinkMessage.Open -> o.put("t", OPEN).put("k", m.key).put("r", m.nonce).put("n", m.name).put("s", m.sig).put("m", m.mac)
    }
    deviceOf(m).takeIf { it.isNotEmpty() }?.let { o.put("d", it) }
    return o.toString()
  }

  fun parse(text: String): HomeLinkMessage? {
    if (text.length > MAX_BYTES) return null
    val o = try { JSONObject(text) } catch (e: JSONException) { return null }
    if (o.opt("v") != VERSION) return null
    val seq = (o.opt("q") as? Int)?.takeIf { it in 0..MAX_SEQ } ?: return null
    val mailbox = o.optString("b").takeIf { MAILBOX.matches(it) } ?: return null
    return when (o.optString("t")) {
      HELLO -> HomeLinkMessage.Hello(mailbox, field(o, "y") ?: return null, seq)
      COMMIT -> HomeLinkMessage.Commit(mailbox, field(o, "y") ?: return null, field(o, "c") ?: return null,
          field(o, "m") ?: return null, seq)
      REVEAL -> HomeLinkMessage.Reveal(mailbox, field(o, "k") ?: return null, name(o) ?: return null,
          field(o, "s") ?: return null, field(o, "m") ?: return null, seq, device(o) ?: return null)
      OPEN -> HomeLinkMessage.Open(mailbox, field(o, "k") ?: return null, field(o, "r") ?: return null,
          name(o) ?: return null, field(o, "s") ?: return null, field(o, "m") ?: return null, seq, device(o) ?: return null)
      else -> null
    }
  }

  /** The exact text the session MAC covers: version, type, seq, then every field but the MAC, length-prefixed. */
  fun signable(m: HomeLinkMessage): String {
    val parts = when (m) {
      is HomeLinkMessage.Hello -> listOf(HELLO, m.mailbox, m.share)
      is HomeLinkMessage.Commit -> listOf(COMMIT, m.mailbox, m.share, m.commitment)
      is HomeLinkMessage.Reveal -> listOf(REVEAL, m.mailbox, m.key, m.name, m.sig)
      is HomeLinkMessage.Open -> listOf(OPEN, m.mailbox, m.key, m.nonce, m.name, m.sig)
    }
    // A device id (pinning only) is MAC'd too; absent, the text is exactly the home-link v2 text.
    val device = deviceOf(m).takeIf { it.isNotEmpty() }?.let { listOf("d", it) }.orEmpty()
    return (listOf("v$VERSION", "q${m.seq}") + parts + device).joinToString("") { "${it.length}:$it" }
  }

  private fun deviceOf(m: HomeLinkMessage): String = when (m) {
    is HomeLinkMessage.Reveal -> m.device
    is HomeLinkMessage.Open -> m.device
    else -> ""
  }

  /** Absent is "" (linking homes); present must be a Portal device id. Null for anything else. */
  private fun device(o: JSONObject): String? {
    if (!o.has("d")) return ""
    return (o.opt("d") as? String)?.takeIf(::validDevice)
  }

  private fun field(o: JSONObject, key: String): String? =
      (o.opt(key) as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD }

  private fun name(o: JSONObject): String? = (o.opt("n") as? String)?.takeIf { validName(it) }
}
