/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Real routing and Compose state; doubles stop at Android's window, lifecycle and message queue. */
class ApprovedHomeNavigationBoundaryTest {
  @Test fun resumedFocusedHostSelectsRetainedTabWithoutIntentOrLifecycleRoundtrip() = withHost { f ->
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
    assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
    assertEquals(0, f.search.value)
    assertTrue(f.launches.isEmpty())
    assertTrue(Mockito.mockingDetails(f.home).invocations.none {
      it.method.name in setOf("onNewIntent", "onResume", "onPause")
    })
  }

  @Test fun searchSelectsAppsAndIncrementsExactlyOncePerRequest() = withHost { f ->
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.HOME, searchApps = true))
    assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
    assertEquals(1, f.search.value)
    assertTrue(f.home.openApprovedHomeTab(ApprovedLauncherTab.APPS, searchApps = true))
    assertEquals(2, f.search.value)
    assertTrue(f.launches.isEmpty())
  }

  @Test fun pausedUnfocusedDestroyedAndFinishingHostsKeepIntentFallback() = withHost { f ->
    `when`(f.lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
    `when`(f.lifecycle.currentState).thenReturn(Lifecycle.State.RESUMED)
    `when`(f.home.hasWindowFocus()).thenReturn(false)
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
    `when`(f.home.hasWindowFocus()).thenReturn(true)
    `when`(f.home.isDestroyed).thenReturn(true)
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
    `when`(f.home.isDestroyed).thenReturn(false)
    `when`(f.home.isFinishing).thenReturn(true)
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
    assertEquals(4, f.launches.size)
    f.launches.forEach { assertFallback(it, newTask = true) }
    assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
  }

  @Test fun absentHostKeepsServiceAndActivityTaskFlags() = withHost { f ->
    ApprovedHomeNavigation.unavailable(f.home)
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
    assertFallback(f.launches.single(), newTask = true)
    f.launches.clear()
    assertTrue(f.home.openApprovedHomeTab(ApprovedLauncherTab.APPS))
    assertFallback(f.launches.single(), newTask = false)
  }

  @Test fun coldSearchIntentIsConsumedOnceAndKeepsOriginalLaunchFlags() = withHost { f ->
    ApprovedHomeNavigation.unavailable(f.home)
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.HOME, searchApps = true))
    val launch = f.launches.single()
    assertEquals(HomeActivity::class.java, launch.target)
    assertEquals("home", launch.extras["approved_home_tab"])
    assertEquals(true, launch.extras["approved_home_search"])
    assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_NEW_TASK, launch.flags)
    consumeTabIntent(f.home, launch.intent)
    consumeTabIntent(f.home, launch.intent)
    assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
    assertEquals(1, f.search.value)
    assertTrue(launch.extras.isEmpty())
  }

  @Test fun otherActivityCallerKeepsItsTaskInsteadOfMutatingHome() = withHost { f ->
    val other = Mockito.mock(Activity::class.java)
    Mockito.doAnswer { f.launches += f.intents[it.getArgument<Intent>(0)]!!; null }
        .`when`(other).startActivity(Mockito.any(Intent::class.java))
    assertTrue(other.openApprovedHomeTab(ApprovedLauncherTab.APPS))
    assertFallback(f.launches.single(), newTask = false)
    assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
  }

  @Test fun wrappedHomeContextCanSelectTheSameHostDirectly() = withHost { f ->
    val wrapped = Mockito.mock(ContextWrapper::class.java)
    `when`(wrapped.baseContext).thenReturn(f.home)
    assertTrue(wrapped.openApprovedHomeTab(ApprovedLauncherTab.TOOLS))
    assertEquals(ApprovedLauncherTab.TOOLS, f.tab.value)
    Mockito.verify(wrapped, Mockito.never()).startActivity(Mockito.any(Intent::class.java))
    assertTrue(f.launches.isEmpty())
  }

  @Test fun stockHandoffCannotUseTheDirectPathOrChangeSuppression() = withHost { f ->
    DreamPolicy.inStockHandoff = true
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
    assertFallback(f.launches.single(), newTask = true)
    assertTrue(DreamPolicy.inStockHandoff)
    assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
  }

  @Test fun unfocusedHomeRetiresPreviewBeforeFallbackAndConsumesTheIntentOnce() = withHost { f ->
    val preview = Mockito.mock(PhotoFramePreviewActivity::class.java)
    var retired = false
    Mockito.doAnswer {
      assertTrue(DreamPolicy.userExitAt > 0L)
      assertFalse(ImmortalDreamNavigation.hasOwnedSurface())
      assertTrue(f.launches.isEmpty())
      retired = true
      null
    }.`when`(preview).finish()
    Mockito.doAnswer {
      assertTrue("Retirement must precede Home eligibility checking", retired)
      false
    }.`when`(f.home).hasWindowFocus()
    ImmortalDreamNavigation.attached(preview)
    try {
      assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
      assertFallback(f.launches.single(), newTask = true)
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      Mockito.verify(preview, Mockito.times(1)).finish()
      consumeTabIntent(f.home, f.launches.single().intent)
      consumeTabIntent(f.home, f.launches.single().intent)
      assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
      assertEquals(1, f.launches.size)
      Mockito.verify(f.home, Mockito.times(1)).selectApprovedHomeTab(ApprovedLauncherTab.APPS, false)
      Mockito.verify(preview, Mockito.times(1)).finish()
    } finally { ImmortalDreamNavigation.detached(preview) }
  }

  @Test fun focusedHomeSelectsDirectlyAfterOwnedDreamRetirement() = withHost { f ->
    val dream = Mockito.mock(PhotoDreamService::class.java)
    var retired = false
    Mockito.doAnswer {
      assertTrue(DreamPolicy.userExitAt > 0L)
      assertFalse(ImmortalDreamNavigation.hasOwnedSurface())
      retired = true
      null
    }.`when`(dream).finish()
    Mockito.doAnswer {
      assertTrue("Dream retirement must precede focused Home selection", retired)
      true
    }.`when`(f.home).hasWindowFocus()
    ImmortalDreamNavigation.attached(dream)
    try {
      assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
      assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
      assertTrue(f.intents.isEmpty())
      assertTrue(f.launches.isEmpty())
      Mockito.verify(dream, Mockito.times(1)).finish()
      Mockito.verify(f.home, Mockito.times(1)).selectApprovedHomeTab(ApprovedLauncherTab.APPS, false)
      assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.HOME))
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      assertTrue(f.launches.isEmpty())
      Mockito.verify(dream, Mockito.times(1)).finish()
    } finally { ImmortalDreamNavigation.detached(dream) }
  }

  @Test fun focusedHomeSelectsDirectlyAfterOwnedPreviewRetirement() = withHost { f ->
    val preview = Mockito.mock(PhotoFramePreviewActivity::class.java)
    var retired = false
    Mockito.doAnswer {
      assertTrue(DreamPolicy.userExitAt > 0L)
      assertFalse(ImmortalDreamNavigation.hasOwnedSurface())
      retired = true
      null
    }.`when`(preview).finish()
    Mockito.doAnswer {
      assertTrue("Preview retirement must precede focused Home selection", retired)
      true
    }.`when`(f.home).hasWindowFocus()
    ImmortalDreamNavigation.attached(preview)
    try {
      assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
      assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
      assertTrue(f.intents.isEmpty())
      assertTrue(f.launches.isEmpty())
      Mockito.verify(preview, Mockito.times(1)).finish()
      Mockito.verify(f.home, Mockito.times(1)).selectApprovedHomeTab(ApprovedLauncherTab.APPS, false)
    } finally { ImmortalDreamNavigation.detached(preview) }
  }

  @Test fun workerAppliesOnMainAndNeverConstructsAnIntentForDirectSelection() = withHost(onMain = false) { f ->
    Mockito.mockConstruction(Handler::class.java) { handler, _ ->
      Mockito.doAnswer { f.runOnMain(it.getArgument(0)); true }
          .`when`(handler).post(Mockito.any(Runnable::class.java))
    }.use { handlers ->
      assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS, searchApps = true))
      assertEquals(1, handlers.constructed().size)
      assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
      assertEquals(1, f.search.value)
      assertTrue(f.intents.isEmpty())
    }
  }

  @Test fun rejectedWorkerQueueDoesNotSelectOrFallBack() = withHost(onMain = false) { f ->
    Mockito.mockConstruction(Handler::class.java).use {
      assertFalse(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      assertTrue(f.launches.isEmpty())
    }
  }

  @Test fun expiredWorkerQueueCannotSelectOrOpenFallbackAfterReturn() = withHost(onMain = false) { f ->
    var queued: Runnable? = null
    Mockito.mockConstruction(Handler::class.java) { handler, _ ->
      Mockito.doAnswer { queued = it.getArgument(0); true }
          .`when`(handler).post(Mockito.any(Runnable::class.java))
    }.use { handlers ->
      assertFalse(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS, searchApps = true))
      val posted = requireNotNull(queued)
      // A pause while queued must not convert cancellation into a late fallback.
      ApprovedHomeNavigation.unavailable(f.home)
      f.runOnMain(posted)
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      assertEquals(0, f.search.value)
      assertTrue(f.launches.isEmpty())
      Mockito.verify(handlers.constructed().single()).removeCallbacks(posted)
    }
  }

  @Test fun interruptedWorkerQueueCannotNavigateLater() = withHost(onMain = false) { f ->
    var queued: Runnable? = null
    Mockito.mockConstruction(Handler::class.java) { handler, _ ->
      Mockito.doAnswer { queued = it.getArgument(0); true }
          .`when`(handler).post(Mockito.any(Runnable::class.java))
    }.use {
      Thread.currentThread().interrupt()
      try {
        assertFalse(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS, searchApps = true))
        assertTrue(Thread.currentThread().isInterrupted)
      } finally { Thread.interrupted() }
      f.runOnMain(requireNotNull(queued))
      assertEquals(0, f.search.value)
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      assertTrue(f.launches.isEmpty())
    }
  }

  @Test fun hostReplacedDuringFocusValidationFallsBackWithoutSelectingEitherHost() = withHost(onMain = false) { f ->
    val replacement = Mockito.mock(HomeActivity::class.java)
    Mockito.doAnswer {
      ApprovedHomeNavigation.resumed(replacement)
      ApprovedHomeNavigation.unavailable(f.home)
      true
    }.`when`(f.home).hasWindowFocus()
    Mockito.mockConstruction(Handler::class.java) { handler, _ ->
      Mockito.doAnswer { f.runOnMain(it.getArgument(0)); true }
          .`when`(handler).post(Mockito.any(Runnable::class.java))
    }.use { handlers ->
      try {
        assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
        assertEquals(1, handlers.constructed().size)
        assertFallback(f.launches.single(), newTask = true)
        assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
        assertTrue(Mockito.mockingDetails(f.home).invocations.none {
          it.method.name == "selectApprovedHomeTab"
        })
        Mockito.verifyNoInteractions(replacement)
      } finally { ApprovedHomeNavigation.unavailable(replacement) }
    }
  }

  @Test fun lostFocusBeforeMainDispatchFallsBackExactlyOnce() = withHost(onMain = false) { f ->
    Mockito.mockConstruction(Handler::class.java) { handler, _ ->
      Mockito.doAnswer {
        `when`(f.home.hasWindowFocus()).thenReturn(false)
        val posted = it.getArgument<Runnable>(0)
        f.runOnMain(posted)
        f.runOnMain(posted)
        true
      }.`when`(handler).post(Mockito.any(Runnable::class.java))
    }.use {
      assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
      assertFallback(f.launches.single(), newTask = true)
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
    }
  }

  @Test fun admittedWorkerFocusTimeoutIsAcceptedAndCompletesExactlyOnceAfterRelease() = withHost(onMain = false) { f ->
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    var uiWorker: Thread? = null
    var posted: Runnable? = null
    Mockito.doAnswer {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      true
    }.`when`(f.home).hasWindowFocus()
    Mockito.mockConstruction(Handler::class.java) { handler, _ ->
      Mockito.doAnswer {
        posted = it.getArgument(0)
        uiWorker = Thread { f.runOnMain(requireNotNull(posted)) }.apply { start() }
        true
      }.`when`(handler).post(Mockito.any(Runnable::class.java))
    }.use { handlers ->
      try {
        // Focus validation is already inside the manager's admitted navigation closure.
        assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS, searchApps = true))
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
        assertEquals(0, f.search.value)
        assertTrue(f.launches.isEmpty())
      } finally {
        release.countDown()
        requireNotNull(uiWorker).join(2_000)
      }
      assertFalse(requireNotNull(uiWorker).isAlive)
      assertEquals(1, handlers.constructed().size)
      assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
      assertEquals(1, f.search.value)
      f.runOnMain(requireNotNull(posted))
      assertEquals(1, f.search.value)
      Mockito.verify(f.home, Mockito.times(1)).hasWindowFocus()
      Mockito.verify(f.home, Mockito.times(1)).selectApprovedHomeTab(ApprovedLauncherTab.APPS, true)
      assertTrue(f.intents.isEmpty())
      assertTrue(f.launches.isEmpty())
    }
  }

  @Test fun begunWorkerSelectionReturnsAcceptedAndNeverCreatesDuplicateFallback() = withHost(onMain = false) { f ->
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    var uiWorker: Thread? = null
    var posted: Runnable? = null
    var selectedOn: Thread? = null
    Mockito.doAnswer {
      selectedOn = Thread.currentThread()
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      it.callRealMethod()
    }.`when`(f.home).selectApprovedHomeTab(ApprovedLauncherTab.APPS, true)
    Mockito.mockConstruction(Handler::class.java) { handler, _ ->
      Mockito.doAnswer {
        posted = it.getArgument(0)
        uiWorker = Thread { f.runOnMain(requireNotNull(posted)) }.apply { start() }
        true
      }.`when`(handler).post(Mockito.any(Runnable::class.java))
    }.use {
      try {
        assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS, searchApps = true))
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
        assertEquals(0, f.search.value)
      } finally {
        release.countDown()
        requireNotNull(uiWorker).join(2_000)
      }
      assertFalse(requireNotNull(uiWorker).isAlive)
      assertSame(uiWorker, selectedOn)
      assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
      assertEquals(1, f.search.value)
      f.runOnMain(requireNotNull(posted))
      assertEquals(1, f.search.value)
      Mockito.verify(f.home, Mockito.times(1)).selectApprovedHomeTab(ApprovedLauncherTab.APPS, true)
      assertTrue(f.intents.isEmpty())
      assertTrue(f.launches.isEmpty())
    }
  }

  @Test fun completedFallbackFailureDoesNotAttemptAnotherNavigation() = withHost { f ->
    `when`(f.home.hasWindowFocus()).thenReturn(false)
    Mockito.doThrow(SecurityException("denied")).`when`(f.context)
        .startActivity(Mockito.any(Intent::class.java))
    assertFalse(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))
    Mockito.verify(f.context, Mockito.times(1)).startActivity(Mockito.any(Intent::class.java))
    assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
  }

  private fun consumeTabIntent(home: HomeActivity, intent: Intent) {
    HomeActivity::class.java.getDeclaredMethod("consumeLauncherTabIntent", Intent::class.java)
        .apply { isAccessible = true }.invoke(home, intent)
  }

  private data class Launch(val intent: Intent, val target: Any?, var flags: Int = 0,
      val extras: MutableMap<String, Any?> = linkedMapOf())

  private fun assertFallback(launch: Launch, newTask: Boolean) {
    assertEquals(HomeActivity::class.java, launch.target)
    assertEquals("apps", launch.extras["approved_home_tab"])
    assertEquals(false, launch.extras["approved_home_search"])
    assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
        if (newTask) Intent.FLAG_ACTIVITY_NEW_TASK else 0, launch.flags)
  }

  private class Fixture {
    private val callerThread = Thread.currentThread()
    val mainLooper = Mockito.mock(Looper::class.java)
    var callerLooper: Looper? = null

    /** Mockito static mocks are thread-local; model the public main Looper on the executing thread. */
    fun runOnMain(runnable: Runnable) {
      if (Thread.currentThread() === callerThread) {
        val previous = callerLooper
        callerLooper = mainLooper
        try { runnable.run() } finally { callerLooper = previous }
      } else Mockito.mockStatic(Looper::class.java).use { loopers ->
        loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(mainLooper)
        loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(mainLooper)
        runnable.run()
      }
    }

    val home = Mockito.mock(HomeActivity::class.java, Mockito.CALLS_REAL_METHODS)
    val context = Mockito.mock(Context::class.java)
    val lifecycle = Mockito.mock(Lifecycle::class.java)
    val tab = mutableStateOf(ApprovedLauncherTab.HOME)
    val search = mutableStateOf(0)
    val intents = linkedMapOf<Intent, Launch>()
    val launches = mutableListOf<Launch>()
    init {
      // The JVM has no Activity runtime. Initialize only the real Compose state under test;
      // production gets these delegates from HomeActivity's ordinary constructor.
      setDelegate("launcherTabState", tab)
      setDelegate("appsSearchOpenRequest", search)
      Mockito.doReturn(lifecycle).`when`(home).lifecycle
      Mockito.doReturn(true).`when`(home).hasWindowFocus()
      Mockito.doReturn(false).`when`(home).isDestroyed
      Mockito.doReturn(false).`when`(home).isFinishing
      `when`(lifecycle.currentState).thenReturn(Lifecycle.State.RESUMED)
      for (caller in listOf(context, home)) Mockito.doAnswer {
        launches += checkNotNull(intents[it.getArgument<Intent>(0)])
        null
      }.`when`(caller).startActivity(Mockito.any(Intent::class.java))
    }
    private fun setDelegate(name: String, state: MutableState<*>) {
      HomeActivity::class.java.getDeclaredField(name + "$" + "delegate")
          .apply { isAccessible = true }.set(home, state)
    }
  }

  private fun withHost(onMain: Boolean = true, block: (Fixture) -> Unit) {
    val bridge = DreamPolicy.inStockHandoff
    val exit = DreamPolicy.userExitAt
    DreamPolicy.inStockHandoff = false
    val fixture = Fixture()
    fixture.callerLooper = if (onMain) fixture.mainLooper else null
    ApprovedHomeNavigation.resumed(fixture.home)
    try {
      Mockito.mockStatic(Looper::class.java).use { loopers ->
        loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(fixture.mainLooper)
        loopers.`when`<Looper> { Looper.myLooper() }.thenAnswer { fixture.callerLooper }
        Mockito.mockConstruction(Intent::class.java) { intent, construction ->
          val launch = Launch(intent, construction.arguments()[1])
          fixture.intents[intent] = launch
          Mockito.doAnswer { launch.flags = launch.flags or it.getArgument<Int>(0); intent }
              .`when`(intent).addFlags(Mockito.anyInt())
          Mockito.doAnswer { launch.extras[it.getArgument<String>(0)] = it.getArgument(1); intent }
              .`when`(intent).putExtra(Mockito.anyString(), Mockito.anyString())
          Mockito.doAnswer { launch.extras[it.getArgument<String>(0)] = it.getArgument<Boolean>(1); intent }
              .`when`(intent).putExtra(Mockito.anyString(), Mockito.anyBoolean())
          Mockito.doAnswer { launch.extras[it.getArgument<String>(0)] as? String }
              .`when`(intent).getStringExtra(Mockito.anyString())
          Mockito.doAnswer { launch.extras[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
              .`when`(intent).getBooleanExtra(Mockito.anyString(), Mockito.anyBoolean())
          Mockito.doAnswer { launch.extras.remove(it.getArgument<String>(0)); null }
              .`when`(intent).removeExtra(Mockito.anyString())
        }.use { block(fixture) }
      }
    } finally {
      ApprovedHomeNavigation.unavailable(fixture.home)
      DreamPolicy.inStockHandoff = bridge
      DreamPolicy.userExitAt = exit
    }
  }
}
