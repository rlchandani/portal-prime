/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Jarvis ↔ Hearthpane memory, the non-model provider methods (plan §3.3–3.4), Android-free:
 * - [context] (`memory_context`, conversation open): freezes whose conversation it is and answers the note lines
 *   for Jarvis's prompt, plus whether to hand the conversation over at its end (`learning`) and, from slice 4,
 *   whether to run the text extractor first (`extract`, with the `known` fact ids it may deduplicate against; only
 *   while plan D3's "closer reading" switch is on too, otherwise Jarvis hands over without it). The
 *   extracted Recent topics the note offers are marked shown, so Jarvis follows each up once. For the card's badges
 *   ([MemoryCard]): `who` (first name and room, only for an adult alone with Jarvis) and the `facts` the note used.
 * - [observe] (`memory_observe`, conversation end): runs the dictionary extractor over the digest, re-checks the
 *   text extractor's suggestions ([LlmExtractionCheck]) and learns, then drops the digest. Answers only ids and labels
 *   of what became known, for the "Learned … Undo" notice.
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
    /** Plan D3's own switch: whether Jarvis may run its text extractor (the cloud read) at all. */
    private val closerReading: () -> Boolean = { true },
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
    val note = MemoryNote.build(MemoryNote.Inputs(access, profile, nowMs, zone(), screen))
    if (note.offeredTopics.isNotEmpty()) runtime.update(on.scope) { MemoryLearner.markShown(it, note.offeredTopics) }
    val learning = !profile.paused
    return JSONObject()
        .put("learning", learning)
        .put("scope", if (on.scope is MemoryScope.Household) "household" else "person")
        .apply { if (note.lines.isNotEmpty()) put("note", note.lines.joinToString("\n")) }
        .apply { if (learning && closerReading()) put("extract", true).put("known", JSONArray(knownIds(profile))) }
        .apply { threads(profile, note.offeredTopics).takeIf { it.length() > 0 }?.let { put("threads", it) } }
        .apply { MemoryCard.who(on, portal())?.let { put("who", it) } }
        .apply { MemoryCard.facts(note.used, dictionary())?.let { put("facts", it) } }
  }

  /**
   * The offered Recent topics as the card sees them: the day each came up and the words naming it, so the card can
   * say "Picked up from Tuesday" when Jarvis follows one up (mock `.used`). No gist, nothing more.
   */
  private fun threads(profile: MemoryProfile, offered: Set<String>): JSONArray = JSONArray().apply {
    profile.recentTopics.filter { it.topic in offered }.forEach { t ->
      val day = Instant.ofEpochMilli(t.atMs).atZone(zone()).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
      val words = t.topic.substringAfter(':').split('-').filter { it.length >= THREAD_WORD }.take(THREAD_WORDS)
      if (words.isNotEmpty()) put(JSONObject().put("day", day).put("words", JSONArray(words)))
    }
  }

  /** What the text extractor may deduplicate against: the usable, non-sensitive fact ids, most sure first. */
  private fun knownIds(profile: MemoryProfile): List<String> = profile.facts
      .filter { MemoryConfidence.usable(it) && !it.sensitive }
      .sortedByDescending { it.confidence }.take(MAX_KNOWN).map { it.id }

  /**
   * The [kind] facts known for whoever the conversation under way belongs to (the scope [tool] uses), most sure
   * first, for "how did my team do" / "how are my stocks"; only usable, non-sensitive ones. Null when memory is off
   * for this moment (switch off, guests, children), so the caller asks instead of guessing.
   */
  fun interests(kind: MemoryFact.Kind): List<MemoryFact>? {
    val nowMs = now()
    val current = accessNow()
    val access = if (current is MemoryAccess.Off) current else synchronized(lock) { conversations.liveAccess(nowMs) } ?: current
    val on = access as? MemoryAccess.On ?: return null
    return runtime.profile(on.scope).facts
        .filter { it.kind == kind && MemoryConfidence.usable(it) && !it.sensitive && it.label.isNotBlank() }
        .sortedWith(compareByDescending<MemoryFact> { it.confidence }.thenByDescending { it.lastSeenMs })
  }

  fun observe(argsJson: String?): JSONObject {
    val digest = MemoryDigest.parse(argsJson) ?: return JSONObject().put("error", "bad digest")
    val nowMs = now()
    val frozen = synchronized(lock) { conversations.accessFor(digest.conversationId, nowMs) } ?: accessNow()
    val current = accessNow()
    val on = frozen as? MemoryAccess.On
    // Someone else recognised by the end: the words may be theirs, so they go to nobody's profile.
    val someoneElse = current is MemoryAccess.On && current.scope is MemoryScope.Person && current.scope != on?.scope
    if (on == null || current is MemoryAccess.Off || someoneElse) {
      synchronized(lock) { conversations = conversations.finished(digest.conversationId, null, emptySet(), nowMs) }
      return JSONObject().put("learned", JSONArray()).put("ignored", true)
    }
    val known = runtime.profile(on.scope).facts.map { it.id }.toSet()
    val words = dictionary()
    val fromWords = MemoryExtractor(words).extract(digest, known)
    // With the closer-reading switch off, anything a text extractor still sent is dropped unread.
    val extracted = if (closerReading()) digest.extracted else null
    val suggested = LlmExtractionCheck(words).check(extracted, digest.userTurns, nowMs)
    val observations = (fromWords.filter { it.factId !in suggested.negated } + suggested.observations)
        .groupBy { it.factId }.map { (_, same) -> same.maxBy { MemoryConfidence.weightOf(it.source) } }
        .sortedBy { it.factId }
    var learned = emptyList<MemoryFact>()
    val next = runtime.update(on.scope) { profile ->
      MemoryLearner.learn(profile, observations, digest.conversationId, portal(), nowMs, suggested.topics)
          .also { learned = it.learned }.profile
    }
    val touched = observations.map { it.factId }.filter { id -> next.facts.any { it.id == id && it.label.isNotEmpty() } }
    synchronized(lock) {
      conversations = conversations.finished(digest.conversationId, on.scope, touched.toSet(), nowMs)
    }
    return JSONObject().put("learned", JSONArray().apply {
      learned.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("kind", it.kind.name.lowercase())) }
    }).put("observed", observations.size)
        .put("extracted", suggested.observations.size).put("topics", suggested.topics.size)
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
      access = {
        val current = accessNow()
        if (current is MemoryAccess.Off) current else synchronized(lock) { conversations.liveAccess(now()) } ?: current
      },
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
    const val MAX_KNOWN = 20
    private const val THREAD_WORD = 3
    private const val THREAD_WORDS = 4
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val SCREEN = Regex("[a-z0-9_]{1,32}")
  }
}
