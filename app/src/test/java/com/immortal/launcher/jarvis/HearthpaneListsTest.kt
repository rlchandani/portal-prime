/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher.jarvis

import com.immortal.launcher.HouseholdListOps
import com.immortal.launcher.HouseholdListState
import com.immortal.launcher.HouseholdListText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `hearthpane.lists`: one household list for shopping and to-dos, with every edit through the store. */
class HearthpaneListsTest {
  private var s = listOf("Eggs", "Paper towels", "Fix the sink").fold(HouseholdListState()) { st, t ->
    HouseholdListOps.add(st, t, "Kitchen", 1_000).state
  }
  private var hidden = false
  private var edits = 0
  private val tool = HearthpaneLists(state = { s }, edit = { op -> edits++; op(s).also { s = it.state }.outcome },
      by = { "Family Area" }, now = { 5_000 }, hidden = { hidden })

  private fun call(vararg kv: Pair<String, Any>) = tool.invoke(JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }.toString())
  private fun open() = s.open().map { it.text.value }

  @Test fun addPutsEachItemOnTheListAndSaysWhatWasAlreadyThere() {
    val r = call("action" to "add", "items" to JSONArray(listOf("milk", "Eggs", "bread")), "list" to "shopping")
    assertTrue(r.getBoolean("ok"))
    assertEquals("Added milk and bread to the household list. Eggs is already on it.", r.getString("message"))
    assertTrue(open().containsAll(listOf("milk", "bread")))
    assertEquals("Family Area", s.items.getValue(HouseholdListText.idFor("milk")).added.by)
  }

  @Test fun aSingleItemAndAToDoGoToTheSameList() {
    assertTrue(call("action" to "add", "item" to "call the plumber", "list" to "to-do").getBoolean("ok"))
    assertTrue("call the plumber" in open())
  }

  @Test fun tooLongItemsAndEmptyAddsAreRefused() {
    val r = call("action" to "add", "items" to JSONArray(listOf("x".repeat(HouseholdListText.MAX_TEXT + 1))))
    assertFalse(r.getBoolean("ok"))
    assertTrue(r.getString("message").contains("Couldn't add"))
    assertTrue(call("action" to "add").getString("error").contains("item is required"))
  }

  @Test fun listReadsOpenItemsInOrder() {
    val r = call("action" to "list")
    assertEquals(3, r.getInt("count"))
    assertTrue(r.getString("message"), r.getString("message").startsWith("3 on the household list:"))
    assertEquals(0, edits)
  }

  @Test fun anEmptyOrLongListReadsHonestly() {
    s = HouseholdListState()
    assertEquals("The household list is empty.", call("action" to "list").getString("message"))
    s = (1..15).fold(HouseholdListState()) { st, i -> HouseholdListOps.add(st, "item $i", "K", i.toLong()).state }
    val msg = call("action" to "list").getString("message")
    assertTrue(msg, msg.startsWith("15 things on the household list. The first 12:"))
  }

  @Test fun doneTicksOffByLooseWordsAndRemoveTakesOff() {
    val done = call("action" to "done", "items" to JSONArray(listOf("egg")))
    assertEquals("Ticked off Eggs.", done.getString("message"))
    assertFalse("Eggs" in open())
    val removed = call("action" to "remove", "item" to "paper towel")
    assertEquals("Removed Paper towels.", removed.getString("message"))
    assertEquals(listOf("Fix the sink"), open())
  }

  @Test fun missingOrAmbiguousItemsAreNotTouched() {
    s = HouseholdListOps.add(s, "Eggs large", "K", 2_000).state
    // "egg" loosely fits both egg items, so neither is ticked.
    assertFalse(call("action" to "done", "item" to "egg").getBoolean("ok"))
    assertEquals(4, open().size)
    // "eggs" is the exact text of one item, so that one goes; bananas isn't there.
    val r = call("action" to "remove", "items" to JSONArray(listOf("bananas", "eggs")))
    assertEquals("Removed Eggs. bananas isn't on the household list.", r.getString("message"))
    assertTrue(call("action" to "done", "item" to "large").getBoolean("ok"))
    assertTrue(call("action" to "done", "item" to "towels sink").getString("message").contains("isn't on"))
  }

  @Test fun guestsHideReadingAndChangingButNotAdding() {
    hidden = true
    assertTrue(call("action" to "list").getString("message").contains("hidden while guests"))
    assertTrue(call("action" to "remove", "item" to "Eggs").getString("message").contains("hidden while guests"))
    assertTrue(call("action" to "add", "item" to "milk").getBoolean("ok"))
  }

  @Test fun badArgumentsAreErrors() {
    assertTrue(call("action" to "shout").getString("error").contains("action must be"))
    assertTrue(tool.invoke("[").has("error"))
    assertEquals("", HearthpaneLists.spoken(emptyList()))
    assertEquals("a, b and c", HearthpaneLists.spoken(listOf("a", "b", "c")))
  }
}
