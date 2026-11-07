/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HouseholdListCodecTest {
  private val now = 1_800_000_000_000L
  private val fa = "Family Area"

  private fun sample(): HouseholdListState {
    var s = HouseholdListOps.add(HouseholdListState(), "Eggs", fa, now - 5_000, migratedFrom = setOf(fa)).state
    s = HouseholdListOps.add(s, "Bananas", "Maya’s phone", now - 4_000).state
    val b = HouseholdListText.idFor("Bananas")
    s = HouseholdListMerge.merge(HouseholdListOps.rename(s, b, "Bananas 6", fa, now - 3_000).state,
        HouseholdListOps.rename(s, b, "Plantains", "Rohit’s Pixel", now - 2_000).state)
    s = HouseholdListOps.remove(s, HouseholdListText.idFor("Eggs"), fa, now - 1_000).state
    return s
  }

  @Test fun replica_roundTripsExactly_withTheSameDigest() {
    val s = sample()
    val back = HouseholdListCodec.decodeState(JSONObject(HouseholdListCodec.encodeState(s).toString()), now)
    assertEquals(s, back)
    assertEquals(s.digest, back!!.digest)
    assertEquals(12, s.digest.length)
  }

  @Test fun digest_changesWithAnyRegister() {
    val s = sample()
    val moved = HouseholdListOps.move(s, HouseholdListText.idFor("Bananas"), 9.5, fa, now).state
    assertNotEquals(s.digest, moved.digest)
  }

  @Test fun decode_rejectsMalformedOrImplausibleReplicas() {
    val good = HouseholdListCodec.encodeState(sample())
    fun mutated(f: (JSONObject) -> Unit): JSONObject = JSONObject(good.toString()).also { f(it.getJSONArray("items").getJSONObject(0)) }
    assertNull(HouseholdListCodec.decodeState(null, now))
    assertNull(HouseholdListCodec.decodeState(JSONObject().put("v", 2).put("items", JSONArray()), now))
    assertNull(HouseholdListCodec.decodeState(mutated { it.put("id", "../../etc") }, now))
    assertNull(HouseholdListCodec.decodeState(mutated { it.getJSONArray("t").put(0, "x".repeat(61)) }, now))
    assertNull(HouseholdListCodec.decodeState(mutated { it.getJSONArray("t").put(0, " padded ") }, now))
    assertNull(HouseholdListCodec.decodeState(mutated { it.getJSONArray("d").put(0, "yes") }, now))
    assertNull(HouseholdListCodec.decodeState(mutated { it.getJSONArray("o").put(0, Double.NaN.toString()) }, now))
    assertNull(HouseholdListCodec.decodeState(mutated { it.getJSONArray("a").put(0, now + 2 * HouseholdListCodec.MAX_FUTURE_MS) }, now))
    assertNull(HouseholdListCodec.decodeState(mutated { it.getJSONArray("a").put(1, "") }, now))
    val dup = JSONObject(good.toString()).also { j -> j.getJSONArray("items").put(j.getJSONArray("items").getJSONObject(0)) }
    assertNull(HouseholdListCodec.decodeState(dup, now))
  }

  @Test fun publicView_showsLiveItemsWithWhoWhenAndNotes() {
    val items = HouseholdListCodec.publicItems(sample())
    assertEquals(1, items.length())
    val plantains = items.getJSONObject(0)
    assertEquals("Plantains", plantains.getString("text"))
    assertEquals("Maya’s phone", plantains.getString("addedBy"))
    assertEquals("Rohit’s Pixel", plantains.getString("renamedBy"))
    assertEquals("edited", plantains.getJSONObject("note").getString("kind"))
    assertEquals("Bananas 6", plantains.getJSONObject("note").getString("alt"))
    assertTrue(plantains.isNull("doneBy"))
  }
}
