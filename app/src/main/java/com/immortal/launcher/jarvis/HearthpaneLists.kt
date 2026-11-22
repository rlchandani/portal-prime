/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.HouseholdItem
import com.immortal.launcher.HouseholdListOps
import com.immortal.launcher.HouseholdListState
import com.immortal.launcher.HouseholdListText
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `hearthpane.lists` for Jarvis: add, read, tick off and remove items on Hearthpane's household list. Hearthpane
 * has one shared list for shopping and to-dos alike (the old To-do list was merged into it), so "add milk to the
 * shopping list" and "add fix the sink to my to-do list" both land there, and sync to the other Portals and phones.
 * Arguments are untrusted (model output). Pure apart from its seams, so every rule is JVM-tested.
 */
internal class HearthpaneLists(
    private val state: () -> HouseholdListState,
    /** Applies one edit atomically to the live list (the store's lock-and-merge), returning its outcome. */
    private val edit: ((HouseholdListState) -> HouseholdListOps.Result) -> HouseholdListOps.Outcome,
    private val by: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
    /** True while guests are here and the list is hidden from them. */
    private val hidden: () -> Boolean = { false },
) {
  fun invoke(argsJson: String?): JSONObject {
    val args = try { JSONObject(argsJson ?: "{}") } catch (_: JSONException) { return error("arguments must be a JSON object") }
    return runCatching {
      when (args.optString("action").trim().lowercase()) {
        "add" -> add(items(args))
        "list", "read" -> read()
        "remove", "delete" -> change(items(args), remove = true)
        "done", "check", "complete", "tick" -> change(items(args), remove = false)
        else -> error("action must be add, list, done or remove")
      }
    }.getOrElse { error("lists unavailable") }
  }

  /** `items` (array) and/or `item` (one), trimmed, without blanks or repeats, at most [MAX_PER_CALL]. */
  private fun items(args: JSONObject): List<String> {
    val many = (args.opt("items") as? JSONArray)?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
    val one = listOfNotNull(args.opt("item") as? String)
    return (many + one).map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { HouseholdListText.norm(it) }.take(MAX_PER_CALL)
  }

  private fun add(items: List<String>): JSONObject {
    if (items.isEmpty()) return error("item is required: what to add, e.g. milk")
    val added = mutableListOf<String>()
    val already = mutableListOf<String>()
    val tooLong = mutableListOf<String>()
    for (raw in items) {
      val text = HouseholdListText.clean(raw)
      if (text == null) { tooLong += raw.take(MAX_ECHO); continue }
      when (edit { s -> HouseholdListOps.add(s, text, by(), now(), local = true) }) {
        is HouseholdListOps.Outcome.Applied, is HouseholdListOps.Outcome.MovedBack -> added += text
        is HouseholdListOps.Outcome.Duplicate -> already += text
        else -> tooLong += text
      }
    }
    val msg = buildString {
      if (added.isNotEmpty()) append("Added ${spoken(added)} to the household list.")
      if (already.isNotEmpty()) append(" ${spoken(already)} ${if (already.size == 1) "is" else "are"} already on it.")
      if (tooLong.isNotEmpty()) append(" Couldn't add ${spoken(tooLong)}: keep each item under ${HouseholdListText.MAX_TEXT} " +
          "characters, or the list is full.")
    }.trim()
    return JSONObject().put("ok", added.isNotEmpty() || already.isNotEmpty()).put("message", msg)
  }

  private fun read(): JSONObject {
    if (hidden()) return hiddenReply()
    val open = state().open()
    val shown = open.take(MAX_READ).map { it.text.value }
    val msg = when {
      open.isEmpty() -> "The household list is empty."
      open.size > MAX_READ -> "${open.size} things on the household list. The first $MAX_READ: ${spoken(shown)}."
      else -> "${open.size} on the household list: ${spoken(shown)}."
    }
    return JSONObject().put("ok", true).put("count", open.size).put("items", JSONArray(shown)).put("message", msg)
  }

  private fun change(items: List<String>, remove: Boolean): JSONObject {
    if (hidden()) return hiddenReply()
    if (items.isEmpty()) return error("item is required: which item, in the user's words")
    val done = mutableListOf<String>()
    val missing = mutableListOf<String>()
    for (said in items) {
      val target = find(state(), said)
      if (target == null) { missing += said.take(MAX_ECHO); continue }
      val outcome = edit { s ->
        if (remove) HouseholdListOps.remove(s, target.id, by(), now(), local = true)
        else HouseholdListOps.setDone(s, target.id, true, by(), now(), local = true)
      }
      if (outcome is HouseholdListOps.Outcome.Applied) done += target.text.value else missing += said.take(MAX_ECHO)
    }
    val verb = if (remove) "Removed" else "Ticked off"
    val msg = buildString {
      if (done.isNotEmpty()) append("$verb ${spoken(done)}.")
      if (missing.isNotEmpty()) append(" ${spoken(missing)} ${if (missing.size == 1) "isn't" else "aren't"} on the household list.")
    }.trim()
    return JSONObject().put("ok", done.isNotEmpty()).put("message", msg)
  }

  /** The open item the user meant: its exact text, else the one item whose words cover what was said. */
  private fun find(s: HouseholdListState, said: String): HouseholdItem? {
    val open = s.open()
    val norm = HouseholdListText.norm(said)
    open.firstOrNull { HouseholdListText.norm(it.text.value) == norm }?.let { return it }
    val want = words(said)
    if (want.isEmpty()) return null
    return open.filter { item -> words(item.text.value).let { have -> have.isNotEmpty() && (have.containsAll(want) || want.containsAll(have)) } }
        .singleOrNull()
  }

  private fun words(text: String): Set<String> =
      text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() && it !in STOP }.map(::singular).toSet()

  private fun singular(w: String) = if (w.length > 3 && w.endsWith("s") && !w.endsWith("ss")) w.dropLast(1) else w

  private fun hiddenReply() = JSONObject().put("ok", false).put("message", "The household list is hidden while guests are here.")

  private fun error(message: String) = JSONObject().put("error", message)

  companion object {
    const val NAME = "hearthpane.lists"
    private const val MAX_PER_CALL = 10
    private const val MAX_READ = 12
    private const val MAX_ECHO = 60
    private val STOP = setOf("the", "a", "an", "some", "of", "my", "our")

    /** "milk", "milk and eggs", "milk, eggs and bread". */
    fun spoken(items: List<String>): String = when (items.size) {
      0 -> ""
      1 -> items[0]
      else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
  }
}
