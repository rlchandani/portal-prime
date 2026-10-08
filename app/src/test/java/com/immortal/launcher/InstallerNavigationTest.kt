/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Android boundary doubles; exercise the real headless receiver and dream recovery policy. */
class InstallerNavigationTest {
  @get:Rule val files = TemporaryFolder()

  @Test fun pendingConfirmationFinishesOwnedDreamBeforeShowingInstallerWithoutRecoveringPhotos() {
    withInstaller { context, confirmation, install ->
      val dream = Mockito.mock(PhotoDreamService::class.java)
      val events = mutableListOf<String>()
      var exitAtFinish = 0L
      var armedAtConfirmation = false
      Mockito.doAnswer {
        exitAtFinish = DreamPolicy.userExitAt
        events += "dream_finished"
        DreamPolicy.onImmortalDreamingStopped(context)
        null
      }.`when`(dream).finish()
      Mockito.doAnswer {
        if (it.getArgument<Intent>(0) === confirmation) armedAtConfirmation = InstallAutoConfirm.isArmed()
        events += if (it.getArgument<Intent>(0) === confirmation) "installer_shown" else "photos_reopened"
        null
      }.`when`(context).startActivity(Mockito.any(Intent::class.java))
      ImmortalDreamNavigation.attached(dream)
      try {
        assertTrue(install())
        assertEquals(listOf("dream_finished", "installer_shown"), events)
        assertTrue("Intentional exit must be recorded before finish()", exitAtFinish > 0L)
        assertTrue("Confirmation must be shown while auto-confirm is armed", armedAtConfirmation)
        assertFalse("Terminal completion must disarm auto-confirm", InstallAutoConfirm.isArmed())
        Mockito.verify(dream, Mockito.times(1)).finish()
        Mockito.verify(confirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Mockito.verify(context, Mockito.times(1)).startActivity(confirmation)
      } finally { ImmortalDreamNavigation.detached(dream) }
    }
  }

  @Test fun confirmationWithoutOwnedDreamDoesNotMarkExitOrRecoverOverMetaDreamStop() {
    withInstaller { context, confirmation, install ->
      assertTrue(install())
      assertEquals(0L, DreamPolicy.userExitAt)
      DreamPolicy.onDreamingStopped(context)
      Mockito.verify(context, Mockito.times(1)).startActivity(Mockito.any(Intent::class.java))
      Mockito.verify(context).startActivity(confirmation)
    }
  }

  @Test fun ownedDreamExitForConfirmationPreservesCallsHandoffSuppression() {
    withInstaller { context, confirmation, install ->
      DreamPolicy.bridgeAt = 123L
      DreamPolicy.inStockHandoff = true
      DreamPolicy.portalHomeHandoff = true
      val dream = Mockito.mock(PhotoDreamService::class.java)
      Mockito.doAnswer {
        DreamPolicy.onImmortalDreamingStopped(context)
        null
      }.`when`(dream).finish()
      ImmortalDreamNavigation.attached(dream)
      try {
        assertTrue(install())
        assertEquals(123L, DreamPolicy.bridgeAt)
        assertTrue(DreamPolicy.inStockHandoff)
        assertTrue(DreamPolicy.portalHomeHandoff)
        Mockito.verify(dream).finish()
        Mockito.verify(context, Mockito.times(1)).startActivity(Mockito.any(Intent::class.java))
        Mockito.verify(context).startActivity(confirmation)
      } finally { ImmortalDreamNavigation.detached(dream) }
    }
  }

  @Test fun installWithoutVisibleConfirmationLeavesOwnedDreamRunning() {
    withInstaller(pendingUserAction = false) { context, _, install ->
      val dream = Mockito.mock(PhotoDreamService::class.java)
      ImmortalDreamNavigation.attached(dream)
      try {
        assertTrue(install())
        assertEquals(0L, DreamPolicy.userExitAt)
        Mockito.verify(dream, Mockito.never()).finish()
        Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
      } finally { ImmortalDreamNavigation.detached(dream) }
    }
  }

  @Suppress("DEPRECATION")
  private fun withInstaller(
      pendingUserAction: Boolean = true,
      action: (Context, Intent, () -> Boolean) -> Unit,
  ) {
    val previousExit = DreamPolicy.userExitAt
    val previousBridge = DreamPolicy.bridgeAt
    val previousHandoff = DreamPolicy.inStockHandoff
    val previousPortalHome = DreamPolicy.portalHomeHandoff
    val context = ScreensaverTestPrefs(mapOf("enabled" to true)).context
    val power = Mockito.mock(PowerManager::class.java)
    `when`(power.isInteractive).thenReturn(true)
    `when`(context.getSystemService(PowerManager::class.java)).thenReturn(power)
    `when`(context.applicationContext).thenReturn(context)
    `when`(context.packageName).thenReturn("com.immortal.launcher")
    val packages = Mockito.mock(PackageManager::class.java)
    val installer = Mockito.mock(PackageInstaller::class.java)
    val session = Mockito.mock(PackageInstaller.Session::class.java)
    `when`(context.packageManager).thenReturn(packages)
    `when`(packages.packageInstaller).thenReturn(installer)
    `when`(installer.createSession(Mockito.any(PackageInstaller.SessionParams::class.java))).thenReturn(37)
    `when`(installer.openSession(37)).thenReturn(session)
    val apk = files.newFile("fixture.apk").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    `when`(session.openWrite("base.apk", 0L, apk.length())).thenReturn(ByteArrayOutputStream())
    var receiver: BroadcastReceiver? = null
    Mockito.doAnswer {
      receiver = it.getArgument(0)
      null
    }.`when`(context).registerReceiver(Mockito.any(BroadcastReceiver::class.java), Mockito.any(IntentFilter::class.java))
    val confirmation = Mockito.mock(Intent::class.java, Mockito.RETURNS_SELF)
    val pendingResult = Mockito.mock(Intent::class.java)
    `when`(pendingResult.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE))
        .thenReturn(PackageInstaller.STATUS_PENDING_USER_ACTION)
    `when`(pendingResult.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)).thenReturn(confirmation)
    val terminalResult = Mockito.mock(Intent::class.java)
    `when`(terminalResult.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE))
        .thenReturn(PackageInstaller.STATUS_SUCCESS)
    val sender = Mockito.mock(IntentSender::class.java)
    val pending = Mockito.mock(PendingIntent::class.java)
    `when`(pending.intentSender).thenReturn(sender)
    Mockito.doAnswer {
      if (pendingUserAction) requireNotNull(receiver).onReceive(context, pendingResult)
      requireNotNull(receiver).onReceive(context, terminalResult)
      null
    }.`when`(session).commit(sender)
    try {
      DreamPolicy.userExitAt = 0L
      DreamPolicy.bridgeAt = 0L
      DreamPolicy.inStockHandoff = false
      DreamPolicy.portalHomeHandoff = false
      Mockito.mockStatic(Log::class.java).use {
        Mockito.mockStatic(SystemClock::class.java).use {
          Mockito.mockConstruction(IntentFilter::class.java).use {
            Mockito.mockConstruction(PackageInstaller.SessionParams::class.java).use {
              Mockito.mockConstruction(Intent::class.java,
                  Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)).use {
                Mockito.mockStatic(PendingIntent::class.java).use { intents ->
                  intents.`when`<PendingIntent> {
                    PendingIntent.getBroadcast(Mockito.eq(context), Mockito.eq(37),
                        Mockito.any(Intent::class.java), Mockito.anyInt())
                  }.thenReturn(pending)
                  action(context, confirmation) {
                    HeadlessInstaller.install(context, apk, "org.y20k.transistor", timeoutMs = 1)
                  }
                }
              }
            }
          }
        }
      }
    } finally {
      DreamPolicy.userExitAt = previousExit
      DreamPolicy.bridgeAt = previousBridge
      DreamPolicy.inStockHandoff = previousHandoff
      DreamPolicy.portalHomeHandoff = previousPortalHome
    }
  }
}
