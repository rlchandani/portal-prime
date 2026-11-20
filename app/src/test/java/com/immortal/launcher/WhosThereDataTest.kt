/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enrolment reducer, personal content filters, the template codec and the audit log. */
class WhosThereDataTest {

  private fun emb(i: Int, j: Int = i) = Embedding.of(FloatArray(128).also { it[i] = 1f; it[j] += 0.5f })!!

  private fun frame(at: Long, yaw: Float = 0f, pitch: Float = 0.5f, e: Embedding? = emb((at / 100).toInt() % 128),
                    faces: Int = 1, width: Float = 0.2f, luma: Float = 120f, sharp: Float = 80f, score: Float = 0.9f) =
      EnrolFrame(faces, score, width, luma, sharp, yaw, pitch, e, at)

  @Test fun qualityGate() {
    val p = WhosThereEnrolPolicy
    assertEquals(EnrolHint.NONE, p.quality(frame(0)))
    assertEquals(EnrolHint.NO_FACE, p.quality(frame(0, faces = 0)))
    assertEquals(EnrolHint.NO_FACE, p.quality(frame(0, e = null)))
    assertEquals(EnrolHint.TWO_FACES, p.quality(frame(0, faces = 2)))
    assertEquals(EnrolHint.NO_FACE, p.quality(frame(0, score = 0.5f)))
    assertEquals(EnrolHint.TOO_FAR, p.quality(frame(0, width = 0.05f)))
    assertEquals(EnrolHint.TOO_DARK, p.quality(frame(0, luma = 20f)))
    assertEquals(EnrolHint.TOO_BRIGHT, p.quality(frame(0, luma = 240f)))
    assertEquals(EnrolHint.BLURRY, p.quality(frame(0, sharp = 3f)))
  }

  @Test fun fourGuidedLooksCompleteTheCapture() {
    var s = WhosThereEnrolPolicy.start(0)
    assertEquals(EnrolLook.FRONT, s.currentLook)
    s = WhosThereEnrolPolicy.onFrame(s, frame(100))
    assertEquals(0.125f, WhosThereEnrolPolicy.progress(s), 1e-6f)
    s = WhosThereEnrolPolicy.onFrame(s, frame(200))
    assertEquals(EnrolLook.LEFT, s.currentLook)
    // Looking straight on doesn't count as "left".
    s = WhosThereEnrolPolicy.onFrame(s, frame(300))
    assertEquals(EnrolHint.TURN, s.hint)
    s = WhosThereEnrolPolicy.onFrame(s, frame(400, yaw = -0.3f))
    s = WhosThereEnrolPolicy.onFrame(s, frame(500, yaw = -0.3f))
    assertEquals(EnrolLook.RIGHT, s.currentLook)
    assertEquals(-1, s.leftYawSign)
    // Same direction again is not "right".
    s = WhosThereEnrolPolicy.onFrame(s, frame(600, yaw = -0.3f))
    assertEquals(EnrolHint.TURN, s.hint)
    s = WhosThereEnrolPolicy.onFrame(s, frame(700, yaw = 0.3f))
    s = WhosThereEnrolPolicy.onFrame(s, frame(800, yaw = 0.3f))
    assertEquals(EnrolLook.UP, s.currentLook)
    s = WhosThereEnrolPolicy.onFrame(s, frame(900, pitch = 0.5f))
    assertEquals(EnrolHint.TURN, s.hint)
    s = WhosThereEnrolPolicy.onFrame(s, frame(1000, pitch = 0.4f))
    s = WhosThereEnrolPolicy.onFrame(s, frame(1100, pitch = 0.4f))
    assertTrue(s.done)
    assertEquals(8, s.collected.size)
    assertEquals(0, s.relaxedLooks)
    assertEquals(1f, WhosThereEnrolPolicy.progress(s), 0f)
    assertEquals(s, WhosThereEnrolPolicy.onFrame(s, frame(1200)))
    assertNull(s.currentLook)
  }

  @Test fun aLookRelaxesAfterItsPatienceSoSetUpNeverStalls() {
    var s = WhosThereEnrolPolicy.start(0)
    s = WhosThereEnrolPolicy.onFrame(s, frame(100))
    s = WhosThereEnrolPolicy.onFrame(s, frame(200))
    s = WhosThereEnrolPolicy.onFrame(s, frame(300))
    assertEquals(EnrolLook.LEFT, s.currentLook)
    val late = 200 + WhosThereEnrolPolicy.LOOK_PATIENCE_MS
    s = WhosThereEnrolPolicy.onFrame(s, frame(late))
    s = WhosThereEnrolPolicy.onFrame(s, frame(late + 100))
    assertEquals(EnrolLook.RIGHT, s.currentLook)
    assertEquals(1, s.relaxedLooks)
    assertEquals(0, s.leftYawSign)
  }

  @Test fun badFramesAndDuplicatesDontCount() {
    var s = WhosThereEnrolPolicy.start(0)
    s = WhosThereEnrolPolicy.onFrame(s, frame(100, luma = 10f))
    assertEquals(EnrolHint.TOO_DARK, s.hint)
    assertTrue(s.collected.isEmpty())
    s = WhosThereEnrolPolicy.onFrame(s, frame(200, e = emb(3)))
    s = WhosThereEnrolPolicy.onFrame(s, frame(300, e = emb(3)))
    assertEquals(1, s.collected.size)
  }

  // ---- content ----

  private val maya = WhosTherePerson("mayaaaaa", "Maya", WhosThereRole.ADULT, 0, 0L)
  private val arjun = WhosTherePerson("arjunnnn", "Arjun", WhosThereRole.ADULT, 1, 0L)
  private val all = listOf(maya, arjun)
  private fun ev(title: String, start: Long = 10L, end: Long = 20L) = CalendarFeed.Event(start, end, title, false, null)

  @Test fun mentionsNeedAWholeName() {
    val c = WhosThereContent
    assertTrue(c.mentions("Maya: dentist", "Maya"))
    assertTrue(c.mentions("dentist #maya", "Maya"))
    assertTrue(c.mentions("[Maya] lunch", "maya"))
    assertTrue(c.mentions("call @Maya", "Maya"))
    assertFalse(c.mentions("Mayan ruins trip", "Maya"))
    assertFalse(c.mentions("Amaya's party", "Maya"))
    assertFalse(c.mentions("anything", " "))
  }

  @Test fun privateMarkers() {
    assertTrue(WhosThereContent.isPrivate("Private: dentist"))
    assertTrue(WhosThereContent.isPrivate("🔒 Lunch with Priya"))
    assertTrue(WhosThereContent.isPrivate("Gift shopping #private"))
    assertFalse(WhosThereContent.isPrivate("Privateer exhibition"))
    assertEquals("dentist", WhosThereContent.displayTitle("Private: Maya: dentist", "Maya"))
    assertEquals("Lunch", WhosThereContent.displayTitle("[Maya] Lunch #private", "Maya"))
    assertEquals("Stand-up", WhosThereContent.displayTitle("Stand-up #maya", "Maya"))
    assertEquals("Maya", WhosThereContent.displayTitle("Maya", "Maya"))
  }

  @Test fun sharedFeedKeepsMineAndFamilyButNotOthers() {
    val events = listOf(ev("Maya: stand-up"), ev("Dinner at Nani’s"), ev("Arjun: dentist"), ev("Swim pick-up #maya #private"))
    val out = WhosThereContent.fromShared(events, maya, all)
    assertEquals(listOf("stand-up", "Dinner at Nani’s", "Swim pick-up"), out.map { it.title })
    assertEquals(listOf(EventOwner.MINE, EventOwner.FAMILY, EventOwner.MINE), out.map { it.owner })
    assertEquals(listOf(false, false, true), out.map { it.private })
  }

  @Test fun ownFeedIsAllMine() {
    val out = WhosThereContent.fromOwnFeed(listOf(ev("Yoga"), ev("Private: GP")), maya)
    assertTrue(out.all { it.owner == EventOwner.MINE })
    assertEquals(listOf(false, true), out.map { it.private })
  }

  @Test fun familyViewAttributesEvents() {
    val out = WhosThereContent.forFamily(listOf(ev("Dinner"), ev("Arjun: dentist #private")), all)
    assertEquals(EventOwner.FAMILY, out[0].owner)
    assertEquals(EventOwner.MINE, out[1].owner)
    assertTrue(out[1].private)
  }

  @Test fun upcomingIsTodaysRemainingAllDayFirst() {
    val e = listOf(
        PersonalEvent(5, 8, "past", false, null, EventOwner.MINE, false),
        PersonalEvent(30, 40, "later", false, null, EventOwner.MINE, false),
        PersonalEvent(12, 20, "now", false, null, EventOwner.MINE, false),
        PersonalEvent(0, 100, "all day", true, null, EventOwner.FAMILY, false),
        PersonalEvent(200, 300, "tomorrow", false, null, EventOwner.MINE, false))
    assertEquals(listOf("all day", "now", "later"), WhosThereContent.upcoming(e, nowMs = 10, dayEndMs = 100).map { it.title })
    assertEquals(1, WhosThereContent.upcoming(e, 10, 100, limit = 1).size)
  }

  // ---- codec ----

  @Test fun householdRoundTripsAndDeleteWipesTemplates() {
    val h = WhosThereHousehold()
        .upsert(maya.copy(calendarUrl = "https://example.org/maya.ics"))
        .upsert(arjun)
        .withTemplates(FaceTemplates(maya.id, listOf(emb(1), emb(2))))
        .withTemplates(FaceTemplates(arjun.id, listOf(emb(3))))
    val back = WhosThereCodec.decode(WhosThereCodec.encode(h))
    assertEquals(h.people.toSet(), back.people.toSet())
    assertEquals(2, back.templatesOf(maya.id)!!.looks.size)
    assertTrue(back.templatesOf(maya.id)!!.looks[0].cosine(emb(1)) > 0.99999f)
    assertEquals(listOf(maya.id, arjun.id).toSet(), back.enrolled.map { it.id }.toSet())
    val gone = back.without(maya.id)
    assertNull(gone.person(maya.id))
    assertNull(gone.templatesOf(maya.id))
    assertEquals(1, gone.templates.size)
    val renamed = h.upsert(maya.copy(name = "Maya S"))
    assertEquals(2, renamed.people.size)
    assertEquals("Maya S", renamed.person(maya.id)!!.name)
    val capped = h.withTemplates(FaceTemplates(maya.id, List(20) { emb(it) }))
    assertEquals(WhosThereLimits.MAX_LOOKS, capped.templatesOf(maya.id)!!.looks.size)
  }

  @Test fun decodeDropsMalformedEntries() {
    val text = """{"v":1,"people":[{"id":"BAD ID","name":"x"},{"id":"okokokok","name":"  ","role":"KID"},
      {"id":"goodgood","name":"Leela","role":"KID","color":3,"calendar":"http://insecure"}],
      "templates":[{"person":"goodgood","looks":[[1,2],[${List(128) { "0" }.joinToString(",")}]]},
      {"person":"nobodyyy","looks":[]}]}"""
    val h = WhosThereCodec.decode(text)
    assertEquals(listOf("goodgood"), h.people.map { it.id })
    assertNull(h.people.single().calendarUrl)
    assertEquals(WhosThereRole.KID, h.people.single().role)
    assertTrue(h.templatesOf("goodgood")!!.looks.isEmpty()) // wrong size and all-zero vectors dropped
    assertTrue(h.enrolled.isEmpty())
    assertTrue(runCatching { WhosThereCodec.decode("""{"v":9}""") }.isFailure)
    assertFalse(WhosThereCodec.validId("short"))
  }

  // ---- audit ----

  @Test fun auditKeepsThirtyDaysOfCountsOnly() {
    val day = 24L * 60 * 60 * 1000
    val now = 100 * day
    var log = emptyList<AuditEvent>()
    log = WhosThereAudit.add(log, AuditEvent(now - 31 * day, AuditKind.GLANCE_PERSONAL, 1), now)
    assertTrue(log.isEmpty())
    log = WhosThereAudit.add(log, AuditEvent(now - 2 * day, AuditKind.GLANCE_PERSONAL, 1), now)
    log = WhosThereAudit.add(log, AuditEvent(now - 10, AuditKind.NOT_YOU), now)
    log = WhosThereAudit.add(log, AuditEvent(now - 5, AuditKind.GLANCE_SHARED, 2), now)
    log = WhosThereAudit.add(log, AuditEvent(now + day, AuditKind.GLANCE_SHARED), now) // clock skew: dropped
    assertEquals(3, log.size)
    val text = WhosThereAudit.encode(log)
    assertFalse(text.contains("maya", ignoreCase = true))
    assertEquals(log, WhosThereAudit.decode(text + "\njunk\n1,q,0\n2,p,x"))
    val days = WhosThereAudit.days(log, TimeZone.getTimeZone("UTC"))
    assertEquals(2, days.size)
    assertEquals(AuditDay(99, glances = 1, personal = 0, corrections = 1), days[0])
    assertEquals(1, days[1].personal)
    val capped = WhosThereAudit.prune(List(WhosThereAudit.CAP + 10) { AuditEvent(now - it, AuditKind.GLANCE_NOBODY) }, now)
    assertEquals(WhosThereAudit.CAP, capped.size)
  }

  @Test fun auditKinds() {
    val one = GlanceOutcome(listOf("m"), null, 1, 3)
    assertEquals(AuditKind.GLANCE_NOBODY, WhosThereAudit.kindFor(WhosThereView.Shared, GlanceOutcome.EMPTY))
    assertEquals(AuditKind.GLANCE_PERSONAL, WhosThereAudit.kindFor(WhosThereView.Personal("m", false), one))
    assertEquals(AuditKind.GLANCE_UNSURE, WhosThereAudit.kindFor(WhosThereView.Personal("m", false, true), one))
    assertEquals(AuditKind.GLANCE_FAMILY, WhosThereAudit.kindFor(WhosThereView.Family(listOf("a", "b")), one))
    assertEquals(AuditKind.GLANCE_UNSURE, WhosThereAudit.kindFor(WhosThereView.Shared, GlanceOutcome(emptyList(), "m", 2, 3)))
    assertEquals(AuditKind.GLANCE_SHARED, WhosThereAudit.kindFor(WhosThereView.Shared, GlanceOutcome(emptyList(), null, 1, 3)))
    assertEquals(AuditKind.NOT_YOU, AuditKind.of("x"))
    assertNull(AuditKind.of("?"))
  }
}
