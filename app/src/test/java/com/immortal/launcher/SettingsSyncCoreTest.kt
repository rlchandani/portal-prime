/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.SyncRig.Companion.EMPTY
import com.immortal.launcher.SyncRig.Companion.HI
import com.immortal.launcher.SyncRig.Companion.LO
import com.immortal.launcher.SyncRig.Companion.rec
import com.immortal.launcher.SyncRig.Companion.synced
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sync's ledger rules on fakes: joining, the adopt/keep choice, local edits, merges, pause. */
class SettingsSyncCoreTest {
  @Test fun nothingHappensBeforeInstall() {
    val r = SyncRig()
    r.signal(HI, EMPTY)
    r.changed(HI, rec("theme", "dark", 1))
    r.core.detect()
    r.core.resolveJoin(false); r.core.dismissAdoptCard(); r.core.rejoin()
    assertNull(r.host.stored)
    assertTrue(r.sent.isEmpty())
    assertEquals("{}", r.core.valuesJson().toString())
    assertEquals(JSONNull, r.core.statusJson().get("keyFingerprint"))
  }

  @Test fun aLonePortalFoundsWithItsOwnChangesAndAnnouncesTheDigest() {
    val r = SyncRig(mapOf("chime.volume" to 40)).start()
    assertEquals(SyncPhase.JOINING, r.core.ledger.phase)
    r.signal(HI, EMPTY, joining = false)
    assertEquals(SyncPhase.SYNCED, r.core.ledger.phase)
    assertEquals(mapOf("chime.volume" to rec("chime.volume", 40, 1, FakeSyncHost.SELF).copy(ts = r.now)), r.core.ledger.records)
    val sig = r.sentOf(SettingsSyncWire.SIGNAL_TYPE).single()
    assertEquals(r.digest(), sig.getString("d"))
    assertEquals(false, sig.opt("j"))
    assertEquals(r.core.ledger, r.host.stored)
  }

  @Test fun twoJoiningPortalsLetTheLowerIdFound() {
    val r = SyncRig().start()
    r.signal(LO, EMPTY, joining = true)
    assertEquals("a lower peer founds; we wait", SyncPhase.JOINING, r.core.ledger.phase)
    r.signal(HI, EMPTY, joining = true)
    assertEquals(SyncPhase.SYNCED, r.core.ledger.phase)
  }

  @Test fun aNewPortalWithDefaultsAdoptsSilentlyAndSiblingWritesAreNotEchoed() {
    val r = SyncRig().start()
    r.peerReply = { r.payload("Living", false, rec("chime.volume", 55, 3), rec("clock.face", "neon", 2)) }
    r.signal(HI, "0123456789ab")
    assertEquals(SyncPhase.SYNCED, r.core.ledger.phase)
    assertEquals("Living", r.core.ledger.adoptedFrom)
    assertEquals("neon", r.settings.values["clock.style"])
    assertEquals(1L, r.counter("joinsAuto"))
    r.sent.clear()
    r.core.noteLocalChange(); r.timers.fire()
    assertTrue("the sibling the setter rewrote is not a local edit", r.sentOf(SettingsSyncWire.CHANGED_TYPE).isEmpty())
  }

  @Test fun aConflictWaitsForTheChoiceAndKeepingMineVersionsItAfterTheHouseholds() {
    val r = SyncRig(mapOf("chime.volume" to 40)).start()
    assertEquals(r.payload("Family", true), r.core.handlePeer(HI, r.payload("Living", false, rec("chime.volume", 55, 3), rec("theme", "dark", 1))))
    assertEquals("Living", r.status.value.adoptFrom)
    r.core.dismissAdoptCard()
    assertTrue(r.host.cardDismissed)
    r.core.resolveJoin(keepMine = true)
    assertEquals(rec("chime.volume", 40, 4, FakeSyncHost.SELF).copy(ts = r.now), r.core.ledger.records["chime.volume"])
    assertEquals("dark", r.settings.values["theme"])
    assertEquals(40, r.settings.values["chime.volume"])
    assertFalse(r.host.cardDismissed)
    assertEquals(1L, r.counter("joinsChosen"))
  }

  @Test fun adoptingTakesTheHouseholdsValue() {
    val r = SyncRig(mapOf("chime.volume" to 40)).start()
    r.core.handlePeer(HI, r.payload("Living", false, rec("chime.volume", 55, 3)))
    r.core.resolveJoin(keepMine = false)
    assertEquals(55, r.settings.values["chime.volume"])
    assertTrue(r.log.lines.any { it.contains("adopted, 1 written") })
  }

  @Test fun aLocalEditIsVersionedAndSentAsOneChangeEach() {
    val r = SyncRig().apply { host.stored = synced(rec("chime.volume", 70, 5)) }.start()
    r.settings.values = r.settings.values + ("chime.volume" to 30) + ("theme" to "dark")
    r.core.noteLocalChange()
    assertEquals(listOf(400L), r.timers.posted.map { it.first })
    r.core.noteLocalChange()
    assertEquals("the debounce restarts", 1, r.timers.posted.size)
    r.timers.fire()
    assertEquals(listOf("chime.volume", "theme"), r.sentOf(SettingsSyncWire.CHANGED_TYPE).map { it.getString("key") })
    assertEquals(7L, r.core.ledger.clock)
    assertEquals(1, r.sentOf(SettingsSyncWire.SIGNAL_TYPE).size)
    assertEquals(2L, r.counter("changedSent"))
  }

  @Test fun aBigBatchGoesOutAsOneSignalOnly() {
    val r = SyncRig().apply { host.stored = synced() }.start()
    r.settings.values = r.settings.values + (1..9).associate { "k$it" to it }
    r.core.detect() // new keys (an update added them) are only remembered
    r.settings.values = r.settings.values + (1..9).associate { "k$it" to it * 100 }
    r.core.detect()
    assertTrue(r.sentOf(SettingsSyncWire.CHANGED_TYPE).isEmpty())
    assertEquals(1, r.sentOf(SettingsSyncWire.SIGNAL_TYPE).size)
    assertEquals(9, r.core.ledger.records.size)
  }

  @Test fun busChangesMergeByVersionAndTheDeviceIdBreaksTies() {
    val r = SyncRig().apply { host.stored = synced() }.start()
    r.changed(HI, rec("chime.volume", 50, 3, HI))
    r.changed(LO, rec("chime.volume", 45, 3, LO))
    assertEquals(50, r.settings.values["chime.volume"])
    r.changed(LO, rec("chime.volume", 45, 4, LO))
    assertEquals(45, r.settings.values["chime.volume"])
    r.changed(LO, rec("chime.volume", 1, 9, HI))
    assertEquals("a change is only taken from the Portal that wrote it", 1L, r.counter("changedRefused"))
    r.changed(HI, rec("nope", "x", 9))
    r.changed(HI, rec("chime.volume", "loud", 9))
    assertEquals(2L, r.counter("recordsRefused"))
  }

  @Test fun aValueThisBuildRefusesIsKeptForLaterAndReconciledAfterAnUpdate() {
    val r = SyncRig().apply { host.stored = synced() }.start()
    r.changed(HI, rec("clock.face", "hologram", 2))
    assertEquals("classic", r.settings.values["clock.face"])
    assertEquals("hologram", r.core.ledger.records["clock.face"]?.value)
    val again = SyncRig().apply { host.stored = r.host.stored; settings.invalid = emptySet() }.start()
    assertEquals("hologram", again.settings.values["clock.face"])
    assertTrue(again.log.lines.any { it == "i reconciled clock.face" })
  }

  @Test fun aPendingLocalEditWinsOverAnOlderPeerChange() {
    val r = SyncRig().apply { host.stored = synced(rec("chime.volume", 70, 5)) }.start()
    r.settings.values = r.settings.values + ("chime.volume" to 32)
    // Their 6 ties with the 6 the pending edit gets first; then the device id decides.
    r.changed(LO, rec("chime.volume", 33, 6, LO))
    assertEquals(32, r.settings.values["chime.volume"])
    assertEquals(FakeSyncHost.SELF, r.core.ledger.records["chime.volume"]?.version?.device)
  }

  @Test fun pauseStopsBothDirectionsAndResumeAnnounces() {
    val r = SyncRig().apply { host.stored = synced() }.start()
    r.core.setPaused(true)
    assertTrue(r.status.value.paused)
    r.changed(HI, rec("chime.volume", 20, 3))
    r.signal(HI, "0123456789ab")
    assertNull(r.core.handlePeer(HI, r.payload("Living", false)))
    r.settings.values = r.settings.values + ("chime.volume" to 10)
    r.core.detect()
    assertEquals(10, r.settings.values["chime.volume"])
    assertTrue(r.sent.isEmpty())
    assertTrue(r.posts.isEmpty())
    r.core.setPaused(false)
    assertEquals(1, r.sentOf(SettingsSyncWire.SIGNAL_TYPE).size)
  }

  @Test fun rejoinForgetsTheLedgerButNotTheSettings() {
    val r = SyncRig(mapOf("chime.volume" to 50)).apply { host.stored = synced(rec("chime.volume", 50, 3)) }.start()
    r.core.dismissAdoptCard()
    r.core.rejoin()
    assertEquals(SyncPhase.JOINING, r.core.ledger.phase)
    assertTrue(r.core.ledger.records.isEmpty())
    assertEquals(50, r.core.ledger.lastKnown["chime.volume"])
    assertFalse(r.host.cardDismissed)
  }

  @Test fun noDeviceIdOrKeyOrAddressMeansNothingIsAnnounced() {
    val r = SyncRig { lan = null }.start()
    r.core.signal()
    r.host.lan = FakeSyncHost().lan; r.host.key = null
    r.core.signal()
    assertTrue(r.sent.isEmpty())
    r.host.id = null
    r.signal(HI, EMPTY)
    assertEquals(SyncPhase.JOINING, r.core.ledger.phase)
  }

  @Test fun aFailingRegistryWriteIsLoggedAndTheLedgerStillMoves() {
    val r = SyncRig().apply { host.stored = synced() }.start()
    r.settings.failApply = true
    r.changed(HI, rec("chime.volume", 20, 3))
    assertTrue("w applying synced settings failed" in r.log.lines)
    assertEquals(20, r.core.ledger.records["chime.volume"]?.value)
  }

  private companion object {
    val JSONNull: Any = org.json.JSONObject.NULL
  }
}
