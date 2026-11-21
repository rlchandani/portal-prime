/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.Serializable
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The 3-try limit is what makes a 35-bit secret safe, so it must hold when joins arrive in
 * parallel, and it must not be resettable by restarting the inviter (an invite never outlives
 * the process: restart and it is gone, with nothing left to guess at).
 */
class HomeLinkInviterSessionTest {
  private val random = SecureRandom()
  private val maple = HomeLinkSelf(HomeLinkIdentity.generate(random), "Maple House")
  private val lake = HomeLinkSelf(HomeLinkIdentity.generate(random), "Lakeview")
  private val code = HomeLinkCode.parse("MPT7Q4K2PA")!!
  private val invite = HomeLinkInvite(code, createdAt = 0L)
  private val now = 60_000L

  private fun commitFor(session: HomeLinkInviterSession, typed: HomeLinkCode): HomeLinkMessage =
      HomeLinkJoiner.receive(HomeLinkJoiner.start(typed, random), lake, session.hello, random).outgoing!!

  /** Fires all [messages] at [session] at once from separate threads. */
  private fun parallel(session: HomeLinkInviterSession, messages: List<HomeLinkMessage>): List<HomeLinkStep<HomeLinkInviterState>> {
    val pool = Executors.newFixedThreadPool(messages.size)
    val go = CountDownLatch(1)
    try {
      val futures = messages.map { m -> pool.submit<HomeLinkStep<HomeLinkInviterState>> { go.await(); session.receive(m, now) } }
      go.countDown()
      return futures.map { it.get(30, TimeUnit.SECONDS) }
    } finally {
      pool.shutdownNow()
    }
  }

  @Test fun parallelWrongJoinsNeverGetMoreThanThreeTries() {
    val session = HomeLinkInviterSession.start(invite, maple, random)
    val wrong = (0 until 24).map { commitFor(session, HomeLinkCode.parse("MPT7Q4K2%02d".format(it))!!) }
    val steps = parallel(session, wrong)
    assertEquals(HomeLinkInviter.MAX_TRIES - 1, steps.count { it.ignored == HomeLinkIgnored.WRONG_CODE })
    assertEquals(1, steps.count { it.ignored == null && it.state == HomeLinkInviterState.Failed(HomeLinkFailure.TOO_MANY_TRIES) })
    assertEquals(wrong.size - HomeLinkInviter.MAX_TRIES, steps.count { it.ignored == HomeLinkIgnored.UNEXPECTED })
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.TOO_MANY_TRIES), session.state)
    // The right code, after the burn, gets nowhere.
    assertEquals(HomeLinkIgnored.UNEXPECTED, session.receive(commitFor(session, code), now).ignored)
  }

  @Test fun theRightCodeRacingWrongOnesStillSpendsAtMostThreeTries() {
    repeat(5) {
      val session = HomeLinkInviterSession.start(invite, maple, random)
      val wrong = (0 until 12).map { commitFor(session, HomeLinkCode.parse("MPT7Q4K2%02d".format(it))!!) }
      val steps = parallel(session, (wrong + commitFor(session, code)).shuffled(random))
      val evaluated = steps.count { it.ignored == HomeLinkIgnored.WRONG_CODE || it.ignored == null }
      assertTrue("evaluated $evaluated commits", evaluated <= HomeLinkInviter.MAX_TRIES)
      assertTrue(steps.count { it.outgoing is HomeLinkMessage.Reveal } <= 1)
      val s = session.state
      assertTrue(s is HomeLinkInviterState.AwaitingOpen || s == HomeLinkInviterState.Failed(HomeLinkFailure.TOO_MANY_TRIES))
    }
  }

  /**
   * A forced restart can't reset the counter, because there is nothing to resume: the PAKE half
   * is random per start, so even an inviter rebuilt from the same code (the app never does this:
   * a new screen makes a new code) treats a commit made against the old hello as a wrong code.
   */
  @Test fun aRestartedInviterCantResumeTheOldInvite() {
    val before = HomeLinkInviterSession.start(invite, maple, random)
    val commit = commitFor(before, code)
    repeat(HomeLinkInviter.MAX_TRIES - 1) { before.receive(commitFor(before, HomeLinkCode.parse("MPT7Q4K2PB")!!), now) }
    val after = HomeLinkInviterSession.start(invite, maple, random)
    assertNotEquals((before.hello as HomeLinkMessage.Hello).share, (after.hello as HomeLinkMessage.Hello).share)
    val step = after.receive(commit, now)
    assertEquals(HomeLinkIgnored.WRONG_CODE, step.ignored)
    assertNull(step.outgoing)
  }

  /** Invites, PAKE halves and the try counter live in memory only: nothing stores or restores them. */
  @Test fun invitesAreNeverPersisted() {
    val stored = listOf(HomeLinkVault::class.java, HomeLinkEntry::class.java, HomeLinkStoredIdentity::class.java,
        HomeLink::class.java, HomeLinkPermissions::class.java, HomeLinkBook::class.java)
    val inviteTypes = setOf(HomeLinkInvite::class.java, HomeLinkCode::class.java, HomeLinkPakeHalf::class.java,
        HomeLinkInviterState::class.java, HomeLinkSession::class.java, HomeLinkInviterSession::class.java)
    for (c in stored) for (f in c.declaredFields) assertFalse("${c.simpleName}.${f.name}", f.type in inviteTypes)
    val transient = listOf(HomeLinkPakeHalf::class.java, HomeLinkSession::class.java, HomeLinkInviterSession::class.java,
        HomeLinkInvite::class.java, HomeLinkInviterState.Waiting::class.java, HomeLinkInviterState.AwaitingOpen::class.java,
        HomeLinkInviterState.Comparing::class.java, HomeLinkInviterState.Linked::class.java, HomeLinkInviterState.Failed::class.java)
    for (c in transient) {
      assertFalse("${c.simpleName} is Serializable", Serializable::class.java.isAssignableFrom(c))
      assertFalse("${c.simpleName} is Parcelable", android.os.Parcelable::class.java.isAssignableFrom(c))
    }
    val dir = listOf(File("src/main/java/com/immortal/launcher"), File("app/src/main/java/com/immortal/launcher")).first { it.isDirectory }
    val sources = dir.listFiles { f -> f.name.startsWith("HomeLink") && f.name.endsWith(".kt") }!!
    assertTrue(sources.size > 10)
    for (f in sources) {
      val text = f.readText()
      for (banned in listOf("getSharedPreferences", "onSaveInstanceState", "SavedStateHandle"))
        assertFalse("${f.name} uses $banned", text.contains(banned))
      // Saved UI state only for plain toggles (the keypad's letters/digits mode), never pairing state.
      for (line in text.lines().filter { it.contains("rememberSaveable {") })
        assertTrue("${f.name}: $line", Regex("""rememberSaveable \{ mutableStateOf\((true|false)\) }""").containsMatchIn(line))
    }
    for (name in listOf("HomeLinkVault.kt", "HomeLinkRepository.kt", "HomeLinkSyncWire.kt")) {
      val text = File(dir, name).readText()
      for (banned in listOf("HomeLinkInvite", "HomeLinkCode", "HomeLinkPakeHalf", "failedTries"))
        assertFalse("$name mentions $banned", text.contains(banned))
    }
  }

  @Test fun theSessionRunsTheWholeExchange() {
    val session = HomeLinkInviterSession.start(invite, maple, random)
    val j1 = HomeLinkJoiner.receive(HomeLinkJoiner.start(code, random), lake, session.hello, random)
    val i1 = session.receive(j1.outgoing!!, now)
    val j2 = HomeLinkJoiner.receive(j1.state, lake, i1.outgoing!!, random)
    session.receive(j2.outgoing!!, now)
    val comparing = session.state as HomeLinkInviterState.Comparing
    assertEquals((j2.state as HomeLinkJoinerState.Comparing).safety, comparing.safety)
    assertEquals(HomeLinkInviterState.Linked(comparing.peer), session.confirm(matched = true))
    assertEquals(HomeLinkInviterState.Linked(comparing.peer), session.tick(invite.expiresAt + 1))
  }

  @Test fun tickExpiresTheSessionsOpenInvite() {
    val session = HomeLinkInviterSession.start(invite, maple, random)
    assertEquals(HomeLinkInviterState.Failed(HomeLinkFailure.EXPIRED), session.tick(invite.expiresAt))
  }
}
