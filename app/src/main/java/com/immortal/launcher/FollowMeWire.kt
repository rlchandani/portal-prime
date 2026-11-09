/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

/**
 * [HouseholdBus] bodies for follow-me. Both are broadcasts (no `to`), small enough for one
 * datagram, and carry durations rather than timestamps so Portal clocks never need to agree:
 * - `presence` `{p, d, fn}`: this room's presence (`p`/`a`/`u`), for `d` ms, and its name.
 * - `music_live` `{on, t, a, th, pos, p, d, fn}`: what plays here (`on`=1) or that it stopped
 *   (`on`=0), plus the same presence fields (`d` with `p`=`a` is how long the room has been empty).
 * The bus already checks signature, freshness and replay; decoders still treat every field as untrusted.
 */
internal object FollowMeWire {
  const val PRESENCE = "presence"
  const val LIVE = "music_live"

  const val MAX_TITLE = 80
  const val MAX_ARTIST = 60
  const val MAX_THUMB = 300
  /** Durations above a week are clamped (nothing here cares beyond minutes). */
  const val MAX_FOR_MS = 7 * 24 * 3_600_000L
  const val MAX_POSITION_MS = 24 * 3_600_000L
  /** Body budget, well inside [HouseholdMessage.MAX_WIRE_CHARS] after the signed envelope. */
  const val MAX_BODY_CHARS = 700
  private const val HTTPS = "https://"

  data class PresenceNote(val presence: Presence, val forMs: Long, val name: String)
  data class LiveNote(val presence: Presence, val forMs: Long, val name: String, val track: FollowMeTrack?)

  fun presenceWire(p: Presence): String = when (p) {
    Presence.PRESENT -> "p"
    Presence.ABSENT -> "a"
    Presence.UNKNOWN -> "u"
  }

  fun presenceFromWire(text: String?): Presence? = Presence.entries.firstOrNull { presenceWire(it) == text }

  fun encode(n: PresenceNote): JSONObject =
      JSONObject().put("p", presenceWire(n.presence)).put("d", n.forMs).put("fn", clip(n.name, DinnerWire.MAX_NAME))

  fun encode(n: LiveNote): JSONObject {
    val body = JSONObject().put("p", presenceWire(n.presence)).put("d", n.forMs).put("fn", clip(n.name, DinnerWire.MAX_NAME))
    val t = n.track ?: return body.put("on", 0)
    return body.put("on", 1).put("t", clip(t.title, MAX_TITLE)).put("a", clip(t.artist, MAX_ARTIST))
        .put("th", t.thumb.takeIf { it.length <= MAX_THUMB } ?: "").put("pos", t.positionMs)
  }

  fun decodePresence(b: JSONObject): PresenceNote? {
    val presence = presenceFromWire(b.opt("p") as? String) ?: return null
    val name = DinnerWire.cleanName(b.opt("fn") as? String) ?: return null
    return PresenceNote(presence, duration(b.opt("d"), MAX_FOR_MS), name)
  }

  fun decodeLive(b: JSONObject): LiveNote? {
    val head = decodePresence(b) ?: return null
    val on = (b.opt("on") as? Number)?.toInt() ?: return null
    if (on == 0) return LiveNote(head.presence, head.forMs, head.name, null)
    val title = DinnerMessage.cleanText(b.opt("t") as? String, MAX_TITLE)?.takeIf { it.isNotBlank() } ?: return null
    val artist = DinnerMessage.cleanText(b.opt("a") as? String, MAX_ARTIST) ?: ""
    val thumb = (b.opt("th") as? String)?.takeIf { it.startsWith(HTTPS) && it.length <= MAX_THUMB && it.none(Char::isWhitespace) } ?: ""
    val track = FollowMeTrack(title, artist, thumb, duration(b.opt("pos"), MAX_POSITION_MS))
    return LiveNote(head.presence, head.forMs, head.name, track)
  }

  private fun duration(raw: Any?, max: Long): Long = ((raw as? Number)?.toLong() ?: 0L).coerceIn(0L, max)

  private fun clip(text: String, max: Int): String =
      if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))
}
