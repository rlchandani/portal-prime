package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Last-writer-wins merge, version ordering, local change detection, joining and catch-up. */
class SettingsSyncEngineTest {
  private val e = SettingsSyncEngine
  private val known = setOf("clock.face", "theme", "chime.on", "level")
  private val wellFormed: (String, Any?) -> Any? = { k, v -> v.takeIf { k in known && (v is String || v is Boolean || v is Int) } }
  private val defaults = mapOf("clock.face" to "classic", "theme" to "light", "chime.on" to false, "level" to 5)

  private fun rec(key: String, value: Any, counter: Long, device: String = "A", ts: Long = 1L) =
      SyncRecord(key, value, SyncVersion(counter, device), ts)

  private fun synced(records: List<SyncRecord> = emptyList(), current: Map<String, Any> = defaults) =
      SyncLedger(phase = SyncPhase.SYNCED, clock = records.maxOfOrNull { it.version.counter } ?: 0L,
          records = records.associateBy { it.key }, lastKnown = current)

  @Test
  fun versionsOrderByCounterThenDevice() {
    assertTrue(SyncVersion(2, "A") > SyncVersion(1, "Z"))
    assertTrue(SyncVersion(2, "B") > SyncVersion(2, "A"))
    assertEquals(0, SyncVersion(3, "A").compareTo(SyncVersion(3, "A")))
  }

  @Test
  fun newerWinsOlderIsStaleSameIsDuplicate() {
    val l0 = synced(listOf(rec("theme", "dark", 5)))
    val (l1, newer) = e.offer(l0, rec("theme", "light", 6, "B"), wellFormed)
    assertEquals(MergeOutcome.APPLIED, newer)
    assertEquals("light", l1.records.getValue("theme").value)
    assertEquals(6, l1.clock)
    assertEquals(MergeOutcome.STALE, e.offer(l1, rec("theme", "dark", 5), wellFormed).second)
    assertEquals(MergeOutcome.DUPLICATE, e.offer(l1, rec("theme", "light", 6, "B"), wellFormed).second)
    val (l2, tie) = e.offer(l1, rec("theme", "dark", 6, "C"), wellFormed)
    assertEquals("an equal counter breaks the tie by device id", MergeOutcome.APPLIED, tie)
    assertEquals("dark", l2.records.getValue("theme").value)
  }

  @Test
  fun wallClockNeverDecides() {
    val l0 = synced(listOf(rec("theme", "dark", 9, ts = 1L)))
    val far = rec("theme", "light", 3, "B", ts = Long.MAX_VALUE / 2)
    assertEquals(MergeOutcome.STALE, e.offer(l0, far, wellFormed).second)
  }

  @Test
  fun malformedOrOutOfBoundsRecordsAreRejected() {
    val l0 = synced()
    assertEquals(MergeOutcome.REJECTED, e.offer(l0, rec("unknown", "x", 1), wellFormed).second)
    assertEquals(MergeOutcome.REJECTED, e.offer(l0, rec("theme", listOf(1), 1), wellFormed).second)
    assertEquals(MergeOutcome.REJECTED, e.offer(l0, rec("theme", "dark", 0), wellFormed).second)
    assertEquals(MergeOutcome.REJECTED, e.offer(l0, rec("theme", "dark", e.MAX_COUNTER + 1), wellFormed).second)
    assertEquals(MergeOutcome.REJECTED, e.offer(l0, rec("theme", "dark", 1, "bad id!"), wellFormed).second)
    assertEquals(MergeOutcome.REJECTED, e.offer(l0, rec("theme", "dark", 1, ts = -1), wellFormed).second)
  }

  @Test
  fun mergeAllReportsWinnersAndCapsTheBatch() {
    val l0 = synced(listOf(rec("theme", "dark", 5)))
    val r = e.mergeAll(l0, listOf(rec("theme", "light", 4, "B"), rec("level", 7, 2, "B"), rec("nope", 1, 1)), wellFormed)
    assertEquals(listOf("level"), r.applied.map { it.key })
    assertEquals(1, r.stale)
    assertEquals(1, r.rejected)
    val flood = (1..(e.MAX_RECORDS + 10)).map { rec("theme", "x$it", it.toLong(), "B") }
    assertEquals(10, e.mergeAll(l0, flood, wellFormed).rejected)
  }

  @Test
  fun aLocalEditBecomesTheNextVersion() {
    val l0 = synced(listOf(rec("theme", "dark", 7, "B")), current = defaults + ("theme" to "dark"))
    val (l1, changed) = e.detectLocal(l0, defaults + ("theme" to "dark") + ("clock.face" to "neon"), "A", 100)
    assertEquals(listOf("clock.face"), changed.map { it.key })
    assertEquals(SyncVersion(8, "A"), changed.single().version)
    assertEquals(8, l1.clock)
    assertEquals("neon", l1.lastKnown["clock.face"])
    // Seeing the same values again changes nothing.
    assertEquals(0, e.detectLocal(l1, l1.lastKnown, "A", 200).second.size)
  }

  @Test
  fun aWrittenPeerValueIsNotEchoedBack() {
    val l0 = synced(current = defaults)
    val merged = e.mergeAll(l0, listOf(rec("theme", "dark", 3, "B")), wellFormed).ledger
    val after = defaults + ("theme" to "dark")
    val (_, changed) = e.detectLocal(merged, after, "A", 10)
    assertTrue(changed.isEmpty())
    val noted = e.noteWritten(merged, mapOf("theme" to "dark"))
    assertEquals("dark", noted.lastKnown["theme"])
  }

  @Test
  fun aSettingAddedByAnUpdateIsRememberedNotBroadcast() {
    val l0 = synced(current = defaults - "level")
    val (l1, changed) = e.detectLocal(l0, defaults, "A", 1)
    assertTrue(changed.isEmpty())
    assertEquals(5, l1.lastKnown["level"])
  }

  @Test
  fun joiningPortalsRecordNothing() {
    val l0 = SyncLedger(lastKnown = defaults)
    val (l1, changed) = e.detectLocal(l0, defaults + ("theme" to "dark"), "A", 1)
    assertTrue(changed.isEmpty())
    assertTrue(l1.records.isEmpty())
  }

  @Test
  fun seedsAreOnlyTheValuesChangedFromDefaults() {
    val current = defaults + ("theme" to "dark") + ("level" to 9)
    val seeds = e.seeds(current, defaults, mapOf("level" to rec("level", 9, 4, "B")), "A", after = 10, now = 5)
    assertEquals(listOf("theme"), seeds.map { it.key })
    assertEquals(SyncVersion(11, "A"), seeds.single().version)
  }

  @Test
  fun joinRules() {
    assertEquals(JoinAction.FETCH, e.joinAction("A", "B", "abcdefabcdef", peerJoining = false))
    assertEquals(JoinAction.FETCH, e.joinAction("Z", "B", "abcdefabcdef", peerJoining = true))
    assertEquals(JoinAction.FOUND, e.joinAction("Z", "B", e.EMPTY_DIGEST, peerJoining = false))
    assertEquals(JoinAction.FOUND, e.joinAction("A", "B", e.EMPTY_DIGEST, peerJoining = true))
    assertEquals(JoinAction.WAIT, e.joinAction("B", "A", e.EMPTY_DIGEST, peerJoining = true))
  }

  @Test
  fun theFounderSharesOnlyItsChanges() {
    val current = defaults + ("theme" to "dark")
    val l = e.found(SyncLedger(), current, defaults, "A", 1)
    assertEquals(SyncPhase.SYNCED, l.phase)
    assertEquals(setOf("theme"), l.records.keys)
    assertEquals(current, l.lastKnown)
  }

  @Test
  fun freshDefaultsNeverWin() {
    val household = listOf(rec("theme", "dark", 4, "B"), rec("clock.face", "neon", 6, "B"))
    // A brand-new Portal: every value is the default.
    val joining = e.addPending(SyncLedger(lastKnown = defaults), "B", "Family Area", household, wellFormed)
    assertFalse(e.hasConflicts(joining.pending!!, defaults, defaults))
    listOf(false, true).forEach { keepMine ->
      val (l, writes) = e.resolveJoin(joining, defaults, defaults, "Z", 10, keepMine)
      assertEquals(mapOf<String, Any>("theme" to "dark", "clock.face" to "neon"), writes)
      assertEquals("nothing of the fresh Portal's becomes a record", household.associateBy { it.key }, l.records)
      assertEquals(SyncPhase.SYNCED, l.phase)
      assertEquals("Family Area", l.adoptedFrom)
    }
    // Even a later default-valued read never outranks the household.
    val (l, _) = e.resolveJoin(joining, defaults, defaults, "Z", 10, keepMine = false)
    assertTrue(e.detectLocal(l, l.lastKnown, "Z", 11).second.isEmpty())
  }

  @Test
  fun adoptTakesTheHouseholdButKeepsChangesItNeverMade() {
    val household = listOf(rec("theme", "dark", 4, "B"))
    val current = defaults + ("theme" to "light") + ("level" to 8) // level: household never changed it
    val joining = e.addPending(SyncLedger(lastKnown = current), "B", "Family", household, wellFormed)
    val (l, writes) = e.resolveJoin(joining, current + ("theme" to "sepia"), defaults, "Z", 10, keepMine = false)
    assertEquals(mapOf<String, Any>("theme" to "dark"), writes)
    assertEquals(8, l.records.getValue("level").value)
    assertTrue(l.records.getValue("level").version.counter > 4)
  }

  @Test
  fun keepMineWinsWhereThisPortalChangedSomething() {
    val household = listOf(rec("theme", "dark", 4, "B"), rec("clock.face", "neon", 5, "B"))
    val current = defaults + ("theme" to "sepia") // face is still the default here
    val joining = e.addPending(SyncLedger(lastKnown = current), "B", "Family", household, wellFormed)
    assertTrue(e.hasConflicts(joining.pending!!, current, defaults))
    val (l, writes) = e.resolveJoin(joining, current, defaults, "Z", 10, keepMine = true)
    assertEquals(mapOf<String, Any>("clock.face" to "neon"), writes)
    val mine = l.records.getValue("theme")
    assertEquals("sepia", mine.value)
    assertTrue("my kept value must outrank the household's", mine.version > household[0].version)
  }

  @Test
  fun pendingTakesTheNewestAcrossPeers() {
    var l = SyncLedger()
    l = e.addPending(l, "B", "Family", listOf(rec("theme", "dark", 4, "B")), wellFormed)
    l = e.addPending(l, "C", "Office", listOf(rec("theme", "light", 9, "C"), rec("level", 2, 3, "C")), wellFormed)
    assertEquals("light", l.pending!!.records.getValue("theme").value)
    assertEquals(setOf("theme", "level"), l.pending!!.records.keys)
    assertEquals("the first household seen names the choice", "Family", l.pending!!.fromName)
    assertNull(e.addPending(SyncLedger(), "B", "x", emptyList(), wellFormed).pending)
  }

  @Test
  fun digestIgnoresOrderAndValuesButNotVersions() {
    val a = listOf(rec("theme", "dark", 1), rec("level", 3, 2)).associateBy { it.key }
    val b = listOf(rec("level", 3, 2), rec("theme", "dark", 1)).associateBy { it.key }
    assertEquals(e.digest(a), e.digest(b))
    assertNotEquals(e.digest(a), e.digest(a + ("theme" to rec("theme", "dark", 3))))
    assertEquals(12, e.EMPTY_DIGEST.length)
  }

  @Test
  fun anOfflinePortalCatchesUpByExchangingFullState() {
    // Two Portals in sync; B goes offline and misses two changes made on A.
    var a = synced(listOf(rec("theme", "dark", 1, "A")), defaults + ("theme" to "dark"))
    var b = a
    val (a1, c1) = e.detectLocal(a, a.lastKnown + ("clock.face" to "neon"), "A", 10)
    val (a2, c2) = e.detectLocal(a1, a1.lastKnown + ("theme" to "light"), "A", 20)
    a = a2
    assertEquals(2, (c1 + c2).size)
    // Meanwhile B (offline) changed the chime.
    val (b1, _) = e.detectLocal(b, b.lastKnown + ("chime.on" to true), "B", 15)
    b = b1
    assertNotEquals(e.digest(a.records), e.digest(b.records))
    // B comes back: one exchange each way (B posts its records, A replies with its own).
    val atA = e.mergeAll(a, b.records.values, wellFormed)
    val atB = e.mergeAll(b, atA.ledger.records.values, wellFormed)
    assertEquals(listOf("chime.on"), atA.applied.map { it.key })
    assertEquals(setOf("clock.face", "theme"), atB.applied.map { it.key }.toSet())
    assertEquals(e.digest(atA.ledger.records), e.digest(atB.ledger.records))
    assertEquals("light", atB.ledger.records.getValue("theme").value)
  }

  @Test
  fun aChangeMadeAfterSeeingAnotherAlwaysWins() {
    val a = synced(listOf(rec("theme", "dark", 41, "Z")))
    val (_, changed) = e.detectLocal(a, defaults + ("theme" to "sepia"), "A", 1)
    assertTrue(changed.single().version > SyncVersion(41, "Z"))
  }

  @Test
  fun peersAreCappedAndRemembered() {
    var l = SyncLedger()
    (1..20).forEach { l = e.notePeer(l, "p$it", "Room $it", it.toLong(), synced = it == 20) }
    assertEquals(16, l.peers.size)
    assertEquals(20, l.lastSyncAt)
    l = e.notePeer(l, "p20", null, 30, synced = false)
    assertEquals("Room 20", l.peers.getValue("p20").name)
    assertEquals(20, l.peers.getValue("p20").syncedAt)
  }
}
