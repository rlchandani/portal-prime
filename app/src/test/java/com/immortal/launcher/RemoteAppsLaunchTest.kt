/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

class RemoteAppsLaunchTest {
  @Test fun ownedDreamEndsBeforeSelectedAppLaunchAndCannotRecoverOverIt() {
    withLaunch { context, _, dream, events ->
      Mockito.doAnswer {
        assertTrue("Intentional exit must be recorded before finish", DreamPolicy.userExitAt > 0L)
        events += "dream_finished"
        null
      }.`when`(dream).finish()
      assertTrue(RemoteApps.launch(context, "com.portal.assistant"))
      assertEquals(listOf("dream_finished", "app_started"), events)
      Mockito.verify(dream).finish()
      assertFalse(ImmortalDreamNavigation.dismissForNavigation())
    }
  }

  @Test fun launchWithoutOwnedDreamDoesNotMarkMetaDreamAsUserExit() {
    withLaunch(ownedDream = false) { context, _, dream, events ->
      assertTrue(RemoteApps.launch(context, "com.portal.assistant"))
      assertEquals(listOf("app_started"), events)
      assertEquals(0L, DreamPolicy.userExitAt)
      Mockito.verify(dream, Mockito.never()).finish()
    }
  }

  @Test fun missingAppLeavesScreensaverRunning() {
    withLaunch(launchable = false) { context, _, dream, events ->
      assertFalse(RemoteApps.launch(context, "com.missing.app"))
      assertTrue(events.isEmpty())
      assertEquals(0L, DreamPolicy.userExitAt)
      Mockito.verify(dream, Mockito.never()).finish()
    }
  }

  @Test fun leanbackOnlyAppUsesTheSameDreamHandoff() {
    withLaunch(leanbackOnly = true) { context, intent, dream, events ->
      Mockito.doAnswer { events += "dream_finished"; null }.`when`(dream).finish()
      assertTrue(RemoteApps.launch(context, "com.portal.assistant"))
      assertEquals(listOf("dream_finished", "app_started"), events)
      Mockito.verify(context).startActivity(intent)
      Mockito.verify(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
  }

  @Test fun appStartFailureIsReportedAsFailure() {
    withLaunch { context, _, _, _ ->
      Mockito.doThrow(SecurityException("Synthetic launch denial")).`when`(context)
          .startActivity(Mockito.any(Intent::class.java))
      assertFalse(RemoteApps.launch(context, "com.portal.assistant"))
    }
  }

  @Test fun workerRequestWaitsForMainDreamExitBeforeLaunching() {
    withLaunch(onMain = false) { context, _, dream, events ->
      Mockito.doAnswer { events += "dream_finished"; null }.`when`(dream).finish()
      Mockito.mockConstruction(Handler::class.java) { handler, _ ->
        Mockito.doAnswer {
          events += "main_post"
          it.getArgument<Runnable>(0).run()
          true
        }.`when`(handler).post(Mockito.any(Runnable::class.java))
      }.use {
        assertTrue(RemoteApps.launch(context, "com.portal.assistant"))
        assertEquals(listOf("main_post", "dream_finished", "app_started"), events)
      }
    }
  }

  @Test fun rejectedMainDispatchLeavesDreamAndAppUntouched() {
    withLaunch(onMain = false) { context, _, dream, events ->
      Mockito.mockConstruction(Handler::class.java).use {
        assertFalse(RemoteApps.launch(context, "com.portal.assistant"))
        assertTrue(events.isEmpty())
        assertEquals(0L, DreamPolicy.userExitAt)
        Mockito.verify(dream, Mockito.never()).finish()
      }
    }
  }

  @Test fun timedOutQueuedLaunchCannotOpenAfterCallerRetries() {
    withLaunch(onMain = false) { context, _, dream, events ->
      var queued: Runnable? = null
      Mockito.mockConstruction(Handler::class.java) { handler, _ ->
        Mockito.doAnswer {
          queued = it.getArgument(0)
          true
        }.`when`(handler).post(Mockito.any(Runnable::class.java))
      }.use { handlers ->
        assertFalse(RemoteApps.launch(context, "com.portal.assistant"))
        assertTrue(events.isEmpty())
        assertEquals(0L, DreamPolicy.userExitAt)
        // Even if the queue races removeCallbacks, the cancelled FutureTask is inert.
        val posted = requireNotNull(queued)
        posted.run()
        assertTrue(events.isEmpty())
        assertEquals(0L, DreamPolicy.userExitAt)
        Mockito.verify(dream, Mockito.never()).finish()
        Mockito.verify(handlers.constructed().single()).removeCallbacks(posted)
      }
    }
  }

  @Test fun runningDreamDismissalCannotLaunchAppAfterRequestTimesOut() {
    withLaunch(onMain = false) { context, _, dream, events ->
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      var uiWorker: Thread? = null
      Mockito.doAnswer {
        entered.countDown()
        check(release.await(5, TimeUnit.SECONDS))
        events += "dream_finished"
        null
      }.`when`(dream).finish()
      Mockito.mockConstruction(Handler::class.java) { handler, _ ->
        Mockito.doAnswer {
          val runnable = it.getArgument<Runnable>(0)
          uiWorker = Thread { runnable.run() }.apply { start() }
          true
        }.`when`(handler).post(Mockito.any(Runnable::class.java))
      }.use {
        try {
          assertFalse(RemoteApps.launch(context, "com.portal.assistant"))
          assertTrue(entered.await(1, TimeUnit.SECONDS))
          Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
        } finally {
          release.countDown()
          requireNotNull(uiWorker).join(2_000)
        }
        assertEquals(listOf("dream_finished"), events)
        Mockito.verify(context, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
      }
    }
  }

  private fun withLaunch(
      ownedDream: Boolean = true,
      launchable: Boolean = true,
      leanbackOnly: Boolean = false,
      onMain: Boolean = true,
      check: (Context, Intent, PhotoDreamService, MutableList<String>) -> Unit,
  ) {
    val originalExit = DreamPolicy.userExitAt
    val context = Mockito.mock(Context::class.java)
    val packages = Mockito.mock(PackageManager::class.java)
    val intent = Mockito.mock(Intent::class.java, Mockito.RETURNS_SELF)
    val dream = Mockito.mock(PhotoDreamService::class.java)
    val main = Mockito.mock(Looper::class.java)
    val events = mutableListOf<String>()
    `when`(context.packageManager).thenReturn(packages)
    if (launchable) {
      if (leanbackOnly) `when`(packages.getLeanbackLaunchIntentForPackage("com.portal.assistant")).thenReturn(intent)
      else `when`(packages.getLaunchIntentForPackage("com.portal.assistant")).thenReturn(intent)
    }
    Mockito.doAnswer { events += "app_started"; null }.`when`(context)
        .startActivity(Mockito.any(Intent::class.java))
    DreamPolicy.userExitAt = 0L
    if (ownedDream) ImmortalDreamNavigation.attached(dream)
    try {
      Mockito.mockStatic(Looper::class.java).use { loopers ->
        loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
        if (onMain) loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(main)
        check(context, intent, dream, events)
      }
    } finally {
      ImmortalDreamNavigation.detached(dream)
      DreamPolicy.userExitAt = originalExit
    }
  }
}
