/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject

/** What a list row points at, when the page makes that inferable. */
enum class YtMusicItemKind(val wire: String) {
  SONG("song"),
  VIDEO("video"),
  PLAYLIST("playlist"),
  ALBUM("album"),
  ARTIST("artist"),
  OTHER("other");

  companion object {
    fun fromWire(value: Any?): YtMusicItemKind = entries.firstOrNull { it.wire == value } ?: OTHER
  }
}

/** One native row read from the page. [index] is the row's position in the page's own list. */
data class YtMusicListItem(
    val index: Int,
    val title: String,
    val subtitle: String,
    val duration: String,
    val artUrl: String,
    val playing: Boolean,
    val kind: YtMusicItemKind,
    /** False when the page offers no play target for this row (tapping would only navigate). */
    val playable: Boolean = true,
)

/**
 * A validated list snapshot for one tab. [generation] changes whenever the page's list changes, so
 * a tap on a stale row is refused by the page instead of playing the wrong thing. [onPage] is false
 * while the page has not reached the list's location yet (e.g. still navigating to /library).
 */
data class YtMusicList(
    val tab: YtMusicTab,
    val generation: Int,
    val items: List<YtMusicListItem>,
    val onPage: Boolean = true,
    val signedOut: Boolean = false,
    val query: String = "",
)

/**
 * Pure parser for list messages from `assets/ytmusic/bridge.js`. Page data is untrusted: the
 * envelope must be exact, items are validated one by one (bad rows are dropped, not the list),
 * text is sanitized and capped, and art must be https on a Google image host.
 *
 * Wire format: `{"v":1,"type":"list","k":"queue"|"library"|"search","g":generation,
 * "here":bool,"so":signedOut,"q":query,"items":[{"i":index,"t":title,"s":subtitle,"d":"m:ss",
 * "art":url,"p":playing,"k":kind,"pl":playable}]}`.
 */
object YtMusicListParser {
  const val MAX_MESSAGE_CHARS = 192 * 1024
  const val MAX_ITEMS = 60
  const val MAX_ITEM_TEXT_CHARS = 200
  /** The script scans at most a few hundred page rows; anything beyond is hostile. */
  const val MAX_INDEX = 999
  private const val PREFIX = "{\"v\":1,\"type\":\"list\""
  private val duration = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")

  /** Cheap routing check; the message is still fully validated by [parse]. */
  fun isListMessage(raw: String?): Boolean = raw != null && raw.startsWith(PREFIX)

  fun parse(json: String?): YtMusicList? {
    if (json.isNullOrBlank() || json.length > MAX_MESSAGE_CHARS) return null
    val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (obj.opt("v") != 1 || obj.opt("type") != "list") return null
    val tab = YtMusicTab.entries.firstOrNull { it.pageKey == obj.opt("k") } ?: return null
    val generation = exactInt(obj.opt("g"))?.takeIf { it >= 0 } ?: return null
    val rawItems = obj.opt("items") as? JSONArray ?: return null
    return YtMusicList(
        tab = tab,
        generation = generation,
        items = items(rawItems),
        onPage = obj.opt("here") == true,
        signedOut = obj.opt("so") == true,
        query = YtMusicSearch.normalize(obj.opt("q") as? String).orEmpty(),
    )
  }

  /** Script that plays row [index] of [tab]'s list, if the page still shows [generation]. */
  fun playScript(tab: YtMusicTab, index: Int, generation: Int): String? {
    if (index !in 0..MAX_INDEX || generation < 0) return null
    return "window.__portalYtm&&window.__portalYtm.playItem('${tab.pageKey}',$index,$generation);"
  }

  /** Script that makes the page report [tab]'s list now and whenever it changes. */
  fun watchScript(tab: YtMusicTab): String =
    "window.__portalYtm&&window.__portalYtm.watch('${tab.pageKey}');"

  private fun items(array: JSONArray): List<YtMusicListItem> {
    val out = ArrayList<YtMusicListItem>(minOf(array.length(), MAX_ITEMS))
    val seen = HashSet<Int>()
    var playingSeen = false
    for (n in 0 until array.length()) {
      if (out.size >= MAX_ITEMS) break
      val item = item(array.opt(n) as? JSONObject ?: continue) ?: continue
      if (!seen.add(item.index)) continue
      val playing = item.playing && !playingSeen
      playingSeen = playingSeen || playing
      out += if (playing == item.playing) item else item.copy(playing = playing)
    }
    return out
  }

  private fun item(obj: JSONObject): YtMusicListItem? {
    val index = exactInt(obj.opt("i"))?.takeIf { it in 0..MAX_INDEX } ?: return null
    val title = YtMusicBridgeParser.cleanText(obj.opt("t"), MAX_ITEM_TEXT_CHARS)
    if (title.isEmpty()) return null
    val rawDuration = obj.opt("d") as? String
    return YtMusicListItem(
        index = index,
        title = title,
        subtitle = YtMusicBridgeParser.cleanText(obj.opt("s"), MAX_ITEM_TEXT_CHARS),
        duration = rawDuration?.takeIf { duration.matches(it) }.orEmpty(),
        artUrl = YtMusicBridgeParser.artUrl(obj.opt("art")),
        playing = obj.opt("p") == true,
        kind = YtMusicItemKind.fromWire(obj.opt("k")),
        playable = obj.opt("pl") != false,
    )
  }

  /** Integers only: org.json yields Integer/Long for whole numbers and Double for fractions. */
  private fun exactInt(value: Any?): Int? = when (value) {
    is Int -> value
    is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
    else -> null
  }
}
