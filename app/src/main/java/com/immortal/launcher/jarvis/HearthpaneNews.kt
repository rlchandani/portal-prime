/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.BriefingCopy
import com.immortal.launcher.NewsFeed
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.news` for Jarvis: "what's the news", "play my flash briefing", "what's the latest from the BBC" → the
 * top headlines from the household's own news feeds (Settings › News, the ones the News widget shows), or NPR's top
 * stories when none are added. Keyless public RSS, so it works on every Jarvis model, with or without a Search key.
 * Headlines interleave the feeds so no one source dominates, repeats are dropped, and no link reaches the model.
 * Arguments are untrusted (model output). Pure apart from its seams, so every rule is JVM-tested.
 */
internal class HearthpaneNews(
    /** Each feed's name and headlines, fetched within the tool's budget; a feed that didn't answer has none. */
    private val sources: () -> List<Source>,
    /** No feed is added, so [sources] is the built-in default. */
    private val defaulted: () -> Boolean = { false },
) {
  data class Source(val name: String, val headlines: List<NewsFeed.Headline>)

  private data class Item(val title: String, val source: String)

  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching { answer(args) }.getOrElse { error("news unavailable") }
  }

  private fun answer(args: JSONObject): JSONObject {
    val all = sources()
    if (all.isEmpty()) return reply("No news feeds are set up on this Portal. Search the web for headlines instead.")
    val asked = (args.opt("source") as? String)?.trim().orEmpty()
    val picked = if (asked.isEmpty()) all else {
      val name = RoomMatch.find(asked, all.map { it.name })
          ?: return reply("I only have news from ${BriefingCopy.joinNames(all.map { it.name })}. " +
              "Search the web for ${asked.take(MAX_SOURCE)} instead.")
      all.filter { it.name == name }
    }
    val count = (args.opt("count") as? Number)?.toInt()?.coerceIn(1, MAX_COUNT) ?: DEFAULT_COUNT
    val items = interleave(picked).take(count)
    if (items.isEmpty()) return reply("I couldn't reach the news feeds just now.")
    val out = JSONObject().put("ok", true)
        .put("headlines", JSONArray(items.map { JSONObject().put("title", it.title).put("source", it.source) }))
        .put("message", message(items))
    if (defaulted()) out.put("note", "These are NPR's top stories; the household can choose its own feeds in Settings, under News.")
    return out
  }

  /** One from each feed in turn, a headline already taken (any feed) dropped. */
  private fun interleave(sources: List<Source>): List<Item> {
    val seen = HashSet<String>()
    val out = ArrayList<Item>()
    val longest = sources.maxOfOrNull { it.headlines.size } ?: 0
    for (i in 0 until longest) for (s in sources) {
      val title = s.headlines.getOrNull(i)?.title?.trim()?.take(MAX_TITLE)?.ifEmpty { null } ?: continue
      if (seen.add(title.lowercase())) out.add(Item(title, s.name.trim().ifEmpty { "News" }))
    }
    return out
  }

  /** "From BBC: A. B. From NPR: C." — grouped by feed, in the order the feeds are listed. */
  private fun message(items: List<Item>): String = items.groupBy { it.source }.entries.joinToString(" ") { (source, list) ->
    "From $source: " + list.joinToString(" ") { it.title.trimEnd('.', ' ') + "." }
  }

  private fun reply(message: String) = JSONObject().put("ok", false).put("message", message)

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.news"
    const val DEFAULT_COUNT = 5
    const val MAX_COUNT = 8
    const val MAX_TITLE = 160
    private const val MAX_SOURCE = 40
    /** The feed used when the household has added none: NPR's top stories, one of Settings › News' suggestions. */
    const val DEFAULT_NAME = "NPR"
    const val DEFAULT_URL = "https://feeds.npr.org/1001/rss.xml"
  }
}
