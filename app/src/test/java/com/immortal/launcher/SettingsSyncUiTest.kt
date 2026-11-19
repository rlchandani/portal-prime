package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings notes for sync: the page note, the row label and the About status text. */
class SettingsSyncUiTest {
  private val on = SettingsSyncStatus(available = true, phase = SyncPhase.SYNCED)

  @Test
  fun householdPagesShowTheSyncNoteOnlyWhenSyncIsOn() {
    val note = settingsSyncPageNote(on)
    assertEquals(SYNCED_PAGE_NOTE, note)
    val layout = settingsPageLayout(SettingsPageIds.CLOCK, advanced = true, screensaverOn = true, syncNote = note)
    assertTrue(layout.entries.any { it.key == "note:sync" && it.text == SYNCED_PAGE_NOTE })
    val off = settingsPageLayout(SettingsPageIds.CLOCK, advanced = true, screensaverOn = true)
    assertFalse(off.entries.any { it.key == "note:sync" })
  }

  @Test
  fun pagesWithOnlyThisPortalsSettingsHaveNoSyncNote() {
    assertFalse(settingsPageHasHouseholdRows(SettingsPageIds.ABOUT))
    assertFalse(settingsPageHasHouseholdRows(SettingsPageIds.MQTT))
    assertTrue(settingsPageHasHouseholdRows(SettingsPageIds.CALLS))
  }

  @Test
  fun noteTextFollowsTheSyncState() {
    assertNull(settingsSyncPageNote(SettingsSyncStatus()))
    assertNull(settingsSyncPageNote(on.copy(paused = true)))
    assertNull(settingsSyncPageNote(on.copy(phase = SyncPhase.JOINING)))
    assertTrue(settingsSyncPageNote(on.copy(phase = SyncPhase.JOINING, adoptFrom = "Family"))!!.startsWith("Not synced yet"))
  }

  @Test
  fun deviceRowsSayThisPortalOnly() {
    assertEquals("This Portal only", thisPortalOnlyHelp(null))
    assertEquals("This Portal only. Off until you turn it on.", thisPortalOnlyHelp("Off until you turn it on."))
  }

  @Test
  fun aboutStatusText() {
    assertTrue(SettingsSyncStatus().summary.contains("household key"))
    assertTrue(on.copy(adoptFrom = "Family").summary.contains("Family"))
    assertEquals("Not yet", on.lastSyncLabel(1000))
    assertEquals("just now", on.copy(lastSyncAt = 1000).lastSyncLabel(30_000))
    assertEquals("5 min ago", SettingsSyncStatus.ago(5 * 60_000L))
    assertEquals("2 h ago", SettingsSyncStatus.ago(2 * 3_600_000L))
    assertEquals("3 d ago", SettingsSyncStatus.ago(3 * 86_400_000L))
    val peer = SyncPeer("p", "Office", 0)
    assertEquals("Seen just now", on.peerLabel(peer, 1000))
  }

  @Test
  fun statusIsBuiltFromTheLedger() {
    val ledger = SyncLedger(phase = SyncPhase.SYNCED, lastSyncAt = 5,
        records = mapOf("a" to SyncRecord("a", true, SyncVersion(1, "d"), 0)),
        peers = mapOf("p1" to SyncPeer("p1", "Old", 1), "p2" to SyncPeer("p2", "New", 9)),
        pending = PendingAdopt("p2", "New", emptyMap()))
    val s = SettingsSyncStatus.from(ledger, available = true, paused = false, conflicts = true)
    assertEquals(listOf("New", "Old"), s.peers.map { it.name })
    assertEquals(1, s.recordCount)
    assertEquals("New", s.adoptFrom)
    assertTrue(s.adoptHasConflicts)
  }
}
