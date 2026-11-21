/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A critical overlay outlives an accessibility unbind (uiautomator, `adb install -r`): [OverlayHold]. */
class OverlayHoldTest {
  private val up = OverlayHold<String>().hostReady(0L)

  @Test fun anUnbindParksTheOverlayAndTheRebindShowsItForTheRestOfItsTime() {
    val shown = up.offer("leak:1", "leak", deadlineMs = 10_000L)
    assertTrue(shown.shown)
    val parked = shown.hostLost()
    assertTrue(parked.pending)
    assertFalse(parked.shown)
    val back = parked.hostReady(nowMs = 4_000L)
    assertTrue(back.shown)
    assertEquals("leak", back.held?.payload)
    // The unbound time counts: 10 s total, 4 s gone.
    assertEquals(6_000L, back.remainingMs(4_000L))
  }

  @Test fun anOverlayOfferedBeforeTheHostBindsWaitsForIt() {
    val early = OverlayHold<String>().offer("bell:1", "bell", 60_000L)
    assertTrue(early.pending)
    assertTrue(early.hostReady(1_000L).shown)
  }

  @Test fun dismissDuringUnbindIsNotUndoneByTheRebind() {
    val parked = up.offer("leak:1", "leak", 10_000L).hostLost()
    val dismissed = parked.dismiss("leak:1")
    assertNull(dismissed.held)
    val back = dismissed.hostReady(2_000L)
    assertFalse(back.shown)
    assertTrue(back.hostUp)
  }

  @Test fun dismissingAnOlderIdLeavesTheNewerOneHeld() {
    val parked = up.offer("leak:2", "newer", 10_000L).hostLost()
    assertSame(parked, parked.dismiss("leak:1"))
    assertNull(parked.dismiss().held)
  }

  @Test fun expiryDuringUnbindDropsItAndTheRebindShowsNothing() {
    val parked = up.offer("leak:1", "leak", 10_000L).hostLost()
    assertNull(parked.expire(10_000L).held)
    // Even when the expiry timer didn't run, the rebind checks the deadline itself.
    val late = parked.hostReady(12_000L)
    assertNull(late.held)
    assertFalse(late.shown)
    assertNull(parked.remainingMs(10_000L))
  }

  @Test fun expiryBeforeTheDeadlineKeepsIt() {
    val held = up.offer("leak:1", "leak", 10_000L)
    assertSame(held, held.expire(9_999L))
  }

  @Test fun aNewerAlertReplacesTheParkedOneWithItsOwnDeadline() {
    val parked = up.offer("leak:1", "old", 10_000L).hostLost()
    val replaced = parked.offer("smoke:1", "new", 70_000L)
    assertTrue(replaced.pending)
    assertEquals("smoke:1", replaced.held?.id)
    // The old deadline no longer applies.
    val back = replaced.hostReady(20_000L)
    assertTrue(back.shown)
    assertEquals("new", back.held?.payload)
    assertEquals(50_000L, back.remainingMs(20_000L))
    // The old one's dismissal can't take the new one down.
    assertSame(back, back.dismiss("leak:1"))
  }

  @Test fun repeatedUnbindsKeepOneDeadline() {
    var h = up.offer("leak:1", "leak", 10_000L)
    repeat(3) { i -> h = h.hostLost().hostReady(1_000L * (i + 1)) }
    assertTrue(h.shown)
    assertEquals(7_000L, h.remainingMs(3_000L))
  }

  // --- the on-disk record (an app update replaces the process) -----------------------------

  private fun record(deadline: Long = 60_000L, saved: Long = 1_000L, boot: Int = 7) =
      OverlayHoldRecord.encode(OverlayHold.Held("bell:1", JSONObject().put("k", "v"), deadline), saved, boot)

  @Test fun aRecordRoundTripsInTheSameBoot() {
    val r = OverlayHoldRecord.decode(record(), nowMs = 5_000L, bootCount = 7)
    assertNotNull(r)
    assertEquals("bell:1", r!!.id)
    assertEquals(60_000L, r.deadlineMs)
    assertEquals("v", r.payload.getString("k"))
  }

  @Test fun aRecordFromAnotherBootOrAnUnknownBootIsRefused() {
    assertNull(OverlayHoldRecord.decode(record(), 5_000L, bootCount = 8))
    assertNull(OverlayHoldRecord.decode(record(boot = OverlayHoldRecord.UNKNOWN_BOOT), 5_000L, OverlayHoldRecord.UNKNOWN_BOOT))
    // Uptime went backwards: a reboot the boot count didn't see.
    assertNull(OverlayHoldRecord.decode(record(saved = 9_000L), 5_000L, 7))
  }

  @Test fun anExpiredOrMalformedRecordIsRefused() {
    assertNull(OverlayHoldRecord.decode(record(deadline = 5_000L), 5_000L, 7))
    assertNull(OverlayHoldRecord.decode(null, 5_000L, 7))
    assertNull(OverlayHoldRecord.decode(JSONObject(), 5_000L, 7))
    assertNull(OverlayHoldRecord.decode(record().put("id", ""), 5_000L, 7))
    assertNull(OverlayHoldRecord.decode(record().put("id", "x".repeat(201)), 5_000L, 7))
    assertNull(OverlayHoldRecord.decode(record().put("payload", "nope"), 5_000L, 7))
  }

  // --- the two owners' payloads ---------------------------------------------------------------

  private val leak = CcAlert("binary_sensor.leak:1", CcSeverity.CRITICAL, CcKind.LEAK, "binary_sensor.leak", "Water leak · Laundry",
      "Leak sensor is wet", 42L, CcAlertAction.SHUT_WATER, "moisture")

  @Test fun aHeldControlCenterAlertRoundTrips() {
    val e = CcBannerHold.Entry(leak, takeover = true)
    assertEquals(e, CcBannerHold.decode(CcBannerHold.encode(e)))
    val warn = CcBannerHold.Entry(leak.copy(severity = CcSeverity.WARNING, kind = CcKind.GARAGE, deviceClass = null), takeover = false)
    assertEquals(warn, CcBannerHold.decode(CcBannerHold.encode(warn)))
  }

  @Test fun aPreviewOrAMalformedAlertIsNeverRestored() {
    val preview = CcBannerPreview.samples.last().alert
    assertNull(CcBannerHold.decode(CcBannerHold.encode(CcBannerHold.Entry(preview, true))))
    assertNull(CcBannerHold.decode(CcBannerHold.encode(CcBannerHold.Entry(leak, true)).put("severity", "PANIC")))
    assertNull(CcBannerHold.decode(CcBannerHold.encode(CcBannerHold.Entry(leak, true)).put("title", "")))
    assertNull(CcBannerHold.decode(null))
  }

  @Test fun aParkedCriticalStillOwnsTheBannerSlot() {
    val parked = OverlayHold<CcBannerHold.Entry>().offer(leak.id, CcBannerHold.Entry(leak, true), 10_000L).hostLost()
    val slot = CcBannerHold.slot(parked, drawn = null)
    assertEquals(CcBannerSlot.Showing(CcSeverity.CRITICAL, preview = false), slot)
    // A warning can't replace a parked critical, a newer critical can, and a preview never draws over it.
    assertFalse(CcBannerSlot.admitReal(slot, leak.copy(id = "garage:1", severity = CcSeverity.WARNING)))
    assertTrue(CcBannerSlot.admitReal(slot, leak.copy(id = "binary_sensor.leak:2")))
    assertFalse(CcBannerSlot.admitPreview(slot))
    // Nothing held: what is drawn (a preview) decides.
    val preview = CcBannerSlot.Showing(CcSeverity.INFO, preview = true)
    assertEquals(preview, CcBannerHold.slot(OverlayHold(), preview))
  }

  @Test fun aHeldDoorbellCardRoundTrips() {
    val card = DoorbellCardOverlay.Card("bell:1", "Someone's at the door", "Front door · Hall", "Chime on", quiet = false, camera = "camera.front")
    assertEquals(card, DoorbellCardOverlay.Card.decode(DoorbellCardOverlay.Card.encode(card)))
    assertNull(DoorbellCardOverlay.Card.decode(DoorbellCardOverlay.Card.encode(card).put("title", "")))
    assertNull(DoorbellCardOverlay.Card.decode(DoorbellCardOverlay.Card.encode(card).put("alert", "")))
  }
}
