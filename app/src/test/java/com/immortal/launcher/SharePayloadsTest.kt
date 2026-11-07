/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePayloadsTest {
  private fun parse(json: String) = SharePayloads.parse(JSONObject(json))

  @Test fun noteIsParsedWithACleanSenderName() {
    assertEquals(ShareRequest.Note("n1", "Rohit's Pixel", "Swim bag is by the door"),
        parse("""{"type":"note","id":"n1","from":"  Rohit's\u0007 Pixel ","text":" Swim bag is by the door "}"""))
  }

  @Test fun notesNeedAnIdASenderAndBoundedText() {
    assertNull(parse("""{"type":"note","id":"../x","from":"A","text":"hi"}"""))
    assertNull(parse("""{"type":"note","id":"n1","from":"   ","text":"hi"}"""))
    assertNull(parse("""{"type":"note","id":"n1","from":"A","text":"   "}"""))
    assertNull(parse("""{"type":"note","id":"n1","from":"A","text":"${"x".repeat(1001)}"}"""))
    assertNull(parse("""{"type":"note","id":"n1","from":"A","text":7}"""))
  }

  @Test fun linksMustBePlainHttpWithoutCredentials() {
    assertEquals(ShareRequest.Link("l1", "A", "https://x.org/cake", "Cake"),
        parse("""{"type":"link","id":"l1","from":"A","url":"https://x.org/cake","title":"Cake"}"""))
    assertEquals(ShareRequest.Link("l1", "A", "https://x.org/", null),
        parse("""{"type":"link","id":"l1","from":"A","url":"https://x.org/","title":null}"""))
    assertNull(parse("""{"type":"link","id":"l1","from":"A","url":"javascript:alert(1)"}"""))
    assertNull(parse("""{"type":"link","id":"l1","from":"A","url":"https://u:p@x.org/"}"""))
    assertNull(parse("""{"type":"link","id":"l1","from":"A","url":"intent://x#Intent;end"}"""))
    assertNull(parse("""{"type":"link","id":"l1","from":"A","url":"file:///sdcard/a"}"""))
  }

  @Test fun photoMarksMustPointAtAnImageInSharedStorage() {
    assertEquals(ShareRequest.Photo("p1", "A", "/sdcard/Pictures/Immortal/a.jpg", 10),
        parse("""{"type":"photo","id":"p1","from":"A","path":"/sdcard/Pictures/Immortal/a.jpg","bytes":10}"""))
    assertNull(parse("""{"type":"photo","id":"p1","from":"A","path":"/sdcard/../data/x.jpg","bytes":10}"""))
    assertNull(parse("""{"type":"photo","id":"p1","from":"A","path":"/data/data/x.jpg","bytes":10}"""))
    assertNull(parse("""{"type":"photo","id":"p1","from":"A","path":"/sdcard/a.txt","bytes":10}"""))
    assertNull(parse("""{"type":"photo","id":"p1","from":"A","path":"/sdcard/a.jpg","bytes":0}"""))
  }

  @Test fun retractNeedsOnlyAnId() {
    assertEquals(ShareRequest.Retract("n1"), parse("""{"type":"retract","id":"n1"}"""))
    assertNull(parse("""{"type":"bogus","id":"n1"}"""))
  }

  @Test fun quietHoursAreTheChimeWindowOrTheOvernightWindow() {
    val q = ShareQuiet.Window(on = true, startMin = 22 * 60, endMin = 8 * 60)
    val off = ShareQuiet.Window(on = false, startMin = 0, endMin = 0)
    assertTrue(ShareQuiet.isQuiet(23 * 60, q, off))
    assertTrue(ShareQuiet.isQuiet(7 * 60, q, off))
    assertFalse(ShareQuiet.isQuiet(12 * 60, q, off))
    assertTrue(ShareQuiet.isQuiet(12 * 60, off, ShareQuiet.Window(true, 11 * 60, 13 * 60)))
    assertFalse(ShareQuiet.isQuiet(23 * 60, ShareQuiet.Window(false, 22 * 60, 8 * 60), off))
  }
}
