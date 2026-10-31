/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class NightSettingsChangesTest {
  @Test fun oneValidatedBatchSendsOnePackageScopedUpdate() {
    val context = Mockito.mock(Context::class.java)
    Mockito.`when`(context.packageName).thenReturn("com.immortal.launcher.debug")
    Mockito.mockConstruction(Intent::class.java) { intent, _ ->
      Mockito.`when`(intent.setPackage(Mockito.anyString())).thenReturn(intent)
    }.use { intents ->
      assertTrue(NightSettingsChanges.notifyApplied(context,
          setOf("darkRoomClock", "darkRoomLux", "nightClockBrightness", "nightClockTone")))
      assertEquals(1, intents.constructed().size)
      val intent = intents.constructed().single()
      Mockito.verify(intent).setPackage("com.immortal.launcher.debug")
      Mockito.verify(context).sendBroadcast(intent)
    }
  }

  @Test fun unrelatedSettingsStartNoBroadcastOrObserverWork() {
    val context = Mockito.mock(Context::class.java)
    assertFalse(NightSettingsChanges.notifyApplied(context, setOf("intervalSec", "calendarUrl")))
    Mockito.verifyNoInteractions(context)
  }
}
