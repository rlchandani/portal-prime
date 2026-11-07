/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNotesTest {
  private val zone = ZoneId.of("America/Los_Angeles")
  private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()
  private val note = HomeNote("n1", "Rohit's Pixel", at(7, 18, 10), text = "Swim bag")

  @Test fun addingTheSameNoteTwiceKeepsOneNote() {
    val notes = HomeNotes().add(note).add(note)
    assertEquals(listOf(note), notes.active)
    assertEquals("shown", notes.stateOf("n1"))
  }

  @Test fun dismissThenUndoPutsTheNoteBack() {
    val dismissed = HomeNotes().add(note).dismiss("n1", at(7, 18, 20))
    assertTrue(dismissed.active.isEmpty())
    assertEquals("dismissed", dismissed.stateOf("n1"))
    assertEquals(listOf(note), dismissed.undo("n1").active)
    assertEquals("shown", dismissed.undo("n1").stateOf("n1"))
  }

  @Test fun aRepeatedPostDoesNotResurrectADismissedNote() {
    assertTrue(HomeNotes().add(note).dismiss("n1", at(7, 19)).add(note).active.isEmpty())
  }

  @Test fun notesClearAtFourAmAfterTheyArrived() {
    val notes = HomeNotes().add(note)
    assertEquals(listOf(note), notes.clearOvernight(at(8, 3, 59), zone).active)
    val cleared = notes.clearOvernight(at(8, 4, 0), zone)
    assertTrue(cleared.active.isEmpty())
    assertEquals("cleared", cleared.stateOf("n1"))
    assertEquals(at(8, 4), cleared.gone.single().at)
  }

  @Test fun aNoteLeftAtThreeAmClearsTheNextNightNotAnHourLater() {
    assertEquals(at(8, 4), HomeNoteTime.overnightAfter(at(8, 3), zone))
    assertEquals(at(9, 4), HomeNoteTime.overnightAfter(at(8, 4), zone))
  }

  @Test fun putBackRestoresAClearedNoteFromTools() {
    val back = HomeNotes().add(note).clearOvernight(at(8, 5), zone).undo("n1")
    assertEquals(listOf(note), back.active)
  }

  @Test fun retractedFromThePhoneIsRecordedAsSuch() {
    val notes = HomeNotes().add(note).retract("n1", at(7, 18, 11))
    assertEquals("retracted", notes.stateOf("n1"))
    assertNull(HomeNotes().stateOf("missing"))
  }

  @Test fun activeAndHistoryAreBounded() {
    var notes = HomeNotes()
    repeat(20) { notes = notes.add(note.copy(id = "n$it", at = note.at + it)).dismiss("n$it", note.at + it) }
    assertEquals(HomeNotes.MAX_GONE, notes.gone.size)
    repeat(10) { notes = notes.add(note.copy(id = "a$it", at = note.at + it)) }
    assertEquals(HomeNotes.MAX_ACTIVE, notes.active.size)
    assertEquals("a9", notes.newest?.id)
  }

  @Test fun bylineUsesThePairingNameAndLocalTime() {
    assertEquals("From Rohit's Pixel · 6:10 PM", HomeNoteTime.byline(note, zone))
  }

  @Test fun placementPrefersAFreeSlotThenTheStripAndHidesInCustomize() {
    assertEquals(HomeNotePlacement.SLOT, homeNotePlacement(hasNote = true, freeSlotOnFirstPage = true, customizing = false))
    assertEquals(HomeNotePlacement.STRIP, homeNotePlacement(hasNote = true, freeSlotOnFirstPage = false, customizing = false))
    assertEquals(HomeNotePlacement.HIDDEN, homeNotePlacement(hasNote = true, freeSlotOnFirstPage = true, customizing = true))
    assertEquals(HomeNotePlacement.HIDDEN, homeNotePlacement(hasNote = false, freeSlotOnFirstPage = true, customizing = false))
  }

  @Test fun notesRoundTripThroughJson() {
    val notes = HomeNotes().add(note).add(note.copy(id = "l1", text = null, url = "https://x.org", title = null))
        .dismiss("n1", at(7, 19)).withLink("l1", "canceled")
    assertEquals(notes, HomeNotes.fromJson(notes.toJson()))
    assertEquals(HomeNotes(), HomeNotes.fromJson("garbage"))
  }
}
