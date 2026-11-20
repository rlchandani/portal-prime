/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mock-fidelity CA-1: a room card shows only what the room will take. */
class CallsRoomActionsTest {
  @Test fun dropInsOffLeavesVideoAndAudio_noGreyedPill() {
    val a = CallRoomActions.of(acceptsCalls = true, drop = DropInPolicy.OFF)
    assertEquals(listOf(CallRoomAction.VIDEO, CallRoomAction.AUDIO), a)
    assertFalse(CallRoomAction.DROP_IN in a)
    assertEquals(CallRoomActions.Note.DROP_INS_OFF, CallRoomActions.note(true, DropInPolicy.OFF))
    assertEquals("Drop-ins off", CallRoomActions.Note.DROP_INS_OFF.text)
  }

  @Test fun allowedDropInsAreOffered() {
    assertEquals(listOf(CallRoomAction.VIDEO, CallRoomAction.DROP_IN, CallRoomAction.AUDIO), CallRoomActions.of(true, DropInPolicy.CHIME))
    assertEquals(listOf(CallRoomAction.VIDEO, CallRoomAction.DROP_IN, CallRoomAction.AUDIO), CallRoomActions.of(true, DropInPolicy.ASK))
    assertEquals("Drop in", CallRoomActions.dropInLabel(DropInPolicy.CHIME))
    assertEquals("Ask to join", CallRoomActions.dropInLabel(DropInPolicy.ASK))
    assertEquals(CallRoomActions.Note.DROP_INS_ON, CallRoomActions.note(true, DropInPolicy.CHIME))
    assertEquals(CallRoomActions.Note.ASKS_FIRST, CallRoomActions.note(true, DropInPolicy.ASK))
  }

  @Test fun callsOffOffersNothing_andSaysSo() {
    DropInPolicy.entries.forEach { assertTrue(CallRoomActions.of(acceptsCalls = false, drop = it).isEmpty()) }
    assertEquals(CallRoomActions.Note.CALLS_OFF, CallRoomActions.note(false, DropInPolicy.CHIME))
  }
}
