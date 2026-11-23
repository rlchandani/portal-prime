/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the slice 4 text extractor suggested for one conversation (plan §3.3): Jarvis asks a small Gemini Flash model
 * (or the LAN host on the Local backend) after the live session closed, over the person's own turns only, and hands
 * the answer over inside the `memory_observe` digest as `extracted`. It is a suggestion from untrusted JSON: parsed
 * here with hard caps, then checked against the person's own words and the dictionaries by [LlmExtractionCheck]
 * before anything is learned. Never stored or logged.
 */
internal data class LlmExtraction(val facts: List<Fact>, val topics: List<Topic>) {
  /** One suggested interest; [evidenceQuote] is the person's own words it came from. */
  data class Fact(
      val kind: String,
      val canonical: String,
      val label: String,
      val negative: Boolean,
      val evidenceQuote: String,
      val intent: Boolean,
  )

  /** One thread worth picking up again: what it was about, a one-line gist and what was left open. */
  data class Topic(val topic: String, val gist: String, val openQuestion: String?)

  companion object {
    const val MAX_FACTS = 12
    const val MAX_TOPICS = 3
    const val MAX_KIND = 16
    const val MAX_CANONICAL = 64
    const val MAX_LABEL = 40
    const val MAX_QUOTE = 60
    const val MAX_TOPIC = 40
    const val MAX_QUESTION = 100

    /** The extraction in [o] (the digest's `extracted`), or null when there is none. Oversized fields are dropped. */
    fun parse(o: JSONObject?): LlmExtraction? {
      if (o == null) return null
      return LlmExtraction(
          facts = objects(o.optJSONArray("facts"), MAX_FACTS).mapNotNull(::fact),
          topics = objects(o.optJSONArray("recentTopics"), MAX_TOPICS).mapNotNull(::topic),
      )
    }

    private fun fact(o: JSONObject): Fact? {
      val kind = text(o, "kind", MAX_KIND) ?: return null
      val label = text(o, "label", MAX_LABEL) ?: return null
      val quote = text(o, "evidenceQuote", MAX_QUOTE) ?: return null
      val canonical = text(o, "canonical", MAX_CANONICAL) ?: label
      val polarity = text(o, "polarity", MAX_KIND)?.lowercase()
      return Fact(kind.lowercase(), canonical, label, polarity == "negative" || polarity == "dislike", quote,
          o.optBoolean("intent", false))
    }

    private fun topic(o: JSONObject): Topic? {
      val topic = text(o, "topic", MAX_TOPIC) ?: return null
      val gist = text(o, "gist", RecentTopic.MAX_GIST) ?: return null
      return Topic(topic, gist, text(o, "openQuestion", MAX_QUESTION))
    }

    /** Text at [name], trimmed, or null when missing, blank or longer than [max] (too long is dropped, not cut). */
    private fun text(o: JSONObject, name: String, max: Int): String? =
        (o.opt(name) as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }

    private fun objects(a: JSONArray?, max: Int): List<JSONObject> =
        if (a == null) emptyList() else (0 until minOf(a.length(), max)).mapNotNull { a.optJSONObject(it) }
  }
}
