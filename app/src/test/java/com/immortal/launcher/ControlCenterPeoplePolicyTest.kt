/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The privacy surface of people on the map: who may be shown, sent and accepted. */
class ControlCenterPeoplePolicyTest {
  private val now = 9_000_000L
  private val on = WhosThereSettings(enabled = true)
  private val maya = WhosTherePerson("mayaaaaa", "Maya", WhosThereRole.ADULT, 0, 0L, onMap = true)
  private val leo = WhosTherePerson("leoooooo", "Leo", WhosThereRole.KID, 3, 0L)
  private val names = CcPeoplePolicy.Mode.NAMES

  private fun settings(mode: String) = ControlCenterConfig.Settings(mapPeople = mode)

  @Test fun theHouseholdDefaultIsAnonymousPulsesWithoutNames() {
    val d = ControlCenterConfig.Settings()
    assertEquals(CcPeoplePolicy.Mode.SOMEONE, CcPeoplePolicy.mode(d))
    assertTrue(CcPeoplePolicy.anonymous(d))
    assertFalse(CcPeoplePolicy.named(d))
    assertFalse(CcPeoplePolicy.anonymous(settings(ControlCenterConfig.MAP_OFF)))
    assertTrue(CcPeoplePolicy.named(settings(ControlCenterConfig.MAP_NAMES)))
    assertEquals(CcPeoplePolicy.Mode.SOMEONE, CcPeoplePolicy.mode(settings("bogus")))
  }

  @Test fun aPersonIsSharedOnlyWithEveryConsentInPlace() {
    assertTrue(CcPeoplePolicy.shareable(maya, on, names, now))
    assertFalse("map setting not Names", CcPeoplePolicy.shareable(maya, on, CcPeoplePolicy.Mode.SOMEONE, now))
    assertFalse("recognition off", CcPeoplePolicy.shareable(maya, on.copy(enabled = false), names, now))
    assertFalse("household paused", CcPeoplePolicy.shareable(maya, on.copy(pausedUntilMs = now + 1), names, now))
    assertTrue("household pause over", CcPeoplePolicy.shareable(maya, on.copy(pausedUntilMs = now), names, now))
    assertFalse("person paused", CcPeoplePolicy.shareable(maya.copy(pausedUntilMs = now + 1), on, names, now))
    assertFalse("never recognise me here", CcPeoplePolicy.shareable(maya.copy(neverHere = true), on, names, now))
    assertFalse("hasn't allowed the map", CcPeoplePolicy.shareable(maya.copy(onMap = false), on, names, now))
  }

  @Test fun kidsAreOffTheMapUntilAnAdultTurnsItOn() {
    assertFalse(leo.onMap)
    assertFalse(CcPeoplePolicy.shareable(leo, on, names, now))
    assertTrue(CcPeoplePolicy.shareable(leo.copy(onMap = true), on, names, now))
  }

  @Test fun anArrivingSightingIsKeptOnlyUnderTheSameRules() {
    assertTrue(CcPeoplePolicy.accept("Maya", 0L, emptyList(), names, now))
    assertFalse(CcPeoplePolicy.accept("Maya", 0L, emptyList(), CcPeoplePolicy.Mode.SOMEONE, now))
    assertFalse(CcPeoplePolicy.accept("Maya", 0L, emptyList(), CcPeoplePolicy.Mode.OFF, now))
    assertFalse("too old", CcPeoplePolicy.accept("Maya", CcOccupancyFusion.SIGHTING_MS, emptyList(), names, now))
    assertFalse("from the future", CcPeoplePolicy.accept("Maya", -1L, emptyList(), names, now))
    assertTrue("enrolled here and allowed", CcPeoplePolicy.accept("maya", 0L, listOf(maya), names, now))
    assertFalse("paused here", CcPeoplePolicy.accept("Maya", 0L, listOf(maya.copy(pausedUntilMs = now + 1)), names, now))
    assertFalse("opted out here", CcPeoplePolicy.accept("Maya", 0L, listOf(maya.copy(neverHere = true)), names, now))
    assertFalse("off the map here", CcPeoplePolicy.accept("Maya", 0L, listOf(maya.copy(onMap = false)), names, now))
    assertTrue("someone else's setting doesn't matter", CcPeoplePolicy.accept("Maya", 0L, listOf(leo), names, now))
  }

  @Test fun whosHomeAddsTheLastRoomOnlyWhenNamesAreAllowed() {
    val occ = CcOccupancy(lastSeen = mapOf("maya" to CcLastSeen("kitchen", "Kitchen", now - 3 * 60_000L)))
    assertEquals("Home · Kitchen · 3 min ago", CcPeoplePolicy.whoLine("Home · Phone", "Maya Patel", true, occ, true, now))
    assertEquals("Home · Phone", CcPeoplePolicy.whoLine("Home · Phone", "Maya", true, occ, false, now))
    assertEquals("Away", CcPeoplePolicy.whoLine("Away", "Maya", false, occ, true, now))
    assertEquals("Home", CcPeoplePolicy.whoLine("Home", "Arjun", true, occ, true, now))
    assertEquals("now", CcPeoplePolicy.ago(30_000L))
    assertEquals("now", CcPeoplePolicy.ago(-5L))
  }

  @Test fun securitySideCarriesTheRoom() {
    val person = CcEntity("person.maya", "home", mapOf("friendly_name" to "Maya"))
    val occ = CcOccupancy(lastSeen = mapOf("maya" to CcLastSeen("kitchen", "Kitchen", now)))
    val side = CcSecurity.side(listOf(person), emptyList(), { "" }, null, occ, named = true, now = now)
    assertEquals("Home · Kitchen · now", side.people.single().line)
    assertEquals("Home", CcSecurity.side(listOf(person), emptyList(), { "" }).people.single().line)
  }

  @Test fun recognisableExcludesPausedAndOptedOut() {
    assertTrue(maya.recognisableAt(now))
    assertFalse(maya.copy(neverHere = true).recognisableAt(now))
    assertFalse(maya.copy(pausedUntilMs = now + 1).recognisableAt(now))
  }

  @Test fun theViewNeverPicksSomeoneWhoOptedOutHere() {
    val out = maya.copy(neverHere = true)
    val outcome = GlanceOutcome(listOf(out.id), null, 1, 3)
    assertEquals(WhosThereView.Shared, WhosThereViewPolicy.update(WhosThereView.Shared, outcome, listOf(out), now))
  }

  @Test fun theNewConsentsRoundTripThroughTheEncryptedHousehold() {
    val h = WhosThereHousehold(listOf(maya.copy(neverHere = true), leo))
    val back = WhosThereCodec.decode(WhosThereCodec.encode(h))
    assertTrue(back.person(maya.id)!!.neverHere)
    assertTrue(back.person(maya.id)!!.onMap)
    assertFalse(back.person(leo.id)!!.onMap)
    assertFalse(back.person(leo.id)!!.neverHere)
  }
}

/** The recognising Portal's send / revoke decision (RoomSignals runs exactly this). */
class ControlCenterShareStepTest {
  private val now = 9_000_000L
  private val on = WhosThereSettings(enabled = true)
  private val names = CcPeoplePolicy.Mode.NAMES
  private val maya = WhosTherePerson("mayaaaaa", "Maya", WhosThereRole.ADULT, 0, 0L, onMap = true)
  private val sam = WhosTherePerson("samsamsa", "Sam", WhosThereRole.ADULT, 1, 0L, onMap = true)
  private val dev = WhosTherePerson("devdevde", "Dev", WhosThereRole.TEEN, 2, 0L)
  private val all = listOf(maya, sam, dev)
  private val resend = 60_000L

  private fun step(view: WhosThereView, people: List<WhosTherePerson> = all, rec: WhosThereSettings = on, mode: CcPeoplePolicy.Mode = names,
                   sent: Map<String, Long> = emptyMap(), sighted: Set<String> = emptySet()) =
      CcPeoplePolicy.step(view, people, rec, mode, sent, sighted, now, resend)

  @Test fun firstSightSharesAndSends() {
    val s = step(WhosThereView.Personal(maya.id, othersNear = false))
    assertEquals(listOf(maya), s.share)
    assertEquals(listOf(maya), s.send)
    assertTrue(s.forget.isEmpty() && s.drop.isEmpty())
  }

  @Test fun resendsOnlyWhenDue() {
    val view = WhosThereView.Personal(maya.id, othersNear = false)
    assertTrue(step(view, sent = mapOf(maya.id to now - resend + 1)).send.isEmpty())
    assertEquals(listOf(maya), step(view, sent = mapOf(maya.id to now - resend)).send)
  }

  @Test fun familyViewSharesOnlyThoseWhoAllowedIt() {
    val s = step(WhosThereView.Family(listOf(maya.id, dev.id, sam.id)))
    assertEquals(listOf(maya, sam), s.share)
  }

  @Test fun isThisYouIsNotARecognition() {
    val s = step(WhosThereView.Personal(maya.id, othersNear = false, confirm = true))
    assertTrue(s.share.isEmpty())
    assertTrue(s.send.isEmpty())
  }

  @Test fun pausingRevokesEverywhereAtOnce() {
    val paused = listOf(maya.copy(pausedUntilMs = now + 1), sam, dev)
    val s = step(WhosThereView.Shared, people = paused, sent = mapOf(maya.id to now - 5_000), sighted = setOf(maya.id))
    assertEquals(setOf(maya.id), s.forget)
    assertEquals(setOf(maya.id), s.drop)
  }

  @Test fun aSightingThatWasNeverSentIsStillDroppedHere() {
    val s = step(WhosThereView.Shared, people = listOf(maya.copy(onMap = false)), sighted = setOf(maya.id))
    assertTrue(s.forget.isEmpty())
    assertEquals(setOf(maya.id), s.drop)
  }

  @Test fun deletedOrRecognitionOffOrSettingChangedRevokes() {
    val sent = mapOf(maya.id to now, sam.id to now)
    assertEquals(setOf(maya.id, sam.id), step(WhosThereView.Shared, people = emptyList(), sent = sent).forget)
    assertEquals(setOf(maya.id, sam.id), step(WhosThereView.Shared, rec = on.copy(enabled = false), sent = sent).forget)
    assertEquals(setOf(maya.id, sam.id), step(WhosThereView.Shared, mode = CcPeoplePolicy.Mode.SOMEONE, sent = sent).forget)
  }

  @Test fun leavingTheViewKeepsTheSightingToAgeOut() {
    val s = step(WhosThereView.Shared, sent = mapOf(maya.id to now - 1_000), sighted = setOf(maya.id))
    assertTrue(s.share.isEmpty())
    assertTrue(s.forget.isEmpty())
    assertTrue(s.drop.isEmpty())
  }
}
