/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Android boundary doubles; the real recovery handler decides whether to launch a frame. */
class DreamRecoveryOwnershipTest {
    @Test
    fun systemDreamStopWithoutAnImmortalServiceCallbackDoesNotOpenPhotos() {
        withContext { context ->
            // This is Meta's stop after the browser exits: awake, with no recent tap or Calls bridge.
            DreamPolicy.onDreamingStopped(context)
            Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
        }
    }

    @Test
    fun ownedForceWakeRecoversOnceWhileSurroundingSystemBroadcastsNeverLaunch() {
        withContext { context ->
            DreamPolicy.onDreamingStopped(context)
            DreamPolicy.onImmortalDreamingStopped(context)
            DreamPolicy.onDreamingStopped(context)
            Mockito.verify(context, Mockito.times(1)).startActivity(Mockito.any(Intent::class.java))
        }
    }

    @Test
    fun pinnedOriginWithoutAnAccessibilityHostNeverFallsBackToPreviewActivity() {
        withContext { context ->
            DreamPolicy.onImmortalDreamingStopped(context, pinnedOrigin = true)
            Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
        }
    }

    @Test
    fun ownedTapExitDoesNotRecoverOverTheUser() {
        withContext { context ->
            DreamPolicy.userExitAt = System.currentTimeMillis()
            DreamPolicy.onImmortalDreamingStopped(context)
            Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
        }
    }

    @Test
    fun ownedStopDuringCallsHandoffDoesNotRecover() {
        withContext { context ->
            DreamPolicy.inStockHandoff = true
            DreamPolicy.onImmortalDreamingStopped(context)
            Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
        }
    }

    @Test
    fun ownedStopAfterRealSleepLeavesTheScreenAsleep() {
        withContext(interactive = false) { context ->
            DreamPolicy.onImmortalDreamingStopped(context)
            Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
        }
    }

    @Test
    fun disabledScreensaverNeverRecoversEvenFromAnOwnedCallback() {
        withContext(enabled = false) { context ->
            DreamPolicy.onImmortalDreamingStopped(context)
            Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
        }
    }

    private fun withContext(
        enabled: Boolean = true,
        interactive: Boolean = true,
        action: (Context) -> Unit,
    ) {
        val prefs = ScreensaverTestPrefs(mapOf("enabled" to enabled))
        val context = prefs.context
        val power = Mockito.mock(PowerManager::class.java)
        `when`(power.isInteractive).thenReturn(interactive)
        `when`(context.getSystemService(PowerManager::class.java)).thenReturn(power)
        `when`(context.applicationContext).thenReturn(context)
        val previous = Triple(DreamPolicy.userExitAt, DreamPolicy.bridgeAt, DreamPolicy.inStockHandoff)
        try {
            DreamPolicy.userExitAt = 0L
            DreamPolicy.bridgeAt = 0L
            DreamPolicy.inStockHandoff = false
            Mockito.mockStatic(Log::class.java).use {
                Mockito.mockConstruction(Intent::class.java,
                    Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
                    action(context)
                }
            }
        } finally {
            DreamPolicy.userExitAt = previous.first
            DreamPolicy.bridgeAt = previous.second
            DreamPolicy.inStockHandoff = previous.third
        }
    }
}
