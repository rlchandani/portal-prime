/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pure roster sanitiser behind the server-synced backup (no Context / prefs needed). */
class RemoteRosterTest {

  private fun entry(name: String?, base: String?, token: String?): String {
    val sb = StringBuilder("{")
    val parts = mutableListOf<String>()
    if (name != null) parts += "\"name\":\"$name\""
    if (base != null) parts += "\"base\":\"$base\""
    if (token != null) parts += "\"token\":\"$token\""
    sb.append(parts.joinToString(",")).append("}")
    return sb.toString()
  }

  @Test
  fun keepsWellFormedEntries() {
    val raw = "[${entry("Kitchen", "http://10.0.0.5:8723", "tok1")}]"
    val out = RemoteRoster.sanitize(raw)
    assertEquals(1, out.length())
    val o = out.getJSONObject(0)
    assertEquals("Kitchen", o.getString("name"))
    assertEquals("http://10.0.0.5:8723", o.getString("base"))
    assertEquals("tok1", o.getString("token"))
  }

  @Test
  fun dropsEntriesMissingBaseOrToken() {
    val raw =
        "[" +
            entry("A", "http://a:1", "tok") + "," +
            entry("B", null, "tok") + "," + // no base
            entry("C", "http://c:1", null) + // no token
            "]"
    val out = RemoteRoster.sanitize(raw)
    assertEquals(1, out.length())
    assertEquals("http://a:1", out.getJSONObject(0).getString("base"))
  }

  @Test
  fun dedupesByBaseFirstWins() {
    val raw =
        "[" + entry("First", "http://x:1", "tokA") + "," + entry("Second", "http://x:1", "tokB") + "]"
    val out = RemoteRoster.sanitize(raw)
    assertEquals(1, out.length())
    assertEquals("tokA", out.getJSONObject(0).getString("token"))
  }

  @Test
  fun defaultsBlankName() {
    val raw = "[${entry("", "http://x:1", "tok")}]"
    assertEquals("Portal", RemoteRoster.sanitize(raw).getJSONObject(0).getString("name"))
  }

  @Test
  fun garbageAndNonArrayBecomeEmpty() {
    assertEquals(0, RemoteRoster.sanitize(null).length())
    assertEquals(0, RemoteRoster.sanitize("").length())
    assertEquals(0, RemoteRoster.sanitize("not json").length())
    assertEquals(0, RemoteRoster.sanitize("{\"base\":\"x\"}").length()) // object, not array
  }

  @Test
  fun capsEntryCount() {
    val many = JSONArray()
    for (i in 0 until RemoteRoster.MAX_ENTRIES + 25) {
      many.put(org.json.JSONObject().put("name", "P$i").put("base", "http://h$i:1").put("token", "t$i"))
    }
    assertEquals(RemoteRoster.MAX_ENTRIES, RemoteRoster.sanitize(many.toString()).length())
  }
}
