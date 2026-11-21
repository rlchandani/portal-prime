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
 * The three pairing messages that cross the relay when two homes link ([HomeLinkInviter]).
 * Everything here is public to the relay except the joiner's key and name, which stay hidden
 * behind [Commit.commitment] until the inviter has revealed its own.
 */
internal sealed interface HomeLinkMessage {
  val mailbox: String
  val mac: String

  /** Joiner → inviter: a hash of (key, nonce, name), nothing readable. */
  data class Commit(override val mailbox: String, val commitment: String, override val mac: String = "") : HomeLinkMessage

  /** Inviter → joiner: its key and name, signed. */
  data class Reveal(override val mailbox: String, val key: String, val name: String, val sig: String,
                    override val mac: String = "") : HomeLinkMessage

  /** Joiner → inviter: what it committed to, signed over the inviter's key. */
  data class Open(override val mailbox: String, val key: String, val nonce: String, val name: String, val sig: String,
                  override val mac: String = "") : HomeLinkMessage
}

/** Strict JSON codec: versioned, bounded, every field required (anything off parses to null). */
internal object HomeLinkWire {
  const val VERSION = 1
  const val MAX_NAME = 40
  const val MAX_FIELD = 200
  const val MAX_BYTES = 4_096
  private val MAILBOX = Regex("^[0-9a-f]{32}$")
  private const val COMMIT = "commit"
  private const val REVEAL = "reveal"
  private const val OPEN = "open"

  /** A home name fit to show on the other home's screen: 1–40 characters, no control characters. */
  fun validName(name: String): Boolean =
      name.isNotBlank() && name.length <= MAX_NAME && name.none { Character.isISOControl(it) }

  fun encode(m: HomeLinkMessage): String {
    val o = JSONObject().put("v", VERSION).put("b", m.mailbox).put("m", m.mac)
    when (m) {
      is HomeLinkMessage.Commit -> o.put("t", COMMIT).put("c", m.commitment)
      is HomeLinkMessage.Reveal -> o.put("t", REVEAL).put("k", m.key).put("n", m.name).put("s", m.sig)
      is HomeLinkMessage.Open -> o.put("t", OPEN).put("k", m.key).put("r", m.nonce).put("n", m.name).put("s", m.sig)
    }
    return o.toString()
  }

  fun parse(text: String): HomeLinkMessage? {
    if (text.length > MAX_BYTES) return null
    val o = try { JSONObject(text) } catch (e: JSONException) { return null }
    if (o.optInt("v", -1) != VERSION) return null
    val mailbox = o.optString("b").takeIf { MAILBOX.matches(it) } ?: return null
    val mac = field(o, "m") ?: return null
    return when (o.optString("t")) {
      COMMIT -> HomeLinkMessage.Commit(mailbox, field(o, "c") ?: return null, mac)
      REVEAL -> HomeLinkMessage.Reveal(mailbox, field(o, "k") ?: return null, name(o) ?: return null,
          field(o, "s") ?: return null, mac)
      OPEN -> HomeLinkMessage.Open(mailbox, field(o, "k") ?: return null, field(o, "r") ?: return null,
          name(o) ?: return null, field(o, "s") ?: return null, mac)
      else -> null
    }
  }

  /** The exact text the code MAC covers: every field but the MAC, length-prefixed, type first. */
  fun signable(m: HomeLinkMessage): String {
    val parts = when (m) {
      is HomeLinkMessage.Commit -> listOf(COMMIT, m.mailbox, m.commitment)
      is HomeLinkMessage.Reveal -> listOf(REVEAL, m.mailbox, m.key, m.name, m.sig)
      is HomeLinkMessage.Open -> listOf(OPEN, m.mailbox, m.key, m.nonce, m.name, m.sig)
    }
    return (listOf("v$VERSION") + parts).joinToString("") { "${it.length}:$it" }
  }

  private fun field(o: JSONObject, key: String): String? =
      (o.opt(key) as? String)?.takeIf { it.isNotEmpty() && it.length <= MAX_FIELD }

  private fun name(o: JSONObject): String? = (o.opt("n") as? String)?.takeIf { validName(it) }
}
