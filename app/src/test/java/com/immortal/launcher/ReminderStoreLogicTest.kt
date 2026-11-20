/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.ReminderFixtures.NOW
import com.immortal.launcher.ReminderFixtures.clinic
import com.immortal.launcher.ReminderFixtures.maya
import com.immortal.launcher.ReminderFixtures.state
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codec, merge and pure edits of the reminders replica. */
class ReminderStoreLogicTest {
  private val key = ByteArray(32) { it.toByte() }

  // ---- codec ----

  @Test fun stateRoundTripsThroughJson() {
    val back = ReminderCodec.decodeState(JSONObject(ReminderCodec.encodeState(state).toString()))
    assertEquals(state, back)
  }

  @Test fun junkEntriesAreSkippedAndABadEnvelopeIsNull() {
    assertNull(ReminderCodec.decodeState(null))
    assertNull(ReminderCodec.decodeState(JSONObject().put("v", 2).put("r", JSONArray())))
    assertNull(ReminderCodec.decodeState(JSONObject().put("v", 1)))
    val good = ReminderCodec.reminderJson(clinic)
    val bad = listOf(
        JSONObject(good.toString()).put("i", "UPPER!"),
        JSONObject(good.toString()).put("t", "   "),
        JSONObject(good.toString()).put("q", 0),
        JSONObject(good.toString()).put("q", 1.5),
        JSONObject(good.toString()).put("b", "bad device"),
        JSONObject(good.toString()).put("d", 5L),
        JSONObject(good.toString()).put("pv", "yes"),
        JSONObject(good.toString()).put("p", "bad id!"),
        // The household never has a private reminder, even from a peer that says so.
        JSONObject(good.toString()).put("p", "").put("pv", true),
    )
    val decoded = ReminderCodec.decodeState(JSONObject().put("v", 1).put("r", JSONArray(bad + good)))!!
    assertEquals(listOf(clinic.id), decoded.reminders.keys.toList())
  }

  @Test fun longTitlesAndControlCharactersAreCleaned() {
    val o = ReminderCodec.reminderJson(clinic).put("t", "Call\nthe\tclinic " + "x".repeat(200))
    val r = ReminderCodec.decodeState(JSONObject().put("v", 1).put("r", JSONArray().put(o)))!!.reminders.values.single()
    assertTrue(r.title.startsWith("Call the clinic"))
    assertEquals(ReminderLimits.TITLE_MAX, r.title.codePointCount(0, r.title.length))
  }

  @Test fun digestIsKeyedAndChangesWithAnyEdit() {
    val d = ReminderCodec.digest(state, key)
    assertTrue(Regex("^[0-9a-f]{12}$").matches(d))
    assertEquals(d, ReminderCodec.digest(ReminderState(state.reminders.toList().reversed().toMap()), key))
    assertNotEquals(d, ReminderCodec.digest(state, ByteArray(32) { 7 }))
    val edited = ReminderState(state.reminders + (clinic.id to clinic.copy(done = true, seq = 2)))
    assertNotEquals(d, ReminderCodec.digest(edited, key))
  }

  // ---- merge ----

  @Test fun mergeIsCommutativeIdempotentAndHigherSeqWins() {
    val a = ReminderState(state.reminders + (clinic.id to clinic.copy(title = "Call clinic", seq = 3, by = "living")))
    val b = ReminderState(state.reminders + (clinic.id to clinic.copy(done = true, seq = 2)))
    val ab = ReminderMerge.merge(a, b)
    assertEquals(ab, ReminderMerge.merge(b, a))
    assertEquals(ab, ReminderMerge.merge(ab, b))
    assertEquals("Call clinic", ab.reminders[clinic.id]!!.title)
    assertSame(a, ReminderMerge.merge(a, a))
  }

  @Test fun aTombstoneBeatsAnEqualSeqEditAndUnionKeepsBothSides() {
    val dead = clinic.copy(deleted = true, seq = 2, by = "aaa")
    val live = clinic.copy(title = "x", seq = 2, by = "zzz")
    val merged = ReminderMerge.merge(ReminderState(mapOf(clinic.id to dead)), ReminderState(mapOf(clinic.id to live, "rnew00001" to clinic.copy(id = "rnew00001"))))
    assertTrue(merged.reminders[clinic.id]!!.deleted)
    assertTrue("rnew00001" in merged.reminders)
  }

  // ---- ops ----

  @Test fun addValidatesAndStampsTheReminder() {
    val draft = ReminderDraft("  Call mom ", maya.id, "Maya", NOW + 60_000, private = true)
    val ok = ReminderOps.add(ReminderState(), "rnew00001", draft, "Kitchen", "kitchen", NOW) as ReminderResult.Ok
    val r = ok.reminder
    assertEquals("Call mom", r.title)
    assertEquals(maya.id, r.person)
    assertTrue(r.private)
    assertEquals(1L, r.seq)
    assertEquals(NOW, r.createdMs)
    assertEquals("Kitchen", r.createdBy)
    assertEquals(ReminderResult.Refused("duplicate_reminder"), ReminderOps.add(ok.state, "rnew00001", draft, "K", "kitchen", NOW))
    assertEquals(ReminderResult.Refused("invalid_reminder"), ReminderOps.add(ReminderState(), "rnew00002", draft.copy(title = " "), "K", "k", NOW))
    assertEquals(ReminderResult.Refused("invalid_reminder"),
        ReminderOps.add(ReminderState(), "rnew00002", draft.copy(dueMs = NOW + 3L * 366 * 86_400_000), "K", "k", NOW))
  }

  @Test fun theHouseholdCanNeverHoldAPrivateReminder() {
    val ok = ReminderOps.add(ReminderState(), "rnew00001", ReminderDraft("Bins", null, "ignored", null, true), "K", "k", NOW) as ReminderResult.Ok
    assertFalse(ok.reminder.private)
    assertEquals("", ok.reminder.personName)
  }

  @Test fun tooManyLiveRemindersAreRefused() {
    val full = ReminderState((1..ReminderLimits.MAX_LIVE).associate { i -> "rfull$i" to clinic.copy(id = "rfull$i") })
    assertEquals(ReminderResult.Refused("too_many_reminders"),
        ReminderOps.add(full, "rnew00001", ReminderDraft("x", null, "", null, false), "K", "k", NOW))
  }

  @Test fun editDoneAndDeleteBumpSeqPastTheBase() {
    val edited = ReminderOps.edit(state, clinic.id, ReminderDraft("Clinic", maya.id, "Maya", null, false), "living", NOW, baseSeq = 9)
        as ReminderResult.Ok
    assertEquals(10L, edited.reminder.seq)
    assertEquals("living", edited.reminder.by)
    assertNull(edited.reminder.dueMs)
    val done = ReminderOps.setDone(edited.state, clinic.id, true, "kitchen", NOW) as ReminderResult.Ok
    assertTrue(done.reminder.done)
    assertEquals(NOW, done.reminder.doneMs)
    assertEquals(11L, done.reminder.seq)
    val again = ReminderOps.setDone(done.state, clinic.id, true, "kitchen", NOW) as ReminderResult.Ok
    assertSame(done.state, again.state)
    val undone = ReminderOps.setDone(done.state, clinic.id, false, "kitchen", NOW) as ReminderResult.Ok
    assertEquals(0L, undone.reminder.doneMs)
    val gone = ReminderOps.delete(undone.state, clinic.id, "kitchen", NOW) as ReminderResult.Ok
    assertTrue(gone.reminder.deleted)
    assertEquals(NOW, gone.reminder.doneMs)
    assertEquals(ReminderResult.Refused("unknown_reminder"), ReminderOps.delete(gone.state, clinic.id, "k", NOW))
    assertEquals(ReminderResult.Refused("unknown_reminder"), ReminderOps.setDone(gone.state, clinic.id, true, "k", NOW))
    assertEquals(ReminderResult.Refused("unknown_reminder"),
        ReminderOps.edit(gone.state, clinic.id, ReminderDraft("x", null, "", null, false), "k", NOW))
  }

  @Test fun anEditMayKeepAPastDueTimeButNotSetANewJunkOne() {
    val past = clinic.copy(dueMs = NOW - 86_400_000)
    val s = ReminderState(mapOf(past.id to past))
    assertTrue(ReminderOps.edit(s, past.id, ReminderDraft("Clinic", maya.id, "Maya", past.dueMs, true), "k", NOW) is ReminderResult.Ok)
    assertEquals(ReminderResult.Refused("invalid_reminder"),
        ReminderOps.edit(s, past.id, ReminderDraft("Clinic", maya.id, "Maya", 12L, true), "k", NOW))
  }

  @Test fun pruneTombstonesOldDoneRemindersAndForgetsOldTombstones() {
    val oldDone = clinic.copy(done = true, doneMs = NOW - ReminderLimits.DONE_KEEP_MS - 1)
    val freshDone = ReminderFixtures.milk.copy(done = true, doneMs = NOW - 1_000)
    val ancientTomb = ReminderFixtures.gift.copy(deleted = true, doneMs = NOW - ReminderLimits.TOMBSTONE_KEEP_MS - 1,
        createdMs = NOW - ReminderLimits.TOMBSTONE_KEEP_MS - 10)
    val s = ReminderState(listOf(oldDone, freshDone, ancientTomb).associateBy { it.id })
    val pruned = ReminderOps.prune(s, NOW)
    assertTrue(pruned.reminders[oldDone.id]!!.deleted)
    assertEquals(oldDone.seq + 1, pruned.reminders[oldDone.id]!!.seq)
    assertFalse(pruned.reminders[freshDone.id]!!.deleted)
    assertFalse(ancientTomb.id in pruned.reminders)
    assertSame(pruned, ReminderOps.prune(pruned, NOW))
  }

  @Test fun tombstonesAreCappedNewestFirst() {
    val many = (1..ReminderLimits.MAX_TOMBSTONES + 5).associate { i ->
      "rdead$i" to clinic.copy(id = "rdead$i", deleted = true, seq = i.toLong(), doneMs = NOW)
    }
    val pruned = ReminderOps.prune(ReminderState(many), NOW)
    assertEquals(ReminderLimits.MAX_TOMBSTONES, pruned.reminders.size)
    assertFalse("rdead1" in pruned.reminders)
  }
}
