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
 * The system Back key on the pairing and Link homes test screens steps back one screen instead of
 * closing Calls: each reducer says where Back goes, or null when it should leave that screen.
 */
class PortalPinBackTest {
  private val random = SecureRandom()
  private val invite = HomeLinkInvite(HomeLinkCode.generate(random, HomeLinkDomain.PORTAL_PIN), 0L, HomeLinkInvite.PIN_LIFETIME_MS)

  @Test fun backFromThePairedListLeavesTheScreen() {
    assertNull(PortalPinFlowState().back())
  }

  @Test fun backFirstClosesAnOpenRemoveConfirm() {
    val asking = PortalPinFlowState().askRemove("0123456789abcdef")
    assertEquals(PortalPinFlowState(), asking.back())
  }

  @Test fun backFromEveryPairingStepReturnsToTheList() {
    val peer = HomeLinkPeer("Office", HomeLinkIdentity.generate(random).publicKey, "office-1")
    val steps = listOf(
        PortalPinFlowState().showCode(invite),
        PortalPinFlowState().typeCode(),
        PortalPinFlowState().typeCode().typed(HomeLinkCodeEntry(invite.code.value, HomeLinkDomain.PORTAL_PIN)).joining(),
        PortalPinFlowState().copy(stage = PortalPinStage.VERIFY, peer = peer),
        PortalPinFlowState().copy(stage = PortalPinStage.REPAIR, peer = peer),
        PortalPinFlowState().copy(stage = PortalPinStage.DONE, peer = peer),
        PortalPinFlowState().failed(HomeLinkFailure.MISMATCH),
    )
    assertEquals(PortalPinStage.entries.toSet() - PortalPinStage.LIST, steps.map { it.stage }.toSet())
    for (s in steps) assertEquals(s.stage.name, PortalPinFlowState(), s.back())
  }

  @Test fun theLinkHomesTestStepsBackOneScreen() {
    assertNull(HomeLinkTestNav.back(HomeLinkTestStage.HOUSEHOLDS))
    assertEquals(HomeLinkTestStage.INVITE, HomeLinkTestNav.back(HomeLinkTestStage.JOIN))
    for (s in HomeLinkTestStage.entries - HomeLinkTestStage.HOUSEHOLDS - HomeLinkTestStage.JOIN)
      assertEquals(s.name, HomeLinkTestStage.HOUSEHOLDS, HomeLinkTestNav.back(s))
  }

  @Test fun backOnHouseholdsClosesTheConfirmThenTheMiniDetailThenLeaves() {
    val link = HomeLink("0123456789abcdef", "Lakeview", HomeLinkIdentity.generate(random).publicKey, 1L,
        HomeLinkPermissions.defaults(listOf("Kitchen"), emptySet()))
    val open = HomeLinkHouseholdsState(HomeLinkBook(listOf(link))).select(link.id)
    val confirming = open.askRemove()
    assertEquals(open, confirming.backOrNull(compact = false))
    assertEquals(open.back(), open.backOrNull(compact = true))
    assertNull(open.backOrNull(compact = false))
    assertNull(open.back().backOrNull(compact = true))
  }
}
