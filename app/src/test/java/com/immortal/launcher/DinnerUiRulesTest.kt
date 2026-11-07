/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

/** Small UI rules behind the sheet and the card. */
class DinnerUiRulesTest {
  @Test fun typingStopsAtFortyCodePoints() {
    assertEquals("x".repeat(40), DinnerInput.limit("x".repeat(45)))
    assertEquals("🍕".repeat(40), DinnerInput.limit("🍕".repeat(41)))
    assertEquals("a b", DinnerInput.limit("a\nb"))
  }

  @Test fun longTitlesStepDown() {
    assertTrue(DinnerCopy.titleSize("Bedtime") > DinnerCopy.titleSize("Come downstairs now"))
    assertTrue(DinnerCopy.titleSize("Come downstairs now") > DinnerCopy.titleSize("Lights out in 5 minutes, everyone"))
  }

  @Test fun pingToastCopy() {
    val rows = listOf(DinnerRow("la", "Living Area", DinnerStatus.SENDING))
    val send = DinnerSend("a1b2c3d4", DinnerMessage.ping(), "Family Area", false, 0L, rows)
    assertEquals("Pinging Living Area…", DinnerCopy.pingToast(send))
    assertEquals("Pinged Living Area", DinnerCopy.pingToast(send.copy(rows = listOf(rows[0].copy(status = DinnerStatus.CHIMED)))))
    assertEquals("Pinged Living Area, shown quietly", DinnerCopy.pingToast(send.copy(rows = listOf(rows[0].copy(status = DinnerStatus.QUIET)))))
    assertEquals("Couldn’t reach Living Area", DinnerCopy.pingToast(send.copy(rows = listOf(rows[0].copy(status = DinnerStatus.NO_ANSWER)))))
  }

  @Test fun sendJsonCarriesRowsAndReplies() {
    val send = DinnerSend("a1b2c3d4", DinnerMessage.preset("dinner")!!, "Rohit’s Pixel", true, 5L,
        listOf(DinnerRow("la", "Living Area", DinnerStatus.SPOKEN, DinnerReply.COMING)))
    val json = send.toJson()
    assertEquals("Dinner’s ready", json.getString("text"))
    val row = json.getJSONArray("rows").getJSONObject(0)
    assertEquals("spoken", row.getString("status"))
    assertEquals("Coming", row.getString("reply"))
    assertFalse(json.getBoolean("pending"))
  }
}
