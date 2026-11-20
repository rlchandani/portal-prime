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
import java.util.TimeZone

/** Call everyone: message model edges, delivery decisions, sender book, peers directory and phone requests. */
class DinnerCoverageTest {
  private val room = "11111111-1111-1111-1111-111111111111"
  private val rooms = JSONArray().put(room)
  private val dinner = DinnerMessage.preset("dinner")!!

  private fun send(id: String = "s1", rows: List<DinnerRow> = listOf(DinnerRow("la", "Living Area", DinnerStatus.SENDING))) =
      DinnerSend(id, dinner, "Family Area", false, 100L, rows)

  // --- model ---

  @Test fun messageTextAndIconForEveryKind() {
    assertEquals("Ping", DinnerMessage.ping().text)
    assertEquals("bell", DinnerMessage.ping().icon)
    assertEquals("keyboard", DinnerMessage.custom("Pizza")!!.icon)
    assertEquals("plate", dinner.icon)
    // A preset id the decoder accepted but this build doesn't know shows a generic megaphone and no text.
    val unknown = DinnerMessage(DinnerKind.PRESET, presetId = "future")
    assertEquals("megaphone", unknown.icon)
    assertEquals("", unknown.text)
    assertEquals("", DinnerMessage(DinnerKind.CUSTOM).text)
  }

  @Test fun kindWireRoundTripsAndPresetLookupRejectsNull() {
    for (k in DinnerKind.values()) assertEquals(k, DinnerKind.fromWire(k.wire))
    assertNull(DinnerKind.fromWire("x"))
    assertNull(DinnerPresets.byId(null))
    assertNull(DinnerMessage.preset(null))
  }

  @Test fun cleanTextDropsControlAndBidiCharactersAndCollapsesSpaces() {
    assertNull(DinnerMessage.cleanText(null, 40))
    assertEquals("a b c", DinnerMessage.cleanText("a\r\n\tb  c", 40))
    assertEquals("ab", DinnerMessage.cleanText("a‮b\u0000", 40))
    assertNull(DinnerMessage.cleanText("​\u0007", 40))
    assertEquals("😀😀", DinnerMessage.cleanText("😀😀", 2))
    assertNull(DinnerMessage.cleanText("😀😀😀", 2))
  }

  @Test fun statusAndReplyWire() {
    assertEquals(DinnerStatus.SPOKEN, DinnerStatus.fromWire("spoken"))
    assertNull("a peer can't report a sender-only status", DinnerStatus.fromWire("noanswer"))
    assertNull(DinnerStatus.fromWire("sending"))
    assertNull(DinnerStatus.fromWire(null))
    assertEquals(DinnerReply.ON_MY_WAY, DinnerReply.fromWire("onmyway"))
    assertNull(DinnerReply.fromWire("later"))
  }

  @Test fun decisionPriorities() {
    fun d(kind: DinnerKind, receive: Boolean = true, speak: Boolean = true, quiet: Boolean = false, tts: Boolean = true) =
        DinnerDecision.decide(kind, DinnerReceivePolicy(receive, speak, quiet, tts))
    assertEquals(DinnerStatus.BLOCKED, d(DinnerKind.PING, receive = false, quiet = true).status)
    assertFalse(d(DinnerKind.PING, receive = false).showCard)
    assertEquals(DinnerStatus.QUIET, d(DinnerKind.PING, quiet = true).status)
    assertEquals(DinnerDelivery(DinnerStatus.CHIMED, true, true, false), d(DinnerKind.PING))
    assertEquals(DinnerDelivery(DinnerStatus.SPOKEN, true, true, true), d(DinnerKind.CUSTOM))
    assertEquals(DinnerStatus.CHIMED_ONLY, d(DinnerKind.PRESET, tts = false).status)
    assertEquals(DinnerStatus.CHIMED_ONLY, d(DinnerKind.PRESET, speak = false).status)
  }

  @Test fun quietHoursOffOrEmptyWindowNeverQuiet() {
    val utc = TimeZone.getTimeZone("UTC")
    val at2300 = 23 * 60 * 60_000L
    assertTrue(DinnerQuiet.isQuiet(ChimeConfig.Settings(), at2300, utc))
    assertFalse(DinnerQuiet.isQuiet(ChimeConfig.Settings(quietHoursOn = false), at2300, utc))
    assertFalse(DinnerQuiet.isQuiet(ChimeConfig.Settings(quietStartMin = 600, quietEndMin = 600), at2300, utc))
    // Same instant is 08:00 in Tokyo: the window has just ended.
    assertFalse(DinnerQuiet.isQuiet(ChimeConfig.Settings(), 23 * 60 * 60_000L, TimeZone.getTimeZone("Asia/Tokyo")))
    assertEquals(8 * 60, DinnerQuiet.minuteOfDay(at2300, TimeZone.getTimeZone("Asia/Tokyo")))
  }

  // --- book ---

  @Test fun sendFlagsAndJson() {
    assertFalse("no rows is not 'all failed'", send(rows = emptyList()).allFailed)
    val failed = send(rows = listOf(DinnerRow("la", "LA", DinnerStatus.BLOCKED), DinnerRow("den", "Den", DinnerStatus.NO_ANSWER)))
    assertTrue(failed.allFailed)
    assertFalse(failed.pending)
    val j = send(rows = listOf(DinnerRow("la", "LA", DinnerStatus.SPOKEN, DinnerReply.COMING), DinnerRow("den", "Den", DinnerStatus.NO_ANSWER))).toJson()
    assertEquals("preset", j.getString("kind"))
    assertEquals("Dinner’s ready", j.getString("text"))
    val rows = j.getJSONArray("rows")
    assertEquals("Coming", rows.getJSONObject(0).getString("reply"))
    assertTrue(rows.getJSONObject(1).isNull("reply"))
    assertTrue(rows.getJSONObject(1).getBoolean("retry"))
    assertEquals("noanswer", rows.getJSONObject(1).getString("status"))
  }

  @Test fun startReplacesTheSameIdAndKeepsTheNewestEight() {
    var book = DinnerBook()
    for (i in 1..DinnerBook.CAPACITY + 2) book = book.start(send(id = "s$i"))
    assertEquals(DinnerBook.CAPACITY, book.sends.size)
    assertEquals("s3", book.sends.first().id)
    val again = book.start(send(id = "s3", rows = emptyList()))
    assertEquals("s3", again.sends.last().id)
    assertEquals(DinnerBook.CAPACITY, again.sends.size)
  }

  @Test fun replyOnlyForKnownSendsAndRooms() {
    val book = DinnerBook().start(send())
    assertSame(book, book.reply("nope", "la", DinnerReply.COMING))
    assertSame(book, book.reply("s1", "den", DinnerReply.COMING))
    assertEquals(DinnerReply.ON_MY_WAY, book.reply("s1", "la", DinnerReply.ON_MY_WAY).find("s1")!!.rows.single().reply)
  }

  @Test fun retryingClearsTheReplyAndSetStatusTouchesOnlyNamedRooms() {
    val rows = listOf(DinnerRow("la", "LA", DinnerStatus.NO_ANSWER, DinnerReply.COMING), DinnerRow("den", "Den", DinnerStatus.SPOKEN))
    val book = DinnerBook().start(send(rows = rows))
    val retried = book.retrying("s1", "la").find("s1")!!.rows
    assertEquals(DinnerRow("la", "LA", DinnerStatus.SENDING, null), retried[0])
    assertEquals(rows[1], retried[1])
    val set = book.setStatus("s1", setOf("den"), DinnerStatus.NOT_SET_UP).find("s1")!!.rows
    assertEquals(DinnerStatus.NO_ANSWER, set[0].status)
    assertEquals(DinnerStatus.NOT_SET_UP, set[1].status)
    assertSame(book, book.timeout("missing"))
    assertSame(book, book.retrying("missing", "la"))
  }

  @Test fun ackRenamesAKnownRoom() {
    val book = DinnerBook().start(send()).ack("s1", "la", "Lounge", DinnerStatus.CHIMED)
    assertEquals(DinnerRow("la", "Lounge", DinnerStatus.CHIMED), book.find("s1")!!.rows.single())
  }

  // --- peers ---

  @Test fun mergeKeepsKnownFlagsAndCapsTheDirectory() {
    val a = DinnerPeer("a", "Kitchen", 1, quiet = true, receive = false)
    val merged = DinnerPeers.merge(listOf(a), DinnerPeer("a", "Kitchen 2", 5, quiet = null, receive = null))
    assertEquals(listOf(DinnerPeer("a", "Kitchen 2", 5, true, false)), merged)
    val overridden = DinnerPeers.merge(listOf(a), DinnerPeer("a", "Kitchen", 6, quiet = false, receive = true))
    assertEquals(DinnerPeer("a", "Kitchen", 6, false, true), overridden.single())
    var list = emptyList<DinnerPeer>()
    for (i in 1..DinnerPeers.CAPACITY + 3) list = DinnerPeers.merge(list, DinnerPeer("p$i", "Room $i", i.toLong(), null, null))
    assertEquals(DinnerPeers.CAPACITY, list.size)
    assertEquals("p${DinnerPeers.CAPACITY + 3}", list.first().id)
  }

  @Test fun peersCodecValidatesAndKeepsUnknownFlagsNull() {
    val list = listOf(DinnerPeer("a", "Kitchen", 10, null, true), DinnerPeer("b", "Den", 20, false, null))
    assertEquals(list, DinnerPeers.decode(DinnerPeers.encode(list)))
    val raw = JSONArray().put("x").put(JSONObject().put("id", "bad id").put("name", "N"))
        .put(JSONObject().put("id", "c").put("name", " ")).put(JSONObject().put("id", "d").put("name", "Dup").put("quiet", "yes"))
        .put(JSONObject().put("id", "d").put("name", "Dup again"))
    val decoded = DinnerPeers.decode(raw.toString())
    assertEquals(listOf(DinnerPeer("d", "Dup", 0, null, null)), decoded)
    assertEquals(emptyList<DinnerPeer>(), DinnerPeers.decode("{"))
    assertEquals(emptyList<DinnerPeer>(), DinnerPeers.decode(null))
    val many = JSONArray().apply { repeat(DinnerPeers.CAPACITY + 2) { put(JSONObject().put("id", "r$it").put("name", "R")) } }
    assertEquals(DinnerPeers.CAPACITY, DinnerPeers.decode(many.toString()).size)
  }

  // --- phone requests ---

  @Test fun retryBodiesMustBeExact() {
    fun err(body: JSONObject) = (DinnerRequests.parse(body) as DinnerRequests.Invalid).code
    assertEquals("unknown_field", err(JSONObject().put("retry", JSONObject()).put("kind", "ping")))
    assertEquals("invalid_retry", err(JSONObject().put("retry", "a1b2c3d4")))
    assertEquals("invalid_retry", err(JSONObject().put("retry", JSONObject().put("id", "XYZ").put("room", room))))
    assertEquals("invalid_retry", err(JSONObject().put("retry", JSONObject().put("id", "a1b2c3d4").put("room", "bad id"))))
    assertEquals("invalid_retry", err(JSONObject().put("retry", JSONObject().put("id", "a1b2c3d4"))))
  }

  @Test fun sendBodiesMissingKindOrTextAreRefused() {
    fun err(body: JSONObject) = (DinnerRequests.parse(body) as DinnerRequests.Invalid).code
    assertEquals("invalid_kind", err(JSONObject().put("rooms", rooms)))
    assertEquals("invalid_text", err(JSONObject().put("kind", "custom").put("rooms", rooms)))
    assertEquals("invalid_text", err(JSONObject().put("kind", "custom").put("text", 5).put("rooms", rooms)))
    assertEquals("invalid_preset", err(JSONObject().put("kind", "preset").put("rooms", rooms)))
  }

  @Test fun aBlankSenderFallsBackAndDuplicateRoomsCollapse() {
    val s = DinnerRequests.parse(JSONObject().put("kind", "ping").put("rooms", JSONArray().put(room).put(room)).put("sender", "  ")) as DinnerRequests.Send
    assertEquals(DinnerRequests.DEFAULT_SENDER, s.senderName)
    assertEquals(listOf(room), s.rooms)
    val long = DinnerRequests.parse(JSONObject().put("kind", "ping").put("rooms", rooms).put("sender", "x".repeat(60))) as DinnerRequests.Send
    assertEquals(DinnerWire.MAX_NAME, long.senderName.length)
    assertEquals("invalid_rooms", (DinnerRequests.parse(JSONObject().put("kind", "ping").put("rooms", JSONArray().put(7))) as DinnerRequests.Invalid).code)
  }
}
