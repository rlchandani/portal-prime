/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.youtube.YtSecureStore
import org.junit.Assert.*
import org.junit.Test

/** Grandma mode auto-answer: the stored rules, the in-person guard and the whole ring path. */
class CallAutoAnswerTest {
  private val sam = "22222222-2222-2222-2222-222222222222"
  private val alex = "33333333-3333-3333-3333-333333333333"
  private val id = "0123456789abcdef"
  private val t0 = 10_000_000L
  private val on = AutoAnswerRules(grandmaMode = true, enabled = true, allowed = setOf(sam))

  private class Fake(var text: String? = null, var broken: Boolean = false) : YtSecureStore {
    var saves = 0
    override fun load(): String? { if (broken) throw IllegalStateException("key gone"); return text }
    override fun save(text: String) { if (broken) throw IllegalStateException("key gone"); saves++; this.text = text }
    override fun clear() { text = null }
  }

  // --- codec -----------------------------------------------------------------------------------

  @Test fun codecRoundTrips() = assertEquals(on, AutoAnswerCodec.decode(AutoAnswerCodec.encode(on)))

  @Test fun defaultsAreAllOff() {
    val d = AutoAnswerRules()
    assertFalse(d.grandmaMode); assertFalse(d.enabled); assertTrue(d.allowed.isEmpty())
    assertEquals(d, AutoAnswerCodec.decode(null))
    assertEquals(d, AutoAnswerCodec.decode(""))
  }

  @Test fun anythingOddDecodesToAllOff() {
    val off = AutoAnswerRules()
    listOf(
        "not json", "[]", """{"v":2,"g":true,"a":true,"ids":[]}""", """{"g":true,"a":true,"ids":[]}""",
        """{"v":1,"g":"yes","a":true,"ids":[]}""", """{"v":1,"g":true,"a":1,"ids":[]}""",
        """{"v":1,"g":true,"a":true}""", """{"v":1,"g":true,"a":true,"ids":"x"}""",
        """{"v":1,"g":true,"a":true,"ids":[1]}""", """{"v":1,"g":true,"a":true,"ids":["bad id!"]}""",
        """{"v":1,"g":true,"a":true,"ids":["$sam","$sam"]}""",
        """{"v":1,"g":false,"a":true,"ids":["$sam"]}""",
        """{"v":1,"g":true,"a":true,"ids":[${(1..9).joinToString(",") { "\"p$it\"" }}]}""",
    ).forEach { assertEquals(it, off, AutoAnswerCodec.decode(it)) }
  }

  // --- edits -----------------------------------------------------------------------------------

  @Test fun grandmaOffDisarmsAutoAnswer() {
    val off = AutoAnswerEdits.grandma(on, false)
    assertFalse(off.enabled)
    assertFalse("switching Grandma mode back on never re-arms", AutoAnswerEdits.grandma(off, true).enabled)
  }

  @Test fun autoAnswerNeedsGrandmaMode() {
    assertEquals(AutoAnswerRules(), AutoAnswerEdits.enabled(AutoAnswerRules(), true))
    assertTrue(AutoAnswerEdits.enabled(AutoAnswerRules(grandmaMode = true), true).enabled)
  }

  @Test fun allowValidatesAndCapsAtEight() {
    var r = AutoAnswerRules(grandmaMode = true)
    assertEquals(r, AutoAnswerEdits.allow(r, "bad id!", true))
    (1..10).forEach { r = AutoAnswerEdits.allow(r, "p$it", true) }
    assertEquals(AutoAnswerRules.MAX_ALLOWED, r.allowed.size)
    assertFalse(AutoAnswerEdits.allow(r, "p1", false).allowed.contains("p1"))
    assertEquals(r, AutoAnswerEdits.allow(r, "p1", true))
  }

  @Test fun onlyEnablingOrAddingArms() {
    val base = AutoAnswerRules(grandmaMode = true)
    assertTrue(AutoAnswerEdits.arms(base, base.copy(enabled = true)))
    assertTrue(AutoAnswerEdits.arms(base, base.copy(allowed = setOf(sam))))
    assertFalse(AutoAnswerEdits.arms(on, on.copy(enabled = false)))
    assertFalse(AutoAnswerEdits.arms(on, on.copy(allowed = emptySet())))
    assertFalse(AutoAnswerEdits.arms(AutoAnswerRules(), base))
  }

  // --- repository -------------------------------------------------------------------------------

  @Test fun repositoryLoadsSavedRulesAndBrokenStorageReadsOff() {
    assertEquals(on, AutoAnswerRepository(Fake(AutoAnswerCodec.encode(on))) {}.rules.value)
    assertEquals(AutoAnswerRules(), AutoAnswerRepository(Fake(AutoAnswerCodec.encode(on), broken = true)) {}.rules.value)
  }

  @Test fun armingNeedsSomeoneAtThePortal() {
    val store = Fake()
    val repo = AutoAnswerRepository(store) {}
    assertTrue(repo.update(inPerson = false) { AutoAnswerEdits.grandma(it, true) })
    assertFalse("the phone remote was just used", repo.update(inPerson = false) { AutoAnswerEdits.enabled(it, true) })
    assertFalse(repo.rules.value.enabled)
    assertTrue(repo.update(inPerson = true) { AutoAnswerEdits.enabled(it, true) })
    assertFalse(repo.update(inPerson = false) { AutoAnswerEdits.allow(it, sam, true) })
    assertTrue(repo.update(inPerson = true) { AutoAnswerEdits.allow(it, sam, true) })
    assertTrue("turning off never needs a person", repo.update(inPerson = false) { AutoAnswerEdits.allow(it, sam, false) })
    assertTrue(repo.update(inPerson = false) { AutoAnswerEdits.grandma(it, false) })
    assertEquals(AutoAnswerRules(), AutoAnswerCodec.decode(store.text))
  }

  @Test fun unchangedEditDoesNotWriteAndFailedWriteKeepsOldRules() {
    val store = Fake(AutoAnswerCodec.encode(on))
    val repo = AutoAnswerRepository(store) {}
    assertTrue(repo.update(inPerson = false) { it })
    assertEquals(0, store.saves)
    store.broken = true
    assertFalse(repo.update(inPerson = true) { AutoAnswerEdits.allow(it, alex, true) })
    assertEquals(on, repo.rules.value)
  }

  @Test fun inPersonNeedsTwoQuietMinutesAfterRemoteInput() {
    assertFalse(CallInPerson.quietSince(t0, t0 + CallInPerson.REMOTE_QUIET_MS - 1))
    assertTrue(CallInPerson.quietSince(t0, t0 + CallInPerson.REMOTE_QUIET_MS))
    assertTrue(CallInPerson.INPUT_PATHS.containsAll(listOf("/remote/tap", "/remote/key", "/remote/swipe", "/remote/text")))
  }

  // --- eligibility ------------------------------------------------------------------------------

  @Test fun eligibleOnlyForChosenCallsOutsideQuietAndNotSnoozed() {
    assertTrue(CallAutoAnswer.eligible(on, sam, CallKind.CALL, quiet = false, lastDeclinedMs = null, nowMs = t0))
    assertFalse(CallAutoAnswer.eligible(on, alex, CallKind.CALL, false, null, t0))
    assertFalse(CallAutoAnswer.eligible(on, sam, CallKind.DROP_IN, false, null, t0))
    assertFalse(CallAutoAnswer.eligible(on, sam, CallKind.CALL, quiet = true, lastDeclinedMs = null, nowMs = t0))
    assertFalse(CallAutoAnswer.eligible(on.copy(enabled = false), sam, CallKind.CALL, false, null, t0))
    assertFalse(CallAutoAnswer.eligible(on.copy(grandmaMode = false), sam, CallKind.CALL, false, null, t0))
    val declined = t0 - CallAutoAnswer.SNOOZE_AFTER_DECLINE_MS + 1
    assertFalse(CallAutoAnswer.eligible(on, sam, CallKind.CALL, false, declined, t0))
    assertTrue(CallAutoAnswer.eligible(on, sam, CallKind.CALL, false, declined - 1, t0))
  }

  @Test fun lastDeclinedComesFromIncomingDeclinesOfThatCaller() {
    val h = listOf(
        CallHistoryEntry("aaaaaaaaaaaaaaaa", sam, "Sam", CallDirection.INCOMING, CallKind.CALL, CallEndReason.DECLINED, t0 - 5, 0),
        CallHistoryEntry("bbbbbbbbbbbbbbbb", sam, "Sam", CallDirection.INCOMING, CallKind.CALL, CallEndReason.DECLINED, t0 - 1, 0),
        CallHistoryEntry("cccccccccccccccc", sam, "Sam", CallDirection.OUTGOING, CallKind.CALL, CallEndReason.DECLINED, t0, 0),
        CallHistoryEntry("dddddddddddddddd", alex, "Alex", CallDirection.INCOMING, CallKind.CALL, CallEndReason.DECLINED, t0, 0),
        CallHistoryEntry("eeeeeeeeeeeeeeee", sam, "Sam", CallDirection.INCOMING, CallKind.CALL, CallEndReason.HUNG_UP, t0 + 9, 60_000))
    assertEquals(t0 - 1, CallAutoAnswer.lastDeclinedMs(h, sam))
    assertNull(CallAutoAnswer.lastDeclinedMs(h, "nobody"))
  }

  // --- end to end: policy → machine -------------------------------------------------------------

  private fun decide(rules: AutoAnswerRules, from: String, kind: CallKind = CallKind.CALL, quiet: Boolean = false,
                     history: List<CallHistoryEntry> = emptyList(), now: Long = t0): IncomingDecision {
    val eligible = CallAutoAnswer.eligible(rules, from, kind, quiet, CallAutoAnswer.lastDeclinedMs(history, from), now)
    return CallPolicy.decide(IncomingContext(kind, CallWhoCanCall.HOME, quiet, CallQuietMode.VISUAL, dropInsOn = true,
        dropInFromCaller = DropInPolicy.CHIME, busy = false, autoAnswer = eligible))
  }

  private fun ring(decision: IncomingDecision, from: String = sam, kind: CallKind = CallKind.CALL) =
      CallMachine.reduce(CallState(), CallEvent.Incoming(id, CallContact(from, "Sam"), kind, true, "192.168.1.20", 8723, decision, t0))

  @Test fun chosenCallerChimesCountsDownAndConnectsAtTenSeconds() {
    val d = decide(on, sam)
    assertEquals(IncomingDecision.AutoAnswer, d)
    val step = ring(d)
    assertTrue(CallEffect.AutoAnswerChime in step.effects)
    assertFalse("no camera or mic before it answers", step.effects.any { it is CallEffect.StartMedia || it is CallEffect.SetMic })
    val call = step.state.call!!
    assertEquals(t0 + CallTimings.AUTO_ANSWER_MS, call.autoAnswerAtMs)
    assertEquals(CallPhase.RINGING, CallMachine.reduce(step.state, CallEvent.Tick(t0 + CallTimings.AUTO_ANSWER_MS - 1)).state.call!!.phase)
    val answered = CallMachine.reduce(step.state, CallEvent.Tick(t0 + CallTimings.AUTO_ANSWER_MS))
    assertEquals(CallPhase.CONNECTING, answered.state.call!!.phase)
    assertNull(answered.state.call!!.autoAnswerAtMs)
    assertTrue(answered.effects.any { it is CallEffect.StartMedia })
  }

  @Test fun neverAutoAnswersInQuietHoursEvenWithSilentRingChosen() {
    assertEquals(IncomingDecision.Ring(silent = true), decide(on, sam, quiet = true))
    val step = ring(IncomingDecision.Ring(silent = true))
    assertNull(step.state.call!!.autoAnswerAtMs)
    assertEquals(CallPhase.RINGING, CallMachine.reduce(step.state, CallEvent.Tick(t0 + CallTimings.AUTO_ANSWER_MS * 2)).state.call!!.phase)
  }

  @Test fun dropInsAndOtherCallersNeverAutoAnswer() {
    assertEquals(IncomingDecision.Countdown, decide(on.copy(allowed = setOf(sam)), sam, kind = CallKind.DROP_IN))
    assertEquals(IncomingDecision.Ring(false), decide(on, alex))
  }

  @Test fun notNowDeclinesAndSnoozesThatCallerForThirtyMinutes() {
    val step = ring(IncomingDecision.AutoAnswer)
    val declined = CallMachine.reduce(step.state, CallEvent.Decline(t0 + 3_000))
    assertNull(declined.state.call)
    assertTrue(CallEffect.SendReply(id, sam, CallReply.DECLINED) in declined.effects)
    val log = declined.effects.filterIsInstance<CallEffect.Log>().single().entry
    assertEquals(CallEndReason.DECLINED, log.reason)
    val history = listOf(log)
    assertEquals(IncomingDecision.Ring(false), decide(on, sam, history = history, now = t0 + 10 * 60_000))
    assertEquals(IncomingDecision.AutoAnswer, decide(on, sam, history = history, now = t0 + 3_000 + CallAutoAnswer.SNOOZE_AFTER_DECLINE_MS))
  }

  @Test fun answerNowConnectsImmediately() {
    val step = ring(IncomingDecision.AutoAnswer)
    val now = CallMachine.reduce(step.state, CallEvent.Accept(audioOnly = false, now = t0 + 1_000))
    assertEquals(CallPhase.CONNECTING, now.state.call!!.phase)
  }
}
