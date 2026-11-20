/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * `room_seen`: the one household message that says who Who's there? recognised in which room — the
 * minimum another Portal needs to draw that person's chip on its map. Before this, recognition never
 * left the Portal that ran it (templates, people and results are per Portal). See
 * docs/features/control-center-people.md for the full privacy design.
 *
 * The bus envelope already signs (HMAC-SHA256, household key), time-boxes (±2 min) and de-duplicates
 * every message, but its body is plain text on the LAN broadcast. A name and a room are personal, so
 * the body here is also **sealed**: AES-256-GCM under `HMAC-SHA256(householdKey, "hearthpane-room-seen|v1")`
 * with a random 96-bit IV and AAD `hearthpane-room-seen|v1|<from>|<n>` (bound to the sending Portal and
 * this message's own nonce). Wire body: `{v:1, n, s}` with `s = hex(iv ‖ ciphertext+tag)`.
 *
 * Sealed fields (one person per message, ≈ 240 hex chars): `i` the person's id on the sending Portal,
 * `nm` first name (≤ 20), `c` colour index, `fn` the sending Portal's name, `rm` its plan room id
 * (may be empty), `a` age of the sighting in ms (≤ [MAX_AGE_MS]) — or `x: 1` with only `i`/`fn`: "forget
 * this person now" (paused, opted out, deleted). No face data, image, score or other person is ever sent,
 * and nothing is sent for anyone the sending Portal's [CcPeoplePolicy.shareable] refuses.
 */
internal object RoomSeenWire {
  const val TYPE = "room_seen"
  const val VERSION = 1
  /** A sighting older than the map shows is never sent (and is refused on arrival). */
  const val MAX_AGE_MS = CcOccupancyFusion.SIGHTING_MS
  private const val LABEL = "hearthpane-room-seen|v1"
  private const val IV_BYTES = 12
  private const val NONCE_BYTES = 8
  private const val TAG_BITS = 128
  private const val MAX_SEALED_HEX = 900
  private const val MAX_ROOM_ID = 64
  private const val MAX_COLOR = 63
  private val ID = Regex("^[a-z0-9]{6,32}$")
  private val NONCE = Regex("^[0-9a-f]{16}$")
  private val ROOM_ID = Regex("^[A-Za-z0-9_.-]{1,64}$")
  private val random = SecureRandom()

  /** One recognised person, or a revocation ([forget]). */
  data class Seen(
      val personId: String,
      val name: String,
      val colorIndex: Int,
      val portalName: String,
      val roomId: String?,
      val ageMs: Long,
      val forget: Boolean = false,
  )

  fun encode(seen: Seen, householdKey: ByteArray, from: String): JSONObject? {
    if (!ID.matches(seen.personId)) return null
    val plain = JSONObject().put("i", seen.personId).put("fn", seen.portalName.take(DinnerWire.MAX_NAME))
    if (seen.forget) plain.put("x", 1)
    else {
      val name = WhosThereLimits.cleanName(seen.name).takeIf { it.isNotEmpty() } ?: return null
      plain.put("nm", name).put("c", seen.colorIndex.coerceIn(0, MAX_COLOR)).put("a", seen.ageMs.coerceIn(0L, MAX_AGE_MS))
          .put("rm", seen.roomId?.takeIf { ROOM_ID.matches(it) } ?: "")
    }
    val nonce = HouseholdKey.toHex(ByteArray(NONCE_BYTES).also(random::nextBytes))
    val iv = ByteArray(IV_BYTES).also(random::nextBytes)
    val sealed = runCatching { cipher(Cipher.ENCRYPT_MODE, householdKey, iv, from, nonce).doFinal(plain.toString().toByteArray(Charsets.UTF_8)) }
        .getOrNull() ?: return null
    return JSONObject().put("v", VERSION).put("n", nonce).put("s", HouseholdKey.toHex(iv + sealed))
  }

  /** The sighting if [body] opens under [householdKey] for sender [from] and every field is well-formed; else null. */
  fun decode(body: JSONObject, householdKey: ByteArray, from: String): Seen? {
    if (body.optInt("v") != VERSION) return null
    val nonce = (body.opt("n") as? String)?.takeIf { NONCE.matches(it) } ?: return null
    val hex = (body.opt("s") as? String)?.takeIf { it.length <= MAX_SEALED_HEX } ?: return null
    val bytes = HouseholdKey.fromHex(hex)?.takeIf { it.size > IV_BYTES + TAG_BITS / 8 } ?: return null
    val text = try {
      String(cipher(Cipher.DECRYPT_MODE, householdKey, bytes.copyOfRange(0, IV_BYTES), from, nonce)
          .doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
      return null
    } catch (_: IllegalArgumentException) {
      return null
    }
    val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val id = (o.opt("i") as? String)?.takeIf { ID.matches(it) } ?: return null
    val portal = DinnerWire.cleanName(o.opt("fn") as? String) ?: return null
    if ((o.opt("x") as? Number)?.toInt() == 1) return Seen(id, "", 0, portal, null, 0L, forget = true)
    val name = (o.opt("nm") as? String)?.let(WhosThereLimits::cleanName)?.takeIf { it.isNotEmpty() } ?: return null
    val color = (o.opt("c") as? Number)?.toInt()?.takeIf { it in 0..MAX_COLOR } ?: return null
    val age = (o.opt("a") as? Number)?.toLong()?.takeIf { it in 0 until MAX_AGE_MS } ?: return null
    val room = (o.opt("rm") as? String)?.takeIf { it.length <= MAX_ROOM_ID && ROOM_ID.matches(it) }
    return Seen(id, name, color, portal, room, age)
  }

  fun key(householdKey: ByteArray): ByteArray =
      Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(householdKey, "HmacSHA256")) }.doFinal(LABEL.toByteArray(Charsets.UTF_8))

  private fun cipher(mode: Int, householdKey: ByteArray, iv: ByteArray, from: String, nonce: String): Cipher =
      Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(key(householdKey), "AES"), GCMParameterSpec(TAG_BITS, iv))
        updateAAD("$LABEL|$from|$nonce".toByteArray(Charsets.UTF_8))
      }
}
