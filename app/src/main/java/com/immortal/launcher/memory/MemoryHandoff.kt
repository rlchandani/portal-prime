/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Jarvis ↔ Hearthpane memory, the non-model provider methods (plan §3.3–3.4), Android-free:
 * - [context] (`memory_context`, conversation open): freezes whose conversation it is and answers the note lines
 *   for Jarvis's prompt, plus whether to hand the conversation over at its end (`learning`).
 * - [observe] (`memory_observe`, conversation end): runs the extractor over the digest and learns, then drops the
 *   digest. Answers only ids and labels of what became known, for the "Learned … Undo" notice.
 * - [undo] (`memory_forget`): that notice's Undo; only facts the latest conversation touched.
 * Nothing here logs or keeps conversation text.
 */
internal class MemoryHandoff(
    private val runtime: MemoryRuntime,
    private val dictionary: () -> MemoryDictionary,
    private val accessNow: () -> MemoryAccess,
    private val portal: () -> String,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
    private val recordUsage: (MemoryScope, String) -> Unit = { _, _ -> },
) {
  private val lock = Any()
  private var conversations = MemoryConversations()

  fun context(argsJson: String?): JSONObject {
    val args = json(argsJson)
    val id = args?.optString("conversation")?.takeIf(ID::matches) ?: return JSONObject().put("error", "bad conversation")
    val screen = args.optString("screen").takeIf(SCREEN::matches)
    val access = accessNow()
    val nowMs = now()
    synchronized(lock) { conversations = conversations.opened(id, access, nowMs) }
    val on = access as? MemoryAccess.On ?: return off(access as MemoryAccess.Off)
    recordUsage(on.scope, JARVIS_SURFACE)
    val profile = runtime.profile(on.scope)
    val lines = MemoryNote.lines(MemoryNote.Inputs(access, profile, nowMs, zone(), screen))
    return JSONObject()
        .put("learning", !profile.paused)
        .put("scope", if (on.scope is MemoryScope.Household) "household" else "person")
        .apply { if (lines.isNotEmpty()) put("note", lines.joinToString("\n")) }
  }

  fun observe(argsJson: String?): JSONObject {
    val digest = MemoryDigest.parse(argsJson) ?: return JSONObject().put("error", "bad digest")
    val nowMs = now()
    val frozen = synchronized(lock) { conversations.accessFor(digest.conversationId, nowMs) } ?: accessNow()
    val current = accessNow()
    val on = frozen as? MemoryAccess.On
    if (on == null || current is MemoryAccess.Off) {
      synchronized(lock) { conversations = conversations.finished(digest.conversationId, null, emptySet(), nowMs) }
      return JSONObject().put("learned", JSONArray()).put("ignored", true)
    }
    val known = runtime.profile(on.scope).facts.map { it.id }.toSet()
    val observations = MemoryExtractor(dictionary()).extract(digest, known)
    var learned = emptyList<MemoryFact>()
    val next = runtime.update(on.scope) { profile ->
      MemoryLearner.learn(profile, observations, digest.conversationId, portal(), nowMs).also { learned = it.learned }
          .profile
    }
    val touched = observations.map { it.factId }.filter { id -> next.facts.any { it.id == id && it.label.isNotEmpty() } }
    synchronized(lock) {
      conversations = conversations.finished(digest.conversationId, on.scope, touched.toSet(), nowMs)
    }
    return JSONObject().put("learned", JSONArray().apply {
      learned.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("kind", it.kind.name.lowercase())) }
    }).put("observed", observations.size)
  }

  fun undo(argsJson: String?): JSONObject {
    val ids = json(argsJson)?.optJSONArray("ids")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }
        .orEmpty().toSet()
    val nowMs = now()
    val (scope, allowed) = synchronized(lock) {
      val s = conversations.lastTouchedScope
      s to (s?.let { conversations.touchedNow(it, nowMs) }.orEmpty())
    }
    val forget = ids.intersect(allowed)
    if (scope == null || forget.isEmpty()) return JSONObject().put("forgotten", 0)
    runtime.update(scope, immediate = true) { MemoryLearner.forget(it, forget, nowMs) }
    return JSONObject().put("forgotten", forget.size)
  }

  /** `hearthpane.memory`, scoped to the conversation under way (or to who is there now). */
  fun tool(show: () -> Unit): MemoryTool = MemoryTool(
      access = { synchronized(lock) { conversations.liveAccess(now()) }?.takeIf { accessNow() !is MemoryAccess.Off }
          ?: accessNow() },
      profile = runtime::profile,
      update = { scope, change -> runtime.update(scope, immediate = true, change = change) },
      dictionary = dictionary,
      lastTouched = {
        val nowMs = now()
        synchronized(lock) { conversations.lastTouchedScope?.let { conversations.touchedNow(it, nowMs) }.orEmpty() }
      },
      conversationId = { synchronized(lock) { conversations.currentId } },
      portal = portal,
      now = now,
      show = show,
  )

  private fun off(access: MemoryAccess.Off) =
      JSONObject().put("learning", false).put("scope", "off").put("reason", access.reason.name.lowercase())

  private fun json(text: String?): JSONObject? = try {
    JSONObject(text ?: "{}")
  } catch (_: JSONException) {
    null
  }

  companion object {
    const val METHOD_CONTEXT = "memory_context"
    const val METHOD_OBSERVE = "memory_observe"
    const val METHOD_FORGET = "memory_forget"
    const val JARVIS_SURFACE = "jarvis"
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val SCREEN = Regex("[a-z0-9_]{1,32}")
  }
}
