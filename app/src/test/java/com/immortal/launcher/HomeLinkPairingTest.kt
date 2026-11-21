/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Linking two homes over an untrusted mailbox: hello → commit → reveal → open → compare pictures.
 * The "relay" here is the test itself, so it can drop, replay, forge and swap messages.
 */
class HomeLinkPairingTest {
  private val random = SecureRandom()
  private val maple = HomeLinkSelf(HomeLinkIdentity.generate(random), "Maple House")
  private val lake = HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview")
  private val code = HomeLinkCode.parse("MPT7Q4K2PA")!!
  private val wrong = HomeLinkCode.parse("MPT7Q4K2PB")!!
  private val invite = HomeLinkInvite(code, createdAt = 0L)
  private val mailbox = HomeLinkCode.mailboxId(code)
  private val now = 60_000L

  private fun hello() = HomeLinkInviter.start(invite, random)

  private fun commit(h: HomeLinkStep<HomeLinkInviterState>, self: HomeLinkSelf = lake, c: HomeLinkCode = code) =
      HomeLinkJoiner.receive(HomeLinkJoiner.start(c, random), self, h.outgoing!!, random)

  private fun toInviter(state: HomeLinkInviterState, m: HomeLinkMessage, at: Long = now) = HomeLinkInviter.receive(state, maple, m, at)

  /** Runs the four messages; returns both sides comparing and everything the relay saw. */
  private fun run(): Triple<HomeLinkInviterState, HomeLinkJoinerState, List<HomeLinkMessage>> {
    val h = hello()
    val j1 = commit(h)
    val i1 = toInviter(h.state, j1.outgoing!!)
    val j2 = HomeLinkJoiner.receive(j1.state, lake, i1.outgoing!!, random)
    val i2 = toInviter(i1.state, j2.outgoing!!)
    return Triple(i2.state, j2.state, listOf(h.outgoing!!, j1.outgoing!!, i1.outgoing!!, j2.outgoing!!))
  }

  @Test fun happyPathBothScreensShowTheSamePictures() {
    val (i, j) = run()
    i as HomeLinkInviterState.Comparing
    j as HomeLinkJoinerState.Comparing
    assertEquals(i.safety, j.safety)
    assertEquals("Lakeview", i.peer.name)
    assertEquals(lake.identity.publicKey, i.peer.publicKey)
    assertEquals("Maple House", j.peer.name)
    assertEquals(maple.identity.publicKey, j.peer.publicKey)
  }

  @Test fun messagesGoInOrderWithTheirSequenceNumbers() {
    val seen = run().third
    assertEquals(listOf(0, 1, 2, 3), seen.map { it.seq })
    assertTrue(seen[0] is HomeLinkMessage.Hello && seen[1] is HomeLinkMessage.Commit &&
        seen[2] is HomeLinkMessage.Reveal && seen[3] is HomeLinkMessage.Open)
  }

  @Test fun theyMatchLinksBothHomesWithPinnedKeys() {
    val (i, j) = run()
    val linkedI = HomeLinkInviter.confirm(i, matched = true)
    val linkedJ = HomeLinkJoiner.confirm(j, matched = true)
    assertEquals(lake.identity.publicKey, (linkedI as HomeLinkInviterState.Linked).peer.publicKey)
    assertEquals(maple.identity.publicKey, (linkedJ as HomeLinkJoinerState.Linked).peer.publicKey)
  }

  @Test fun theyDontMatchStopsAndKeepsNothing() {
    val (i, j) = run()
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.MISMATCH), HomeLinkInviter.confirm(i, matched = false))
    assertEquals(HomeLinkJoinerState.Failed(HomeLinkFailure.MISMATCH), HomeLinkJoiner.confirm(j, matched = false))
  }

  @Test fun confirmOutsideComparingChangesNothing() {
    val waiting = hello().state
    assertSame(waiting, HomeLinkInviter.confirm(waiting, matched = true))
    val awaiting = HomeLinkJoiner.start(code, random)
    assertSame(awaiting, HomeLinkJoiner.confirm(awaiting, matched = true))
  }

  @Test fun commitSaysNothingAboutTheJoiner() {
    val wire = HomeLinkWire.encode(commit(hello()).outgoing!!)
    assertFalse(wire.contains("Lakeview"))
    assertFalse(wire.contains(lake.identity.publicKey))
  }

  // ---- Offline guessing: nothing the relay sees can be tested against a guess. ----

  @Test fun theSecretNeverCrossesTheRelay() {
    for (m in run().third) {
      val wire = HomeLinkWire.encode(m)
      assertFalse(wire.contains(code.secret))
      assertFalse(wire.contains(code.value))
    }
  }

  /**
   * Two pairings with the same code: apart from the mailbox (public nameplate), the identity keys
   * and names, every value on the wire differs. A deterministic function of the secret (like the
   * old hash-of-the-code mailbox or code-keyed MACs) would repeat and could be brute-forced.
   */
  @Test fun nothingDeterministicFromTheSecretCrossesTheRelay() {
    fun values(ms: List<HomeLinkMessage>): Set<String> = ms.flatMap { m ->
      val o = org.json.JSONObject(HomeLinkWire.encode(m))
      o.keys().asSequence().filter { it !in setOf("v", "q", "t", "b", "k", "n") }.map { o.get(it).toString() }.toList()
    }.toSet()
    assertEquals(emptySet<String>(), values(run().third) intersect values(run().third))
  }

  /** Even the right code is no help with a recording: the commit MAC can't be checked without a PAKE scalar. */
  @Test fun aRecordedTranscriptCantConfirmEvenTheRightCode() {
    val seen = run().third
    val hello = seen[0] as HomeLinkMessage.Hello
    val commit = seen[1] as HomeLinkMessage.Commit
    val eve = HomeLinkPake.start(code.secret, mailbox, random)
    for (theirs in listOf(hello.share, commit.share)) {
      val k = HomeLinkPake.finish(eve, theirs)!!
      val guess = HomeLinkSession.derive(mailbox, hello.share, commit.share, k)
      assertFalse(HomeLinkPairingProof.fromPeer(guess, mailbox, commit))
    }
  }

  // ---- Online guessing: each try is one join attempt, three per invite. ----

  @Test fun aWrongCodeIsIgnoredAndCountsAsATry() {
    val h = hello()
    val step = toInviter(h.state, commit(h, c = wrong).outgoing!!)
    assertEquals(HomeLinkIgnored.WRONG_CODE, step.ignored)
    assertNull(step.outgoing)
    assertEquals(1, (step.state as HomeLinkInviterState.Waiting).failedTries)
  }

  @Test fun threeWrongTriesBurnTheInviteAndTheRightCodeNoLongerWorks() {
    val h = hello()
    var state = h.state
    repeat(HomeLinkInviter.MAX_TRIES) { state = toInviter(state, commit(h, c = wrong).outgoing!!).state }
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.TOO_MANY_TRIES), state)
    val late = toInviter(state, commit(h).outgoing!!)
    assertNull(late.outgoing)
    assertEquals(HomeLinkIgnored.UNEXPECTED, late.ignored)
  }

  @Test fun twoWrongTriesThenTheRightCodeStillLinks() {
    val h = hello()
    var state = h.state
    repeat(HomeLinkInviter.MAX_TRIES - 1) { state = toInviter(state, commit(h, c = wrong).outgoing!!).state }
    val ok = toInviter(state, commit(h).outgoing!!)
    assertTrue(ok.state is HomeLinkInviterState.AwaitingOpen)
    assertTrue(ok.outgoing is HomeLinkMessage.Reveal)
  }

  @Test fun aGarbageShareCountsAsATry() {
    val h = hello()
    val good = commit(h).outgoing as HomeLinkMessage.Commit
    val step = toInviter(h.state, good.copy(share = HomeLinkHash.b64(ByteArray(32) { 0xFF.toByte() })))
    assertEquals(1, (step.state as HomeLinkInviterState.Waiting).failedTries)
  }

  @Test fun anotherMailboxIsIgnoredWithoutCountingATry() {
    val h = hello()
    val other = HomeLinkCode.parse("XYZ7Q4K2PA")!!
    val stray = (commit(h).outgoing as HomeLinkMessage.Commit).copy(mailbox = HomeLinkCode.mailboxId(other))
    val step = toInviter(h.state, stray)
    assertEquals(HomeLinkIgnored.WRONG_CODE, step.ignored)
    assertSame(h.state, step.state)
  }

  @Test fun theCodeWorksOnce() {
    val h = hello()
    val first = toInviter(h.state, commit(h).outgoing!!)
    val second = toInviter(first.state, commit(h, HomeLinkSelf(HomeLinkIdentity.generate(random), "Eve")).outgoing!!)
    assertSame(first.state, second.state)
    assertNull(second.outgoing)
    assertEquals(HomeLinkIgnored.ALREADY_USED, second.ignored)
  }

  @Test fun anExpiredInviteFails() {
    val h = hello()
    val step = toInviter(h.state, commit(h).outgoing!!, at = invite.expiresAt)
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED), step.state)
    assertNull(step.outgoing)
  }

  // ---- Sequence numbers: covered by the MAC, out-of-sequence rejected. ----

  @Test fun theMacCoversTheSequenceNumber() {
    val h = hello()
    val j1 = commit(h)
    val session = (j1.state as HomeLinkJoinerState.Committing).session
    val c = j1.outgoing as HomeLinkMessage.Commit
    assertTrue(HomeLinkPairingProof.fromPeer(session, mailbox, c))
    assertFalse(HomeLinkPairingProof.fromPeer(session, mailbox, c.copy(seq = 3)))
  }

  @Test fun outOfSequenceMessagesAreRejectedAndDontCountAsTries() {
    val h = hello()
    val j1 = commit(h)
    val session = (j1.state as HomeLinkJoinerState.Committing).session
    val c = j1.outgoing as HomeLinkMessage.Commit
    // Relabelled in transit, and re-MAC'd by someone holding the session: both refused.
    for (m in listOf(c.copy(seq = 2), HomeLinkPairingProof.withMac(session, c.copy(seq = 2)))) {
      val step = toInviter(h.state, m)
      assertEquals(HomeLinkIgnored.OUT_OF_SEQUENCE, step.ignored)
      assertSame(h.state, step.state)
    }
    val badHello = (h.outgoing as HomeLinkMessage.Hello).copy(seq = 1)
    assertEquals(HomeLinkIgnored.OUT_OF_SEQUENCE, HomeLinkJoiner.receive(HomeLinkJoiner.start(code, random), lake, badHello, random).ignored)
    val i1 = toInviter(h.state, c)
    val reveal = i1.outgoing as HomeLinkMessage.Reveal
    val relabelled = HomeLinkPairingProof.withMac(session, reveal.copy(seq = 3))
    assertEquals(HomeLinkIgnored.OUT_OF_SEQUENCE, HomeLinkJoiner.receive(j1.state, lake, relabelled, random).ignored)
  }

  // ---- Tampering. ----

  @Test fun anOpenThatBreaksItsCommitmentFails() {
    val h = hello()
    val j1 = commit(h)
    val i1 = toInviter(h.state, j1.outgoing!!)
    val session = (j1.state as HomeLinkJoinerState.Committing).session
    // Someone holding the session answers with a key that was never committed.
    val impostor = HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview")
    val nonce = (j1.state as HomeLinkJoinerState.Committing).nonce
    val sig = impostor.identity.sign(HomeLinkPairingProof.openText(session, mailbox, maple.identity.publicKey, nonce, impostor.name))
    val open = HomeLinkPairingProof.withMac(session, HomeLinkMessage.Open(mailbox, impostor.identity.publicKey, nonce, impostor.name, sig))
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.TAMPERED), toInviter(i1.state, open).state)
  }

  @Test fun aRevealFromAnotherSessionIsIgnored() {
    val h = hello()
    val j1 = commit(h)
    val i1 = toInviter(h.state, j1.outgoing!!)
    val other = commit(h)
    val step = HomeLinkJoiner.receive(other.state, lake, i1.outgoing!!, random)
    assertEquals(HomeLinkIgnored.WRONG_CODE, step.ignored)
  }

  @Test fun aForgedRevealSignatureFails() {
    val h = hello()
    val j1 = commit(h)
    val session = (j1.state as HomeLinkJoinerState.Committing).session
    val reveal = toInviter(h.state, j1.outgoing!!).outgoing as HomeLinkMessage.Reveal
    val forged = HomeLinkPairingProof.withMac(session, reveal.copy(name = "Evil House"))
    val j2 = HomeLinkJoiner.receive(j1.state, lake, forged, random)
    assertEquals(HomeLinkJoinerState.Failed(HomeLinkFailure.TAMPERED), j2.state)
    assertNull(j2.outgoing)
  }

  @Test fun aRevealWithoutTheSessionKeyIsIgnored() {
    val h = hello()
    val j1 = commit(h)
    val reveal = toInviter(h.state, j1.outgoing!!).outgoing as HomeLinkMessage.Reveal
    val step = HomeLinkJoiner.receive(j1.state, lake, reveal.copy(mac = "AAAA"), random)
    assertSame(j1.state, step.state)
    assertEquals(HomeLinkIgnored.WRONG_CODE, step.ignored)
  }

  @Test fun aHelloWithABadShareOrMailboxIsIgnored() {
    val start = HomeLinkJoiner.start(code, random)
    val h = hello().outgoing as HomeLinkMessage.Hello
    assertEquals(HomeLinkIgnored.UNEXPECTED, HomeLinkJoiner.receive(start, lake, h.copy(share = "AAAA"), random).ignored)
    val elsewhere = h.copy(mailbox = HomeLinkCode.mailboxId(HomeLinkCode.parse("XYZ7Q4K2PA")!!))
    assertEquals(HomeLinkIgnored.WRONG_CODE, HomeLinkJoiner.receive(start, lake, elsewhere, random).ignored)
  }

  @Test fun outOfOrderMessagesAreIgnored() {
    val h = hello()
    val j1 = commit(h)
    val i1 = toInviter(h.state, j1.outgoing!!)
    // The joiner's own commit echoed back, a replayed commit to the inviter, a reveal sent to an inviter.
    assertEquals(HomeLinkIgnored.UNEXPECTED, HomeLinkJoiner.receive(j1.state, lake, j1.outgoing!!, random).ignored)
    val (i, _) = run()
    assertEquals(HomeLinkIgnored.ALREADY_USED, toInviter(i, j1.outgoing!!).ignored)
    assertEquals(HomeLinkIgnored.UNEXPECTED, toInviter(h.state, i1.outgoing!!).ignored)
  }

  /** Worst case, Mallory knows the code and runs both halves: the pictures still differ. */
  @Test fun aRelayInTheMiddleShowsDifferentPictures() {
    val mallory = HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview")
    val malloryAsMaple = HomeLinkSelf(HomeLinkIdentity.generate(random), "Maple House")
    val h = hello()
    val m1 = commit(h, mallory)
    val i1 = toInviter(h.state, m1.outgoing!!)
    val m2 = HomeLinkJoiner.receive(m1.state, mallory, i1.outgoing!!, random)
    val atMaple = toInviter(i1.state, m2.outgoing!!).state as HomeLinkInviterState.Comparing

    val mh = HomeLinkInviter.start(invite, random)
    val j1 = commit(mh)
    val mi1 = HomeLinkInviter.receive(mh.state, malloryAsMaple, j1.outgoing!!, now)
    val atLake = HomeLinkJoiner.receive(j1.state, lake, mi1.outgoing!!, random).state as HomeLinkJoinerState.Comparing
    assertNotEquals(atMaple.safety, atLake.safety)
  }

  @Test fun failedStatesIgnoreEverything() {
    val failed = HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED)
    assertEquals(HomeLinkIgnored.UNEXPECTED, toInviter(failed, commit(hello()).outgoing!!).ignored)
  }

  @Test fun tickExpiresOnlyAnOpenInvite() {
    val waiting = hello().state
    assertSame(waiting, HomeLinkInviter.tick(waiting, invite.expiresAt - 1))
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED), HomeLinkInviter.tick(waiting, invite.expiresAt))
    val (comparing, _) = run()
    assertSame(comparing, HomeLinkInviter.tick(comparing, invite.expiresAt + 1))
  }

  @Test fun bothSidesDeriveTheSameLinkKeyFromThePinnedKeys() {
    val (i, j) = run()
    val atMaple = maple.identity.linkKey((i as HomeLinkInviterState.Comparing).peer.publicKey, maple.identity.publicKey)
    val atLake = lake.identity.linkKey((j as HomeLinkJoinerState.Comparing).peer.publicKey, maple.identity.publicKey)
    assertArrayEquals(atMaple, atLake)
  }

  /**
   * Verify has no skip: once both sides are comparing, no message (a replay of any of the four,
   * or the joiner's open again) and no amount of waiting links anyone. Only a person's answer does.
   */
  @Test fun comparingEndsOnlyWithAPersonsAnswer() {
    val h = hello()
    val j1 = commit(h)
    val i1 = toInviter(h.state, j1.outgoing!!)
    val j2 = HomeLinkJoiner.receive(j1.state, lake, i1.outgoing!!, random)
    val i2 = toInviter(i1.state, j2.outgoing!!)
    val i = i2.state as HomeLinkInviterState.Comparing
    val j = j2.state as HomeLinkJoinerState.Comparing
    for (m in listOf(h.outgoing!!, j1.outgoing!!, i1.outgoing!!, j2.outgoing!!)) {
      for (at in listOf(now, invite.expiresAt, Long.MAX_VALUE)) {
        assertEquals(i, toInviter(i, m, at).state)
        assertNull(toInviter(i, m, at).outgoing)
      }
      assertEquals(j, HomeLinkJoiner.receive(j, lake, m, random).state)
      assertNull(HomeLinkJoiner.receive(j, lake, m, random).outgoing)
    }
    assertEquals(i, HomeLinkInviter.tick(i, Long.MAX_VALUE))
    assertEquals(HomeLinkInviterState.Linked(i.peer), HomeLinkInviter.confirm(i, matched = true))
    assertEquals(HomeLinkJoinerState.Linked(j.peer), HomeLinkJoiner.confirm(j, matched = true))
  }

  /** The screens agree: Verify has no timer, no auto-answer, and the test flow links only from "They match". */
  @Test fun theVerifyScreenHasNoSkipOrAutoConfirm() {
    val dir = listOf(java.io.File("src/main/java/com/immortal/launcher"), java.io.File("app/src/main/java/com/immortal/launcher"))
        .first { it.isDirectory }
    val screens = java.io.File(dir, "HomeLinkScreens.kt").readText()
    val start = screens.indexOf("internal fun HomeLinkVerifyScreen(")
    val verify = screens.substring(start, screens.indexOf("\n}\n", start))
    for (banned in listOf("LaunchedEffect", "delay(", "onMatch()", "onMatch.invoke", "SideEffect", "DisposableEffect", "Timer"))
      assertFalse("Verify uses $banned", verify.contains(banned))
    assertEquals("onMatch is only the parameter and the They match button", 2, Regex("onMatch").findAll(verify).count())
    assertTrue(verify.contains("CallPill(HomeLinkCopy.MATCH, onMatch"))
    val sources = dir.listFiles { f -> f.name.startsWith("HomeLink") && f.name.endsWith(".kt") }!!.associate { it.name to it.readText() }
    for ((name, text) in sources) {
      if (name == "HomeLinkRoles.kt") continue
      assertFalse("$name makes a Linked state", text.contains("InviterState.Linked(") || text.contains("JoinerState.Linked("))
      assertFalse("$name confirms a match itself", Regex("""confirm\([^)]*true""").containsMatchIn(text))
    }
    val flow = sources.getValue("HomeLinkTestFlow.kt")
    assertEquals(1, Regex("""stage = HomeLinkTestStage\.PERMS""").findAll(flow).count())
    val onMatch = flow.substring(flow.indexOf("onMatch = {"), flow.indexOf("onMismatch ="))
    assertTrue(onMatch.contains("stage = HomeLinkTestStage.PERMS"))
    assertTrue(onMatch.contains(".link(peer"))
    assertEquals(1, Regex("""\.link\(peer""").findAll(flow).count())
  }

  @Test fun homeNamesMustBeShowable() {
    assertThrows(IllegalArgumentException::class.java) { HomeLinkSelf(maple.identity, "") }
    assertThrows(IllegalArgumentException::class.java) { HomeLinkSelf(maple.identity, "x".repeat(HomeLinkWire.MAX_NAME + 1)) }
  }
}
