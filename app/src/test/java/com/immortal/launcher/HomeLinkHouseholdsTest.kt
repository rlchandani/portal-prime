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
import java.time.ZoneId

/** Households & devices and the permissions screen (mock `hhScr`, `permsScr`): state, edits, words, sizes. */
class HomeLinkHouseholdsTest {
  private val random = SecureRandom()
  private val rooms = listOf("Living room", "Kitchen", "Kids’ room", "Office")
  private fun link(name: String) = HomeLink.verified(HomeLinkPeer(name, HomeLinkIdentity.generate(random).publicKey), 0L,
      HomeLinkPermissions.defaults(rooms, setOf("Kids’ room", "Office")))
  private val lake = link("Lakeview")
  private val priya = link("Priya & June’s")
  private val book = HomeLinkBook(listOf(lake, priya))

  @Test fun startsOnThisHomeWithTheListShowing() {
    val s = HomeLinkHouseholdsState(book)
    assertNull(s.link)
    assertEquals(HomeLinkPane.BOTH, s.pane(compact = false))
    assertEquals(HomeLinkPane.LIST, s.pane(compact = true))
  }

  @Test fun selectingAHomeOpensItsDetailOnAMini() {
    val s = HomeLinkHouseholdsState(book).select(lake.id)
    assertEquals(lake, s.link)
    assertEquals(HomeLinkPane.DETAIL, s.pane(compact = true))
    assertEquals(HomeLinkPane.BOTH, s.pane(compact = false))
    assertEquals(HomeLinkPane.LIST, s.back().pane(compact = true))
  }

  @Test fun selectingAnUnknownIdFallsBackToThisHome() {
    assertNull(HomeLinkHouseholdsState(book).select("nope").selected)
  }

  @Test fun removeAsksFirstAndOnlyForALinkedHome() {
    val home = HomeLinkHouseholdsState(book)
    assertSame(home, home.askRemove())
    val asking = home.select(lake.id).askRemove()
    assertTrue(asking.confirmingRemove)
    assertFalse(asking.cancelRemove().confirmingRemove)
    assertFalse(asking.select(priya.id).confirmingRemove)
  }

  @Test fun aBookWithoutTheSelectedHomeDropsTheSelection() {
    val s = HomeLinkHouseholdsState(book).select(lake.id).askRemove().withBook(book.remove(lake.id))
    assertNull(s.selected)
    assertFalse(s.confirmingRemove)
    assertEquals(HomeLinkPane.LIST, s.pane(compact = true))
  }

  @Test fun aSyncedEditKeepsTheSelection() {
    val edited = HomeLinkPermissionEdit.call(lake, false)
    val s = HomeLinkHouseholdsState(book).select(lake.id).withBook(book.upsert(edited))
    assertEquals(edited, s.link)
    assertEquals(HomeLinkPane.DETAIL, s.pane(compact = true))
  }

  @Test fun permissionEditsReturnNewLinksAndLeaveTheOriginal() {
    val off = HomeLinkPermissionEdit.call(lake, false)
    assertFalse(off.permissions.canCall)
    assertTrue(lake.permissions.canCall)
    assertFalse(HomeLinkPermissionEdit.availability(lake, false).permissions.seesAvailability)
    val kids = HomeLinkPermissionEdit.room(lake, "Kids’ room", true)
    assertTrue(kids.permissions.rings("Kids’ room"))
    assertFalse(lake.permissions.rings("Kids’ room"))
    assertFalse(HomeLinkPermissionEdit.room(kids, "Kitchen", false).permissions.rings("Kitchen"))
  }

  @Test fun oddRoomNamesAreRefused() {
    assertSame(lake, HomeLinkPermissionEdit.room(lake, "", true))
    assertSame(lake, HomeLinkPermissionEdit.room(lake, "Kitchen\n", true))
  }

  @Test fun roomsToShowKeepThisHomesOrderThenStoredRoomsNoLongerHere() {
    val old = HomeLinkPermissionEdit.room(lake, "Attic", true)
    assertEquals(rooms + "Attic", HomeLinkPermissionEdit.roomsToShow(old, rooms))
    assertEquals(rooms, HomeLinkPermissionEdit.roomsToShow(lake, rooms))
  }

  @Test fun noEditCanTurnOnDropIns() {
    // Permissions have no drop-in field at all: the screen shows a locked row.
    assertFalse(HomeLinkPermissions::class.java.declaredFields.any { it.name.contains("drop", ignoreCase = true) })
  }

  @Test fun wordsMatchTheMock() {
    assertEquals("Safety number checked 17 Nov",
        HomeLinkHouseholdsCopy.checked(1_794_945_600_000L, ZoneId.of("America/Los_Angeles")))
    assertEquals("What can Lakeview do?", HomeLinkHouseholdsCopy.permsTitle("Lakeview"))
    assertEquals("Call Maple House", HomeLinkHouseholdsCopy.call("Maple House"))
    assertEquals("Rooms that ring when Lakeview calls", HomeLinkHouseholdsCopy.roomsThatRing("Lakeview"))
    assertEquals("2 households · all checked", HomeLinkHouseholdsCopy.summary(book))
    assertEquals("1 household · checked", HomeLinkHouseholdsCopy.summary(HomeLinkBook(listOf(lake))))
    assertEquals("No households yet", HomeLinkHouseholdsCopy.summary(HomeLinkBook()))
    assertTrue(HomeLinkHouseholdsCopy.removeBody("Lakeview").contains("every Portal here"))
  }

  @Test fun ringSummaryFollowsThePermissions() {
    assertEquals("Rings 2 rooms", HomeLinkHouseholdsCopy.ringSummary(lake))
    assertEquals("Can’t call", HomeLinkHouseholdsCopy.ringSummary(HomeLinkPermissionEdit.call(lake, false)))
    val one = HomeLinkPermissionEdit.room(lake, "Kitchen", false)
    assertEquals("Rings 1 room", HomeLinkHouseholdsCopy.ringSummary(one))
    assertEquals("Calls ring no rooms", HomeLinkHouseholdsCopy.ringSummary(HomeLinkPermissionEdit.room(one, "Living room", false)))
  }

  @Test fun everyTargetIsAtLeast64dpOnBothSizes() {
    for (d in listOf(HomeLinkDims.PORTAL, HomeLinkDims.MINI)) for (t in d.targets) assertTrue("$t", t.value >= HomeLinkDims.MIN_TARGET_DP)
    for (compact in listOf(true, false)) for (letters in listOf(true, false)) {
      assertTrue(HomeLinkLayout.keyHeight(compact, letters).value >= HomeLinkDims.MIN_TARGET_DP)
    }
  }

  @Test fun portalSizesAreTheMockTokens() {
    val d = HomeLinkDims.PORTAL
    assertEquals(110f, d.rowMin.value)
    assertEquals(96f, d.switchW.value)
    assertEquals(58f, d.switchH.value)
    assertEquals(600f, d.sideWidth.value)
    assertEquals(2, d.roomColumns)
    assertEquals(1, HomeLinkDims.MINI.roomColumns)
    assertSame(HomeLinkDims.MINI, HomeLinkDims.of(compact = true))
  }

  @Test fun aFullCodeFitsTheMiniAndDashesMatchTheDisplay() {
    assertTrue(HomeLinkLayout.slotsWidth(compact = true) <= HomeLinkLayout.MINI_CONTENT_WIDTH)
    val shown = HomeLinkCode.parse("MPT7Q4K2PA")!!.display
    val dashes = shown.withIndex().filter { it.value == '-' }.mapIndexed { n, it -> it.index - n }.toSet()
    assertEquals(dashes, HomeLinkLayout.GROUP_STARTS)
    assertEquals(2, HomeLinkLayout.safetyColumns(compact = true))
    assertEquals(4, HomeLinkLayout.safetyColumns(compact = false))
  }

  @Test fun thisHomesDevicesListThisPortalFirstThenRoomsByNameWithPresence() {
    val now = 1_000_000L
    val peers = listOf(CallPeer("k-1", "Kitchen", seenMs = now), CallPeer("a-1", "Attic", seenMs = 0L))
    val devices = HomeLinkDevices.ofThisHome("Living room", peers, now)
    assertEquals(listOf("Living room", "Attic", "Kitchen"), devices.map { it.name })
    assertEquals(HomeLinkDevices.THIS_ONE, devices[0].detail)
    assertEquals(CallPresence.OFFLINE, devices[1].presence)
    assertEquals(CallPresence.AVAILABLE, devices[2].presence)
  }
  /** After a Portal is removed the identity rotates: links checked under the old key say so instead of their date. */
  @Test fun aLinkCheckedUnderAnOldKeySaysItNeedsCheckingAgain() {
    val zone = ZoneId.of("UTC")
    val s = HomeLinkHouseholdsState(book).withBook(book, setOf(lake.id))
    assertEquals(HomeLinkHouseholdsCopy.KEY_CHANGED, s.checkedLine(lake, zone))
    assertEquals(HomeLinkHouseholdsCopy.checked(priya.verifiedAt, zone), s.checkedLine(priya, zone))
    assertEquals(setOf(lake.id), s.withBook(book.upsert(lake)).reverify)
  }
}
