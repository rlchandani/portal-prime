/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.memory

import com.immortal.launcher.memory.MemoryFact.Kind
import com.immortal.launcher.memory.MemoryFact.State
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.memory` (plan §3.5): the optional explicit controls over what Jarvis learned — recall, remember,
 * forget, pause, resume. Learning never depends on it. Arguments come from the model, so they are untrusted: the
 * action must be one of the five, `about` is capped, and remember only accepts what the dictionary knows.
 *
 * Refused (with a reason to say) whenever the moment is [MemoryAccess.Off]: the switch is off, Guest mode, a child,
 * a teen. "Forget that" means the facts the latest conversation touched. Pure apart from its seams.
 */
internal class MemoryTool(
    private val access: () -> MemoryAccess,
    private val profile: (MemoryScope) -> MemoryProfile,
    private val update: (MemoryScope, (MemoryProfile) -> MemoryProfile) -> MemoryProfile,
    private val dictionary: () -> MemoryDictionary,
    /** Fact ids the latest conversation touched, for "forget that". */
    private val lastTouched: () -> Set<String>,
    private val conversationId: () -> String?,
    private val portal: () -> String,
    private val now: () -> Long,
    /** Opens "What Jarvis knows" on the Portal. */
    private val show: () -> Unit,
) {
  fun invoke(argsJson: String?): JSONObject {
    val args = try {
      JSONObject(argsJson ?: "{}")
    } catch (_: JSONException) {
      return error("arguments are not JSON")
    }
    val action = args.optString("action").trim().lowercase()
    if (action !in ACTIONS) return error("action must be recall, remember, forget, pause or resume")
    val on = when (val a = access()) {
      is MemoryAccess.Off -> return refused(a.reason)
      is MemoryAccess.On -> a
    }
    val about = args.optString("about").trim().take(MAX_ABOUT)
    val kind = kindOf(args.optString("kind"))
    if (kind == UNSUPPORTED) return message("I don't keep places, routines or preferences yet.")
    return when (action) {
      RECALL -> recall(on, kind, args.optBoolean("show", false))
      REMEMBER -> remember(on, about, kind)
      FORGET -> forget(on, about, kind)
      else -> pause(on, action == PAUSE)
    }
  }

  private fun recall(on: MemoryAccess.On, kind: Kind?, open: Boolean): JSONObject {
    if (open) show()
    val facts = profile(on.scope).facts.filter { kind == null || it.kind == kind }
    val usable = facts.filter(MemoryConfidence::usable)
    val learning = facts.count { it.state == State.TENTATIVE }
    val whose = if (on.scope is MemoryScope.Household) "For the household (nobody is recognised): " else ""
    if (usable.isEmpty()) {
      val extra = if (learning > 0) " I'm still learning $learning thing${if (learning == 1) "" else "s"}." else ""
      return message("${whose}I haven't learned anything for sure yet.$extra I learn as you talk; there's nothing to set up.")
    }
    val parts = RECALL_PHRASES.mapNotNull { (k, phrase) ->
      usable.filter { it.kind == k }.takeIf { it.isNotEmpty() }?.joinToString(", ", "$phrase ") { it.label }
    }
    val tail = if (learning > 0) " Still learning $learning more." else ""
    return message("$whose${parts.joinToString("; ")}.$tail").put("count", usable.size)
  }

  private fun remember(on: MemoryAccess.On, about: String, kind: Kind?): JSONObject {
    if (about.isEmpty()) return error("about is required")
    val found = dictionary().lookup(about, kind)
    val entry = when (found.size) {
      0 -> return message("I can remember sports teams, stocks, crypto and news topics for now, and I couldn't match that.")
      1 -> found.single()
      else -> return message("Which one: ${found.take(MAX_CHOICES).joinToString(" or ") { label(it) }}?")
          .put("needs_choice", true)
    }
    update(on.scope) {
      MemoryLearner.confirm(it, entry, Evidence.Source.EXPLICIT, conversationId(), portal(), now())
    }
    val where = if (on.scope is MemoryScope.Household) " for the household" else ""
    return message("Okay, I'll remember ${label(entry)}$where.").put("remembered", entry.label)
  }

  private fun forget(on: MemoryAccess.On, about: String, kind: Kind?): JSONObject {
    val words = about.lowercase()
    if (words in EVERYTHING) {
      show()
      return message("To forget everything, use Forget all on the What Jarvis knows screen. I've opened it.")
    }
    val current = profile(on.scope)
    val ids = if (words in THAT) {
      lastTouched()
    } else {
      val tokens = MemoryText.tokens(about).dropWhile { it == "the" }
      val byName = dictionary().lookup(about, kind).map { it.factId }
      val byLabel = current.facts.filter { f ->
        (kind == null || f.kind == kind) && f.label.isNotBlank() && MemoryText.tokens(f.label).containsAll(tokens)
      }.map { it.id }
      (byName + byLabel).toSet()
    }
    if (ids.isEmpty()) return message("I don't have anything like that to forget.")
    val labels = current.facts.filter { it.id in ids && it.label.isNotBlank() }.map { it.label }
        .ifEmpty { ids.mapNotNull { dictionary().entry(it)?.label } }
    update(on.scope) { MemoryLearner.forget(it, ids, now()) }
    return message("Okay, I've forgotten ${labels.joinToString(" and ").ifEmpty { "that" }} and won't pick it up again.")
        .put("forgotten", ids.size)
  }

  private fun pause(on: MemoryAccess.On, paused: Boolean): JSONObject {
    update(on.scope) { MemoryLearner.setPaused(it, paused) }
    return message(if (paused) "Learning paused. I'll keep what I know but learn nothing new." else "Learning is back on.")
  }

  private fun refused(reason: MemoryAccess.Reason): JSONObject = message(
      when (reason) {
        MemoryAccess.Reason.DISABLED ->
          "Learning from conversations is off. It can be turned on in Hearthpane Settings, under Jarvis."
        MemoryAccess.Reason.GUEST -> "Guest mode is on, so this chat isn't remembered."
        MemoryAccess.Reason.CHILD -> "I don't keep anything about children."
        MemoryAccess.Reason.TEEN -> "I don't keep anything for teens yet."
      },
  ).put("refused", true)

  private fun label(entry: MemoryDictionary.Entry): String =
      entry.attrs[MemoryDictionary.ATTR_LEAGUE]?.let { "the ${entry.label} ($it)" } ?: entry.label

  private fun message(text: String) = JSONObject().put("ok", true).put("message", text)

  private fun error(text: String) = JSONObject().put("error", text)

  companion object {
    const val NAME = "hearthpane.memory"
    private const val RECALL = "recall"
    private const val REMEMBER = "remember"
    private const val FORGET = "forget"
    private const val PAUSE = "pause"
    private val ACTIONS = setOf(RECALL, REMEMBER, FORGET, PAUSE, "resume")
    private const val MAX_ABOUT = 80
    private const val MAX_CHOICES = 3
    private val THAT = setOf("", "that", "it", "this", "those", "them", "what you just learned")
    private val EVERYTHING = setOf("everything", "all", "all of it", "everything about me")
    private val UNSUPPORTED: Kind? = Kind.PLACE
    private val RECALL_PHRASES = listOf(
        Kind.TEAM to "you follow", Kind.TICKER to "you watch", Kind.CRYPTO to "crypto:",
        Kind.NEWS_TOPIC to "news about", Kind.MUSIC_TASTE to "you like",
    )

    /** The `kind` argument; null for any. Places, routines and preferences aren't kept yet ([UNSUPPORTED]). */
    fun kindOf(arg: String): Kind? = when (arg.trim().lowercase()) {
      "team" -> Kind.TEAM
      "ticker" -> Kind.TICKER
      "crypto" -> Kind.CRYPTO
      "news_topic" -> Kind.NEWS_TOPIC
      "music" -> Kind.MUSIC_TASTE
      "place", "routine", "preference" -> UNSUPPORTED
      else -> null
    }
  }
}
