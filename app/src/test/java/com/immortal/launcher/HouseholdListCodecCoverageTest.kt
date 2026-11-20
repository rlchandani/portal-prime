/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A peer replica is all-or-nothing: any malformed, future-dated or duplicate item rejects the whole state. */
class HouseholdListCodecCoverageTest {
  private val now = 1_800_000_000_000L
  private val id = HouseholdListText.idFor("milk")

  private val full = HouseholdItem(
      id = id,
      text = TextReg("Milk", Stamp(now - 50, "Kitchen"), 0L),
      done = Reg(true, Stamp(now - 40, "Den")),
      order = Reg(-1.5, Stamp(now - 30, "Den")),
      added = Stamp(now - 60, "Kitchen"),
      removal = Removal(false, Stamp(now - 20, "Den"), now - 25),
      alt = AltText("Oat milk", "Den", now - 10),
      migratedFrom = setOf("todo", "notes"))

  private fun stateJson(vararg items: HouseholdItem) = HouseholdListCodec.encodeState(HouseholdListState(items.associateBy { it.id }))

  private fun itemJson() = JSONObject(stateJson(full).toString()).getJSONArray("items").getJSONObject(0)

  private fun decodeOne(item: JSONObject) =
      HouseholdListCodec.decodeState(JSONObject().put("v", 1).put("items", JSONArray().put(item)), now)

  private fun JSONObject.arr(key: String, index: Int, value: Any) = apply { getJSONArray(key).put(index, value) }

  @Test fun fullItemRoundTripsIncludingRemovalAltAndMigration() {
    val back = HouseholdListCodec.decodeState(stateJson(full), now)!!
    assertEquals(full, back.items.getValue(id))
  }

  @Test fun envelopeRejections() {
    assertNull(HouseholdListCodec.decodeState(null, now))
    assertNull(HouseholdListCodec.decodeState(JSONObject().put("v", 2).put("items", JSONArray()), now))
    assertNull(HouseholdListCodec.decodeState(JSONObject().put("v", 1), now))
    assertNull(HouseholdListCodec.decodeState(JSONObject().put("v", 1).put("items", JSONArray().put("x")), now))
    val tooMany = JSONArray().apply { repeat(HouseholdListOps.MAX_ITEMS * 2 + 1) { put(JSONObject()) } }
    assertNull(HouseholdListCodec.decodeState(JSONObject().put("v", 1).put("items", tooMany), now))
    assertNull("duplicate id", HouseholdListCodec.decodeState(JSONObject().put("v", 1).put("items", JSONArray().put(itemJson()).put(itemJson())), now))
    assertEquals(0, HouseholdListCodec.decodeState(JSONObject().put("v", 1).put("items", JSONArray()), now)!!.items.size)
  }

  @Test fun idAndTextRejections() {
    assertNull(decodeOne(itemJson().put("id", "milk")))
    assertNull(decodeOne(itemJson().put("id", 5)))
    assertNull("not pre-cleaned", decodeOne(itemJson().arr("t", 0, " Milk")))
    assertNull("control char", decodeOne(itemJson().arr("t", 0, "Mi\u0007lk")))
    assertNull("blank", decodeOne(itemJson().arr("t", 0, "")))
    assertNull("overlong", decodeOne(itemJson().arr("t", 0, "x".repeat(HouseholdListText.MAX_TEXT + 1))))
    assertNull("negative stamp", decodeOne(itemJson().arr("t", 1, -1)))
    assertNull("blank author", decodeOne(itemJson().arr("t", 2, "  ")))
    assertNull("negative base", decodeOne(itemJson().arr("t", 3, -1)))
    assertNull("missing t", decodeOne(itemJson().apply { remove("t") }))
  }

  @Test fun timestampsMayRunAtMostOneDayAhead() {
    val edge = now + HouseholdListCodec.MAX_FUTURE_MS
    assertNotNull(decodeOne(itemJson().arr("a", 0, edge)))
    assertNull(decodeOne(itemJson().arr("a", 0, edge + 1)))
    assertNull(decodeOne(itemJson().arr("d", 1, edge + 1)))
    assertNull(decodeOne(itemJson().arr("o", 1, edge + 1)))
  }

  @Test fun doneOrderAndAddedRejections() {
    assertNull(decodeOne(itemJson().arr("d", 0, "true")))
    assertNull(decodeOne(itemJson().arr("d", 2, "")))
    assertNull(decodeOne(itemJson().arr("o", 0, "NaN")))
    assertNull(decodeOne(itemJson().arr("o", 0, "Infinity")))
    assertNull(decodeOne(itemJson().arr("o", 0, "abc")))
    assertNull(decodeOne(itemJson().arr("o", 2, "\n")))
    assertNull(decodeOne(itemJson().arr("a", 1, "")))
    assertNull(decodeOne(itemJson().apply { remove("a") }))
    assertNull(decodeOne(itemJson().put("d", JSONArray().put(true))))
  }

  @Test fun removalAltAndMigrationRejections() {
    assertNull(decodeOne(itemJson().arr("r", 0, 1)))
    assertNull(decodeOne(itemJson().arr("r", 1, -5)))
    assertNull(decodeOne(itemJson().arr("r", 3, -5)))
    assertNull(decodeOne(itemJson().arr("x", 0, "  Oat milk")))
    assertNull(decodeOne(itemJson().arr("x", 1, "")))
    assertNull(decodeOne(itemJson().arr("x", 1, "n".repeat(HouseholdListText.MAX_NAME + 1))))
    assertNull(decodeOne(itemJson().arr("x", 2, now + HouseholdListCodec.MAX_FUTURE_MS + 1)))
    assertNull(decodeOne(itemJson().put("m", JSONArray((1..9).map { "s$it" }))))
    assertNull(decodeOne(itemJson().put("m", JSONArray(listOf("ok", " ")))))
    assertEquals(8, decodeOne(itemJson().put("m", JSONArray((1..8).map { "s$it" })))!!.items.getValue(id).migratedFrom.size)
  }

  @Test fun optionalPartsMayBeAbsent() {
    val bare = itemJson().apply { remove("r"); remove("x"); remove("m") }
    val item = decodeOne(bare)!!.items.getValue(id)
    assertNull(item.removal)
    assertNull(item.alt)
    assertTrue(item.migratedFrom.isEmpty())
    val encoded = JSONObject(HouseholdListCodec.encodeState(HouseholdListState(mapOf(id to item))).toString())
    val only = encoded.getJSONArray("items").getJSONObject(0)
    assertTrue(!only.has("r") && !only.has("x") && !only.has("m"))
  }

  @Test fun digestCoversRemovalAltAndMigration() {
    val base = HouseholdListState(mapOf(id to full))
    val d = base.digest
    assertEquals(12, d.length)
    assertNotEquals(d, HouseholdListState(mapOf(id to full.copy(removal = null))).digest)
    assertNotEquals(d, HouseholdListState(mapOf(id to full.copy(alt = null))).digest)
    assertNotEquals(d, HouseholdListState(mapOf(id to full.copy(migratedFrom = emptySet()))).digest)
    assertEquals(d, HouseholdListCodec.digest(HouseholdListCodec.decodeState(stateJson(full), now)!!))
  }

  @Test fun publicItemShowsDoneAndRenameAttributionOnlyWhenTheyApply() {
    val plain = full.copy(text = TextReg("Milk", Stamp(now - 60, "Kitchen"), 0L), done = Reg(false, Stamp(now - 60, "Kitchen")),
        removal = null, alt = null)
    val p = HouseholdListCodec.publicItem(plain)
    assertEquals(JSONObject.NULL, p.get("doneBy"))
    assertEquals(JSONObject.NULL, p.get("doneAt"))
    assertEquals(JSONObject.NULL, p.get("renamedBy"))
    assertEquals(JSONObject.NULL, p.get("note"))
    assertEquals(now - 30, p.getLong("updatedAt"))
    val f = HouseholdListCodec.publicItem(full)
    assertEquals("Den", f.getString("doneBy"))
    assertEquals(now - 40, f.getLong("doneAt"))
    assertEquals("Kitchen", f.getString("renamedBy"))
    assertEquals(now - 50, f.getLong("renamedAt"))
  }

  @Test fun publicItemsListsOpenThenDone() {
    val open = full.copy(id = HouseholdListText.idFor("bread"), text = TextReg("Bread", Stamp(now, "K"), 0L),
        done = Reg(false, Stamp(now, "K")), removal = null, alt = null)
    val arr = HouseholdListCodec.publicItems(HouseholdListState(listOf(full, open).associateBy { it.id }))
    assertEquals(listOf("Bread", "Milk"), (0 until arr.length()).map { arr.getJSONObject(it).getString("text") })
  }
}
