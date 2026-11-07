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
import org.junit.Test

class FreshPhotosTest {
  private val zone = ZoneId.of("America/Los_Angeles")
  private fun at(day: Int, h: Int, m: Int = 0, s: Int = 0) =
      LocalDateTime.of(2026, 11, day, h, m, s).atZone(zone).toInstant().toEpochMilli()
  private val a = FreshPhoto("p1", "/sdcard/Pictures/Immortal/a.jpg", "Rohit's Pixel", at(7, 16, 52))
  private val b = a.copy(id = "p2", path = "/sdcard/Pictures/Immortal/b.jpg")

  @Test fun eachNewPhotoGetsOneTurnInArrivalOrder() {
    var q = FreshPhotoQueue().add(a).add(b)
    val (first, q1) = q.takeTurn(at(7, 17), quiet = false); q = q1
    assertEquals(a, first)
    val (second, q2) = q.takeTurn(at(7, 17, 0, 30), quiet = false); q = q2
    assertEquals(b, second)
    val (none, q3) = q.takeTurn(at(7, 17, 1), quiet = false)
    assertNull(none); assertNull(q3.showing)
  }

  @Test fun theSamePathIsQueuedOnce() {
    assertEquals(1, FreshPhotoQueue().add(a).add(a.copy(id = "again")).pending.size)
  }

  @Test fun duringQuietHoursNewPhotosWait() {
    val q = FreshPhotoQueue().add(a)
    val (shown, after) = q.takeTurn(at(7, 23), quiet = true)
    assertNull(shown)
    assertEquals(listOf(a), after.pending)
  }

  @Test fun homeShowsTheSlideshowsCurrentFreshPhotoForTheRestOfItsTurn() {
    val (_, q) = FreshPhotoQueue().add(a).add(b).takeTurn(at(7, 17), quiet = false)
    assertEquals(a, q.forHome(at(7, 17, 0, 10), quiet = false).first)
    assertEquals(b, q.forHome(at(7, 17, 0, 31), quiet = false).first)
  }

  @Test fun captionSaysWhoAndWhenLikeThePrototype() {
    assertEquals("New from Rohit's Pixel · just now", FreshPhotoCaption.label("Rohit's Pixel", at(7, 16, 52), at(7, 16, 53), zone))
    assertEquals("New from Rohit's Pixel · 5 min ago", FreshPhotoCaption.label("Rohit's Pixel", at(7, 16, 52), at(7, 16, 57), zone))
    assertEquals("New from Rohit's Pixel · at 4:52 PM", FreshPhotoCaption.label("Rohit's Pixel", at(7, 16, 52), at(7, 19), zone))
    assertEquals("New from Rohit's Pixel · last night 9:41 PM", FreshPhotoCaption.label("Rohit's Pixel", at(7, 21, 41), at(8, 7, 14), zone))
    assertEquals("New from Rohit's Pixel · yesterday 4:52 PM", FreshPhotoCaption.label("Rohit's Pixel", at(7, 16, 52), at(8, 9), zone))
    assertEquals("New from Rohit's Pixel · on Nov 3", FreshPhotoCaption.label("Rohit's Pixel", at(3, 16, 52), at(8, 9), zone))
  }

  @Test fun aFreshPhotoIsSplicedRightAfterTheCurrentSlide() {
    val deck = listOf("/x/1.jpg", "/x/2.jpg", "/x/3.jpg")
    assertEquals(2 to true, FreshPhotoSplice.place(deck, current = 1, fresh = "/x/new.jpg"))
    assertEquals(0 to false, FreshPhotoSplice.place(deck, current = 1, fresh = "/x/1.jpg"))
    assertEquals(0 to true, FreshPhotoSplice.place(emptyList(), current = -1, fresh = "/x/new.jpg"))
  }

  @Test fun queueRoundTripsThroughJson() {
    val (_, q) = FreshPhotoQueue().add(a).add(b).takeTurn(at(7, 17), quiet = false)
    assertEquals(q, FreshPhotoQueue.fromJson(q.toJson()))
    assertEquals(FreshPhotoQueue(), FreshPhotoQueue.fromJson("{"))
  }
}
