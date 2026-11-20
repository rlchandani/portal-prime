/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Untrusted suggestion replies: wrong types, duplicates, oversize entries. No network. */
class YtQueryCompletionsCoverageTest {
  @Test fun nonStringEntriesAndDuplicatesAreDropped() {
    val reply = """["q",["a", 1, null, {"x":1}, ["nested"], true, " a ", "b"]]"""
    assertEquals(listOf("a", "b"), YtQueryCompletions.parse(reply))
  }

  @Test fun entryLengthLimitIsInclusiveAfterTrimming() {
    val hundred = "x".repeat(100)
    assertEquals(listOf(hundred), YtQueryCompletions.parse("""["q",["  $hundred  ", "${"y".repeat(101)}"]]"""))
  }

  @Test fun wrongShapesYieldNothing() {
    for (reply in listOf("", "[]", """["q","not an array"]""", """["q",{"a":1}]""", """[1,[]]""", "[\"q\",[\"unterminated", "null"))
      assertEquals(reply, emptyList<String>(), YtQueryCompletions.parse(reply))
  }

  @Test fun urlEncodesUnicodeAndReservedCharacters() {
    val url = YtQueryCompletions.url("beyoncé #1 /?=")
    assertTrue(url, url.endsWith("&q=beyonc%C3%A9+%231+%2F%3F%3D"))
  }

  @Test fun blankQueriesNeverReachTheNetwork() {
    assertEquals(emptyList<String>(), YtQueryCompletions.fetch(""))
    assertEquals(emptyList<String>(), YtQueryCompletions.fetch("   \t "))
  }
}
