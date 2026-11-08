/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject

internal enum class MusicShareKind(val wire: String) { VIDEO("video"), PLAYLIST("playlist") }

/** What the phone asked for. The Portal may turn any of them into "now" when nothing is playing. */
internal enum class MusicShareAction(val wire: String) {
  NOW("now"), NEXT("next"), END("end");

  companion object {
    fun fromWire(value: String?): MusicShareAction? = entries.firstOrNull { it.wire == value }
  }
}

/** A validated `POST /remote/musicshare` body. Text is cleaned and capped; [thumb] is allowlisted or null. */
internal data class MusicShareRequest(
    val kind: MusicShareKind,
    /** A video id for [MusicShareKind.VIDEO], a playlist id for [MusicShareKind.PLAYLIST]. */
    val id: String,
    val listId: String?,
    val action: MusicShareAction,
    val shuffle: Boolean,
    val from: String,
    val title: String,
    val artist: String,
    val thumb: String?,
    val playAnyway: Boolean,
    val shareId: String?,
)

/** Pure validation of the phone's request (it is a paired session, but still untrusted input). */
internal object MusicShareRequests {
  const val MAX_FROM = 40
  const val MAX_TITLE = 120
  const val MAX_ARTIST = 80
  const val MAX_THUMB = 512
  private val VIDEO_ID = Regex("[A-Za-z0-9_-]{6,20}")
  private val LIST_ID = Regex("[A-Za-z0-9_-]{10,64}")
  private val SHARE_ID = Regex("[A-Za-z0-9]{4,32}")
  private val THUMB_HOSTS = setOf("i.ytimg.com", "yt3.ggpht.com", "lh3.googleusercontent.com")
  /** Mixes/radio (`RD…`) are built per listener inside YouTube Music; nothing can open them here. */
  private const val MIX_PREFIX = "RD"

  sealed interface Parsed {
    data class Ok(val request: MusicShareRequest) : Parsed
    data class Invalid(val code: String) : Parsed
  }

  fun isVideoId(id: String?): Boolean = id != null && VIDEO_ID.matches(id)
  fun isListId(id: String?): Boolean = id != null && LIST_ID.matches(id)

  fun parse(body: JSONObject): Parsed {
    val kind = MusicShareKind.entries.firstOrNull { it.wire == body.optString("kind") }
        ?: return Parsed.Invalid("invalid_kind")
    val action = MusicShareAction.fromWire(body.optString("action")) ?: return Parsed.Invalid("invalid_action")
    val id = body.optString("id")
    val listId = text(body, "listId", 64).ifEmpty { null }
    idProblem(kind, id, listId)?.let { return Parsed.Invalid(it) }
    return Parsed.Ok(MusicShareRequest(kind, id, listId, action,
        shuffle = body.optBoolean("shuffle", false),
        from = text(body, "from", MAX_FROM).ifEmpty { "A phone" },
        title = text(body, "title", MAX_TITLE),
        artist = text(body, "artist", MAX_ARTIST),
        thumb = thumb(body.optString("thumb")),
        playAnyway = body.optBoolean("playAnyway", false),
        shareId = body.optString("shareId").takeIf { SHARE_ID.matches(it) }))
  }

  private fun idProblem(kind: MusicShareKind, id: String, listId: String?): String? = when (kind) {
    // The queue only holds real 11-character ids; anything else would be dropped silently.
    MusicShareKind.VIDEO -> if (!isVideoId(id) || !YtTrack.isValidVideoId(id)) "invalid_id"
        else if (listId != null && !isListId(listId)) "invalid_list" else null
    MusicShareKind.PLAYLIST -> if (!isListId(id)) "invalid_list"
        else if (id.startsWith(MIX_PREFIX)) "mix_not_supported" else null
  }

  /** Https only, allowlisted YouTube image hosts, no credentials, capped length; otherwise null. */
  fun thumb(raw: String?): String? {
    val url = raw?.trim().orEmpty()
    if (url.isEmpty() || url.length > MAX_THUMB || url.any { it.isWhitespace() || it.isISOControl() }) return null
    if (!url.startsWith("https://")) return null
    val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#')
    if ('@' in authority || ':' in authority) return null
    return url.takeIf { authority.lowercase() in THUMB_HOSTS }
  }

  private fun text(body: JSONObject, key: String, max: Int): String =
      (body.opt(key) as? String).orEmpty().filterNot { it.isISOControl() }
          .replace(Regex("\\s+"), " ").trim().take(max).trim()
}
