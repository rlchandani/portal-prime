/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What Jarvis hands over when a conversation ends (`memory_observe`, plan §3.3): the person's own turns and the
 * allow-listed tool calls, nothing Jarvis said. It lives only in memory for the length of one extraction and is
 * never stored or logged. Parsed from untrusted JSON with hard caps, so an oversized or odd payload is cut down,
 * not trusted.
 */
internal data class MemoryDigest(
    val conversationId: String,
    val startedAtMs: Long,
    val screen: String?,
    val userTurns: List<String>,
    val toolCalls: List<ToolCall>,
) {
  /** One structured call, by name with its free-text query (web search terms, what to play). */
  data class ToolCall(val name: String, val query: String)

  companion object {
    const val MAX_TURNS = 40
    const val MAX_TURN_CHARS = 1_000
    const val MAX_TOTAL_CHARS = 4_000
    const val MAX_TOOLS = 20
    const val MAX_QUERY_CHARS = 200
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val TOOL = Regex("[a-z0-9_.]{1,64}")
    private val SCREEN = Regex("[a-z0-9_]{1,32}")

    /** The digest in [json], or null when it isn't one (no valid conversation id). */
    fun parse(json: String?): MemoryDigest? {
      val o = try {
        JSONObject(json ?: return null)
      } catch (_: JSONException) {
        return null
      }
      val id = o.optString("conversation").takeIf(ID::matches) ?: return null
      return MemoryDigest(
          conversationId = id,
          startedAtMs = o.optLong("started_at", 0L).coerceAtLeast(0L),
          screen = o.optString("screen").takeIf(SCREEN::matches),
          userTurns = turns(o.optJSONArray("user")),
          toolCalls = tools(o.optJSONArray("tools")),
      )
    }

    private fun turns(a: JSONArray?): List<String> {
      if (a == null) return emptyList()
      var budget = MAX_TOTAL_CHARS
      return (0 until minOf(a.length(), MAX_TURNS)).mapNotNull { i ->
        val text = (a.opt(i) as? String)?.trim()?.take(minOf(MAX_TURN_CHARS, budget))
        if (text.isNullOrEmpty()) null else text.also { budget -= it.length }
      }
    }

    private fun tools(a: JSONArray?): List<ToolCall> {
      if (a == null) return emptyList()
      return (0 until minOf(a.length(), MAX_TOOLS)).mapNotNull { i ->
        val t = a.optJSONObject(i) ?: return@mapNotNull null
        val name = t.optString("name").takeIf(TOOL::matches) ?: return@mapNotNull null
        val query = (t.opt("query") as? String)?.trim()?.take(MAX_QUERY_CHARS)?.ifEmpty { null } ?: return@mapNotNull null
        ToolCall(name, query)
      }
    }
  }
}
