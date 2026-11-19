/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When to glance, which Home to show, what stays private, and the indicator. */
class WhosTherePolicyTest {

  private val now = 1_000_000_000L
  private val go = GlanceInputs(
      trigger = GlanceTrigger.ARRIVAL, nowMs = now, enabled = true, enrolled = 2, modelsReady = true,
      cameraGranted = true, pausedUntilMs = 0L, quietHours = false, debugIgnoreQuiet = false, inCall = false,
      homeInFront = true, screenAwake = true, cameraBusy = false, lastGlanceEndMs = 0L, lastRecognisedMs = 0L)

  private fun skip(i: GlanceInputs) = (WhosThereGlancePolicy.decide(i) as? GlanceDecision.Skip)?.reason

  @Test fun allClearGlances() = assertEquals(GlanceDecision.Go, WhosThereGlancePolicy.decide(go))

  @Test fun everyPrivacyRuleBlocks() {
    assertEquals(GlanceSkip.OFF, skip(go.copy(enabled = false)))
    assertEquals(GlanceSkip.NOBODY_ENROLLED, skip(go.copy(enrolled = 0)))
    assertEquals(GlanceSkip.MODELS_UNAVAILABLE, skip(go.copy(modelsReady = false)))
    assertEquals(GlanceSkip.NO_PERMISSION, skip(go.copy(cameraGranted = false)))
    assertEquals(GlanceSkip.PAUSED, skip(go.copy(pausedUntilMs = now + 1)))
    assertEquals(GlanceSkip.QUIET_HOURS, skip(go.copy(quietHours = true)))
    assertEquals(GlanceSkip.IN_CALL, skip(go.copy(inCall = true)))
    assertEquals(GlanceSkip.NOT_FOREGROUND, skip(go.copy(homeInFront = false)))
    assertEquals(GlanceSkip.SCREEN_OFF, skip(go.copy(screenAwake = false)))
    assertEquals(GlanceSkip.BUSY, skip(go.copy(cameraBusy = true)))
  }

  @Test fun offWinsOverEverything() {
    assertEquals(GlanceSkip.OFF, skip(go.copy(enabled = false, inCall = true, quietHours = true, homeInFront = false)))
  }

  @Test fun quietHoursHaveAnOperatorOverrideButCallsDoNot() {
    assertNull(skip(go.copy(quietHours = true, debugIgnoreQuiet = true)))
    assertEquals(GlanceSkip.IN_CALL, skip(go.copy(quietHours = true, debugIgnoreQuiet = true, inCall = true)))
  }

  @Test fun pauseExpires() = assertNull(skip(go.copy(pausedUntilMs = now)))

  @Test fun cooldownBetweenGlances() {
    assertEquals(GlanceSkip.COOLDOWN, skip(go.copy(lastGlanceEndMs = now - 5_000)))
    assertNull(skip(go.copy(lastGlanceEndMs = now - WhosThereGlancePolicy.COOLDOWN_MS)))
    // A tap may look again sooner, but not in a tight loop.
    assertNull(skip(go.copy(trigger = GlanceTrigger.MANUAL, lastGlanceEndMs = now - 5_000)))
    assertEquals(GlanceSkip.COOLDOWN, skip(go.copy(trigger = GlanceTrigger.MANUAL, lastGlanceEndMs = now - 1_000)))
  }

  @Test fun notAgainRightAfterARecognition() {
    assertEquals(GlanceSkip.RECENTLY_RECOGNISED, skip(go.copy(lastRecognisedMs = now - 10_000)))
    assertEquals(GlanceSkip.RECENTLY_RECOGNISED, skip(go.copy(trigger = GlanceTrigger.WAKE, lastRecognisedMs = now - 10_000)))
    assertNull(skip(go.copy(trigger = GlanceTrigger.MANUAL, lastRecognisedMs = now - 10_000)))
    assertNull(skip(go.copy(lastRecognisedMs = now - WhosThereGlancePolicy.RECOGNISED_HOLD_MS)))
  }

  private fun ps(p: Presence, s: ScreenState, since: Long = 0L, confident: Boolean = true) = PresenceState(p, s, confident, since)

  @Test fun arrivalsTrigger() {
    val t = WhosThereGlancePolicy.trigger(ps(Presence.ABSENT, ScreenState.INTERACTIVE), ps(Presence.PRESENT, ScreenState.INTERACTIVE), now)
    assertEquals(GlanceTrigger.ARRIVAL, t)
  }

  @Test fun waking_into_home_with_someone_here_triggers() {
    assertEquals(GlanceTrigger.WAKE, WhosThereGlancePolicy.trigger(
        ps(Presence.PRESENT, ScreenState.DREAMING), ps(Presence.PRESENT, ScreenState.INTERACTIVE), now))
    assertEquals(GlanceTrigger.WAKE, WhosThereGlancePolicy.trigger(
        ps(Presence.PRESENT, ScreenState.OFF), ps(Presence.PRESENT, ScreenState.INTERACTIVE), now))
  }

  @Test fun nonArrivalsDoNot() {
    // Screen not awake: the camera can't open anyway.
    assertNull(WhosThereGlancePolicy.trigger(ps(Presence.ABSENT, ScreenState.DREAMING), ps(Presence.PRESENT, ScreenState.DREAMING), now))
    // Still present, still awake.
    assertNull(WhosThereGlancePolicy.trigger(ps(Presence.PRESENT, ScreenState.INTERACTIVE), ps(Presence.PRESENT, ScreenState.INTERACTIVE), now))
    // Leaving.
    assertNull(WhosThereGlancePolicy.trigger(ps(Presence.PRESENT, ScreenState.INTERACTIVE), ps(Presence.ABSENT, ScreenState.INTERACTIVE), now))
    // Unconfident presence counts as unknown; a short unknown is not an arrival.
    assertNull(WhosThereGlancePolicy.trigger(ps(Presence.ABSENT, ScreenState.INTERACTIVE, confident = false),
        ps(Presence.PRESENT, ScreenState.INTERACTIVE, confident = false), now))
    // Waking with nobody known to be here.
    assertNull(WhosThereGlancePolicy.trigger(ps(Presence.UNKNOWN, ScreenState.OFF, since = now - 1_000),
        ps(Presence.UNKNOWN, ScreenState.INTERACTIVE), now))
  }

  @Test fun returningToHomeAfterAWhileWithSomeoneHereIsAWake() {
    val here = ps(Presence.PRESENT, ScreenState.INTERACTIVE)
    assertEquals(GlanceTrigger.WAKE, WhosThereGlancePolicy.onReturn(WhosThereGlancePolicy.RETURN_AFTER_MS, here))
    assertNull(WhosThereGlancePolicy.onReturn(5_000, here)) // back from Settings: no glance
    assertNull(WhosThereGlancePolicy.onReturn(120_000, ps(Presence.ABSENT, ScreenState.INTERACTIVE)))
    assertNull(WhosThereGlancePolicy.onReturn(120_000, ps(Presence.PRESENT, ScreenState.INTERACTIVE, confident = false)))
  }

  @Test fun longUnknownThenPresentIsAnArrival() {
    assertEquals(GlanceTrigger.ARRIVAL, WhosThereGlancePolicy.trigger(
        ps(Presence.UNKNOWN, ScreenState.INTERACTIVE, since = now - 61_000), ps(Presence.PRESENT, ScreenState.INTERACTIVE), now))
  }

  // ---- view selection ----

  private val maya = WhosTherePerson("mayaaaaa", "Maya", WhosThereRole.ADULT, 0, 0L)
  private val arjun = WhosTherePerson("arjunnnn", "Arjun", WhosThereRole.ADULT, 1, 0L)
  private val leela = WhosTherePerson("leelaaaa", "Leela", WhosThereRole.KID, 3, 0L)
  private val people = listOf(maya, arjun, leela)

  private fun out(rec: List<String>, faces: Int = rec.size, unsure: String? = null) = GlanceOutcome(rec, unsure, faces, 5)

  @Test fun onePersonGetsTheirHome() =
      assertEquals(WhosThereView.Personal("mayaaaaa", othersNear = false), WhosThereViewPolicy.select(out(listOf("mayaaaaa")), people, now))

  @Test fun someoneElseNearBlurs() =
      assertEquals(WhosThereView.Personal("mayaaaaa", othersNear = true), WhosThereViewPolicy.select(out(listOf("mayaaaaa"), faces = 2), people, now))

  @Test fun twoOrMoreIsFamily() =
      assertEquals(WhosThereView.Family(listOf("mayaaaaa", "arjunnnn")), WhosThereViewPolicy.select(out(listOf("mayaaaaa", "arjunnnn")), people, now))

  @Test fun unknownIsShared() {
    assertEquals(WhosThereView.Shared, WhosThereViewPolicy.select(out(emptyList(), faces = 1), people, now))
    assertEquals(WhosThereView.Shared, WhosThereViewPolicy.select(GlanceOutcome.EMPTY, people, now))
  }

  @Test fun unsureAloneAsksUnsureWithOthersIsShared() {
    assertEquals(WhosThereView.Personal("mayaaaaa", othersNear = false, confirm = true),
        WhosThereViewPolicy.select(out(emptyList(), faces = 1, unsure = "mayaaaaa"), people, now))
    assertEquals(WhosThereView.Shared, WhosThereViewPolicy.select(out(emptyList(), faces = 2, unsure = "mayaaaaa"), people, now))
  }

  @Test fun pausedPeopleAreTreatedAsUnknown() {
    val paused = listOf(maya.copy(pausedUntilMs = now + 60_000), arjun)
    assertEquals(WhosThereView.Shared, WhosThereViewPolicy.select(out(listOf("mayaaaaa")), paused, now))
    // Maya's face is still in view, so Arjun's private items blur.
    assertEquals(WhosThereView.Personal("arjunnnn", othersNear = true),
        WhosThereViewPolicy.select(out(listOf("mayaaaaa", "arjunnnn")), paused, now))
    assertEquals(WhosThereView.Shared, WhosThereViewPolicy.select(out(emptyList(), faces = 1, unsure = "mayaaaaa"), paused, now))
  }

  @Test fun deletedPeopleAreIgnored() =
      assertEquals(WhosThereView.Shared, WhosThereViewPolicy.select(out(listOf("goneeeee")), people, now))

  @Test fun aSecondFaceBlursButKeepsTheHome() {
    val cur = WhosThereView.Personal("mayaaaaa", othersNear = false)
    assertEquals(cur.copy(othersNear = true), WhosThereViewPolicy.update(cur, out(listOf("mayaaaaa"), faces = 2), people, now))
    assertEquals(cur.copy(othersNear = true), WhosThereViewPolicy.update(cur, out(emptyList(), faces = 2), people, now))
    assertEquals(WhosThereView.Family(listOf("mayaaaaa", "arjunnnn")),
        WhosThereViewPolicy.update(cur, out(listOf("mayaaaaa", "arjunnnn")), people, now))
    // The owner left and someone else is alone at the Portal: their Home (or shared), not Maya's.
    assertEquals(WhosThereView.Personal("arjunnnn", othersNear = false), WhosThereViewPolicy.update(cur, out(listOf("arjunnnn")), people, now))
    assertEquals(WhosThereView.Shared, WhosThereViewPolicy.update(cur, out(emptyList(), faces = 1), people, now))
    assertEquals(WhosThereView.Shared, WhosThereViewPolicy.update(cur, GlanceOutcome.EMPTY, people, now))
    assertEquals(WhosThereView.Personal("mayaaaaa", false), WhosThereViewPolicy.update(WhosThereView.Shared, out(listOf("mayaaaaa")), people, now))
    val confirming = cur.copy(confirm = true)
    assertEquals(cur, WhosThereViewPolicy.update(confirming, out(listOf("mayaaaaa")), people, now))
    assertEquals(WhosThereView.Shared, WhosThereViewPolicy.update(confirming, out(emptyList(), faces = 2), people, now))
  }

  @Test fun peopleOfAView() {
    assertEquals(emptyList<String>(), WhosThereViewPolicy.people(WhosThereView.Shared))
    assertEquals(listOf("mayaaaaa"), WhosThereViewPolicy.people(WhosThereView.Personal("mayaaaaa", false)))
    assertEquals(listOf("a", "b"), WhosThereViewPolicy.people(WhosThereView.Family(listOf("a", "b"))))
  }

  // ---- privacy ----

  private fun priv(view: WhosThereView, owner: WhosTherePerson? = maya, inCall: Boolean = false, setting: Boolean = true,
                   reveal: Long = 0L) = WhosTherePrivacy.Context(view, owner, inCall, setting, reveal, now)

  @Test fun privateItemsShowOnlyToTheOwnerAlone() {
    assertFalse(WhosTherePrivacy.hidePrivate(priv(WhosThereView.Personal("mayaaaaa", false))))
    assertTrue(WhosTherePrivacy.hidePrivate(priv(WhosThereView.Personal("mayaaaaa", true))))
    assertTrue(WhosTherePrivacy.hidePrivate(priv(WhosThereView.Shared, owner = null)))
    assertTrue(WhosTherePrivacy.hidePrivate(priv(WhosThereView.Family(listOf("mayaaaaa", "arjunnnn")))))
    assertTrue(WhosTherePrivacy.hidePrivate(priv(WhosThereView.Personal("mayaaaaa", false, confirm = true))))
  }

  @Test fun kidsHaveNoPrivateItems() {
    assertTrue(WhosTherePrivacy.hidePrivate(priv(WhosThereView.Personal("leelaaaa", false), owner = leela)))
    assertFalse(WhosTherePrivacy.canReveal(priv(WhosThereView.Personal("leelaaaa", false), owner = leela)))
  }

  @Test fun callsKeepPrivateHidden() =
      assertTrue(WhosTherePrivacy.hidePrivate(priv(WhosThereView.Personal("mayaaaaa", false), inCall = true)))

  @Test fun ownerMayRevealForAWhile() {
    val near = WhosThereView.Personal("mayaaaaa", true)
    assertTrue(WhosTherePrivacy.canReveal(priv(near)))
    assertFalse(WhosTherePrivacy.hidePrivate(priv(near, reveal = now + 1)))
    assertTrue(WhosTherePrivacy.hidePrivate(priv(near, reveal = now)))
    assertFalse(WhosTherePrivacy.canReveal(priv(WhosThereView.Family(listOf("a", "b")))))
    assertFalse(WhosTherePrivacy.canReveal(priv(near.copy(confirm = true))))
    assertFalse(WhosTherePrivacy.canReveal(priv(near, owner = null)))
  }

  @Test fun theHideWhenOthersNearSettingCanBeTurnedOff() =
      assertFalse(WhosTherePrivacy.hidePrivate(priv(WhosThereView.Personal("mayaaaaa", true), setting = false)))

  // ---- indicator ----

  private val ind = IndicatorInputs(enabled = true, enrolled = 1, modelsError = null, looking = false,
      view = WhosThereView.Shared, pausedUntilMs = 0L, inCall = false, quietHours = false, debugIgnoreQuiet = false,
      quietEndMinute = 480, lastGlanceDark = false, lastGlanceCovered = false, nowMs = now)

  @Test fun indicatorStates() {
    val p = WhosThereIndicatorPolicy
    assertEquals(WhosThereIndicator.Hidden, p.of(ind.copy(enabled = false)))
    assertEquals(WhosThereIndicator.Hidden, p.of(ind.copy(enrolled = 0)))
    assertEquals(WhosThereIndicator.Unavailable("x"), p.of(ind.copy(modelsError = "x")))
    assertEquals(WhosThereIndicator.Ready, p.of(ind))
    assertEquals(WhosThereIndicator.Looking, p.of(ind.copy(looking = true, view = WhosThereView.Personal("m", false))))
    assertEquals(WhosThereIndicator.Paused(now + 5), p.of(ind.copy(pausedUntilMs = now + 5)))
    assertEquals(WhosThereIndicator.Recognised("m", unsure = true, othersNear = false),
        p.of(ind.copy(view = WhosThereView.Personal("m", false, confirm = true))))
    assertEquals(WhosThereIndicator.Family(listOf("a", "b")), p.of(ind.copy(view = WhosThereView.Family(listOf("a", "b")))))
    assertEquals(WhosThereIndicator.InCall, p.of(ind.copy(inCall = true)))
    assertEquals(WhosThereIndicator.QuietHours(480), p.of(ind.copy(quietHours = true)))
    assertEquals(WhosThereIndicator.Ready, p.of(ind.copy(quietHours = true, debugIgnoreQuiet = true)))
    assertEquals(WhosThereIndicator.CameraCovered, p.of(ind.copy(lastGlanceCovered = true, lastGlanceDark = true)))
    assertEquals(WhosThereIndicator.TooDark, p.of(ind.copy(lastGlanceDark = true)))
  }

  @Test fun clockLabels() {
    assertEquals("8:00", WhosThereIndicatorPolicy.clock(480, use24 = true))
    assertEquals("6:30", WhosThereIndicatorPolicy.clock(1110, use24 = false))
    assertEquals("12:05", WhosThereIndicatorPolicy.clock(5, use24 = false))
    assertEquals("0:05", WhosThereIndicatorPolicy.clock(1445, use24 = true))
  }
}
