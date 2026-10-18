/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.AtomicFile
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`

/** Real authenticated routes, navigation admission and retained state; Android owns the idle signal. */
@Suppress("DEPRECATION")
class RemoteNavigationActivityTest {
  @Test fun authenticatedHomeSelectionRenewsNativeIdleAfterSelectingRetainedHome() = withFixture { f ->
    f.tab.value = ApprovedLauncherTab.APPS
    f.onAcquire = { assertEquals(ApprovedLauncherTab.HOME, f.tab.value) }
    assertSuccessful(f.key("home"))
    assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
    assertTrue(f.launches.isEmpty())
    assertIdlePulse(f)
  }

  @Test fun authenticatedAppsSelectionRenewsNativeIdleAfterSelectingRetainedApps() = withFixture { f ->
    f.onAcquire = { assertEquals(ApprovedLauncherTab.APPS, f.tab.value) }
    assertSuccessful(f.key("apps"))
    assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
    assertEquals(0, f.search.value)
    assertTrue(f.launches.isEmpty())
    assertIdlePulse(f)
  }

  @Test fun authenticatedHomeColdFallbackRenewsNativeIdleAfterLaunchingHome() = withFixture(retained = false) { f ->
    f.onAcquire = { assertHomeLaunch(f, "home") }
    assertSuccessful(f.key("home"))
    assertHomeLaunch(f, "home")
    assertIdlePulse(f)
  }

  @Test fun authenticatedAppsColdFallbackRenewsNativeIdleAfterLaunchingHomeApps() = withFixture(retained = false) { f ->
    f.onAcquire = { assertHomeLaunch(f, "apps") }
    assertSuccessful(f.key("apps"))
    assertHomeLaunch(f, "apps")
    assertIdlePulse(f)
  }

  @Test fun authenticatedAppLaunchRenewsNativeIdleAfterAndroidAcceptsTheIntent() = withFixture { f ->
    f.onAcquire = { assertEquals(listOf(f.appIntent), f.launches.toList()) }
    assertSuccessful(f.launch())
    assertEquals(listOf(f.appIntent), f.launches.toList())
    Mockito.verify(f.appIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    assertIdlePulse(f)
  }

  @Test fun authenticatedDashboardLaunchRenewsNativeIdleAfterOpeningTheBrowser() = withFixture { f ->
    f.onAcquire = { assertEquals(1, f.launches.size) }
    assertSuccessful(f.key("dashboard"))
    val launch = f.intents.getValue(f.launches.single())
    assertSame(f.normalizedDashboardUri, launch.target)
    Mockito.verify(f.dashboardUri).normalizeScheme()
    assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, launch.flags)
    assertIdlePulse(f)
  }

  @Test fun noninteractiveNavigationUsesTheExistingBoundedScreenWake() = withFixture { f ->
    `when`(f.power.isInteractive).thenReturn(false)
    f.onAcquire = { assertEquals(ApprovedLauncherTab.APPS, f.tab.value) }
    assertSuccessful(f.key("apps"))
    assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
    assertEquals(1, f.locks.size)
    assertEquals(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
        PowerManager.ON_AFTER_RELEASE, f.locks.single().flags)
    assertEquals("immortal:wake", f.locks.single().tag)
    Mockito.verify(f.wakeLock, Mockito.times(1)).acquire(3_000L)
    Mockito.verify(f.wakeLock, Mockito.never()).acquire()
    Mockito.verify(f.wakeLock, Mockito.never()).release()
  }

  @Test fun unavailableAppCannotRetireTheFrameOrPulseNativeIdle() = withFixture { f ->
    val preview = Mockito.mock(PhotoFramePreviewActivity::class.java)
    ImmortalDreamNavigation.attached(preview)
    val response = f.request("POST", "/remote/launch", JSONObject().put("packageName", "example.missing"))
    assertEquals(404, response.status)
    assertFalse(response.json().getBoolean("ok"))
    assertTrue(ImmortalDreamNavigation.hasOwnedSurface())
    assertEquals(0L, DreamPolicy.userExitAt)
    Mockito.verify(preview, Mockito.never()).finish()
    assertTrue(f.launches.isEmpty())
    assertNoIdleSignal(f)
  }

  @Test fun throwingAppLaunchReportsFailureWithoutPulsingNativeIdle() = withFixture { f ->
    Mockito.doThrow(SecurityException("Synthetic launch denial")).`when`(f.context)
        .startActivity(Mockito.any(Intent::class.java))
    val response = f.launch()
    assertEquals(404, response.status)
    assertFalse(response.json().getBoolean("ok"))
    Mockito.verify(f.context, Mockito.times(1)).startActivity(f.appIntent)
    assertNoIdleSignal(f)
  }

  @Test fun throwingColdHomeAndAppsLaunchesCannotPulseNativeIdle() = withFixture(retained = false) { f ->
    Mockito.doThrow(SecurityException("Synthetic Home denial")).`when`(f.context)
        .startActivity(Mockito.any(Intent::class.java))
    for (action in listOf("home", "apps")) {
      val response = f.key(action)
      assertEquals(action, 500, response.status)
      assertFalse(response.json().getBoolean("ok"))
    }
    Mockito.verify(f.context, Mockito.times(2)).startActivity(Mockito.any(Intent::class.java))
    assertNoIdleSignal(f)
  }

  @Test fun throwingDashboardLaunchReportsFailureWithoutPulsingNativeIdle() = withFixture { f ->
    val toast = Mockito.mock(Toast::class.java)
    Mockito.mockStatic(Toast::class.java).use { toasts ->
      toasts.`when`<Toast> {
        Toast.makeText(Mockito.eq(f.context), Mockito.any(CharSequence::class.java), Mockito.eq(Toast.LENGTH_LONG))
      }.thenReturn(toast)
      Mockito.doThrow(SecurityException("Synthetic browser denial")).`when`(f.context)
          .startActivity(Mockito.any(Intent::class.java))
      val response = f.key("dashboard")
      assertEquals(503, response.status)
      assertFalse(response.json().getBoolean("ok"))
      Mockito.verify(toast).show()
      assertNoIdleSignal(f)
    }
  }

  @Test fun authenticatedPinAndAppsPollingCannotRenewNativeIdle() = withFixture { f ->
    // Empty inventory keeps this polling check independent of Build.DEVICE curation.
    `when`(f.packages.queryIntentActivities(Mockito.any(Intent::class.java), Mockito.eq(0))).thenReturn(emptyList())
    repeat(3) {
      val pin = f.request("GET", "/remote/pin")
      assertSuccessful(pin)
      assertFalse(pin.json().getBoolean("pinned"))
      val apps = f.request("GET", "/remote/apps")
      assertSuccessful(apps)
      assertEquals(0, apps.json().getJSONArray("apps").length())
    }
    assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
    assertTrue(f.launches.isEmpty())
    assertNoIdleSignal(f)
  }

  @Test fun unauthenticatedNavigationCannotSelectLaunchOrPulseNativeIdle() = withFixture { f ->
    for (action in listOf("home", "apps", "dashboard")) {
      assertEquals(401, f.request("POST", "/remote/key", JSONObject().put("action", action), authorized = false).status)
    }
    assertEquals(401, f.request("POST", "/remote/launch",
        JSONObject().put("packageName", "example.player"), authorized = false).status)
    assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
    assertTrue(f.launches.isEmpty())
    assertNoIdleSignal(f)
  }

  @Test fun cancelledActualQueuedNavigationCannotPulseEvenWhenItsQueueIsDrained() = withFixture(onMain = false) { f ->
    val queued = mutableListOf<PinnedMainRequest>()
    Mockito.mockConstruction(Handler::class.java) { handler, _ ->
      Mockito.doAnswer {
        val request = it.getArgument<Runnable>(0) as PinnedMainRequest
        queued += request
        // Cancel the real request while still queued, without waiting for a wall-clock timeout.
        assertEquals("main_timeout", request.await(0).getString("error"))
        true
      }.`when`(handler).post(Mockito.any(Runnable::class.java))
    }.use {
      assertEquals(500, f.key("home").status)
      assertEquals(500, f.key("apps").status)
      assertEquals(404, f.launch().status)
      assertEquals(503, f.key("dashboard").status)
      assertEquals(4, queued.size)
      assertNoIdleSignal(f)
      f.callerOnMain = true
      queued.forEach { it.run(); it.run() }
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      assertTrue(f.launches.isEmpty())
      assertNoIdleSignal(f)
    }
  }

  @Test fun startedRetainedSelectionPulsesOnceOnMainAfterIntentionalExitAndSelection() = withFixture(onMain = false) { f ->
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val preview = Mockito.mock(PhotoFramePreviewActivity::class.java)
    Mockito.doAnswer {
      assertTrue("Intentional exit must precede the frame's finish callback", DreamPolicy.userExitAt > 0L)
      assertFalse(ImmortalDreamNavigation.hasOwnedSurface())
      assertNoIdleSignal(f)
      f.events += "finish"
      null
    }.`when`(preview).finish()
    ImmortalDreamNavigation.attached(preview)
    Mockito.doAnswer {
      assertEquals(listOf("finish"), f.events.toList())
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      true
    }.`when`(f.home).hasWindowFocus()
    f.onAcquire = {
      assertTrue(DreamPolicy.userExitAt > 0L)
      assertFalse(ImmortalDreamNavigation.hasOwnedSurface())
      assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
      assertSame(Looper.getMainLooper(), Looper.myLooper())
    }
    withStartedRequest(f, entered, release) { posted, uiWorker ->
      assertSuccessful(f.key("apps")) // Started work is accepted even while focus validation is blocked.
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      assertNoIdleSignal(f)
      assertTrue("Admission preserves the interrupted HTTP waiter flag", Thread.interrupted())
      release.countDown()
      uiWorker().join(2_000)
      assertFalse(uiWorker().isAlive)
      assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
      assertTrue(f.launches.isEmpty())
      assertIdlePulse(f)
      assertSame(uiWorker(), f.locks.single().thread)
      assertEquals(listOf("finish", "lock", "acquire", "release"), f.events.toList())
      f.callerOnMain = true
      posted().run()
      assertIdlePulse(f)
      Mockito.verify(preview, Mockito.times(1)).finish()
      Mockito.verify(f.home, Mockito.times(1)).hasWindowFocus()
    }
  }

  @Test fun startedAppLaunchPulsesOnceOnMainOnlyAfterTheLaunchReturns() = withFixture(onMain = false) { f ->
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    f.beforeLaunch = {
      entered.countDown()
      check(release.await(5, TimeUnit.SECONDS))
    }
    f.onAcquire = {
      assertEquals(listOf(f.appIntent), f.launches.toList())
      assertSame(Looper.getMainLooper(), Looper.myLooper())
    }
    withStartedRequest(f, entered, release) { posted, uiWorker ->
      val response = f.launch()
      assertEquals(202, response.status)
      assertTrue(response.json().getBoolean("ok"))
      assertEquals("completion_unconfirmed", response.json().getString("result"))
      assertTrue(f.launches.isEmpty())
      assertNoIdleSignal(f)
      assertTrue("Admission preserves the interrupted HTTP waiter flag", Thread.interrupted())
      release.countDown()
      uiWorker().join(2_000)
      assertFalse(uiWorker().isAlive)
      assertEquals(listOf(f.appIntent), f.launches.toList())
      assertIdlePulse(f)
      assertSame(uiWorker(), f.locks.single().thread)
      assertEquals(listOf("launch", "lock", "acquire", "release"), f.events.toList())
      f.callerOnMain = true
      posted().run()
      assertIdlePulse(f)
      Mockito.verify(f.context, Mockito.times(1)).startActivity(f.appIntent)
    }
  }

  @Test fun powerServiceFailureCannotChangeSuccessfulRetainedNavigationResponse() = withFixture { f ->
    `when`(f.context.getSystemService(PowerManager::class.java)).thenThrow(SecurityException("Synthetic power denial"))
    assertSuccessful(f.key("apps"))
    assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
    assertTrue(f.launches.isEmpty())
    Mockito.verify(f.context, Mockito.times(1)).getSystemService(PowerManager::class.java)
  }

  @Test fun wakeLockAcquireFailureCannotChangeSuccessfulAppLaunchResponse() = withFixture { f ->
    Mockito.doThrow(SecurityException("Synthetic wake-lock denial")).`when`(f.wakeLock).acquire(1_000L)
    assertSuccessful(f.launch())
    assertEquals(listOf(f.appIntent), f.launches.toList())
    assertEquals(1, f.locks.size)
    Mockito.verify(f.wakeLock, Mockito.times(1)).acquire(1_000L)
    Mockito.verify(f.wakeLock, Mockito.never()).release()
  }

  @Test fun acquireFailureAfterTakingTheLockStillReleasesItWithoutUndoingNavigation() = withFixture { f ->
    Mockito.doAnswer {
      f.wakeHeld = true
      throw SecurityException("Synthetic failure after acquire")
    }.`when`(f.wakeLock).acquire(1_000L)
    assertSuccessful(f.launch())
    assertEquals(listOf(f.appIntent), f.launches.toList())
    assertIdlePulse(f)
    assertFalse(f.wakeHeld)
  }

  @Test fun alreadyExpiredLockIsNotReleasedAgainAfterSuccessfulNavigation() = withFixture { f ->
    Mockito.doAnswer { f.wakeHeld = false; null }.`when`(f.wakeLock).acquire(1_000L)
    assertSuccessful(f.launch())
    assertEquals(listOf(f.appIntent), f.launches.toList())
    assertEquals(1, f.locks.size)
    Mockito.verify(f.wakeLock, Mockito.times(1)).acquire(1_000L)
    Mockito.verify(f.wakeLock, Mockito.never()).release()
  }

  @Test fun wakeLockReleaseFailureCannotChangeSuccessfulDashboardResponse() = withFixture { f ->
    Mockito.doThrow(IllegalStateException("Synthetic release failure")).`when`(f.wakeLock).release()
    assertSuccessful(f.key("dashboard"))
    assertEquals(1, f.launches.size)
    assertIdlePulse(f)
  }

  @Test fun authenticatedCompanionHomeOperationRenewsNativeIdleAfterRetainedSelection() = withFixture { f ->
    withOperations(f) { operations ->
      f.tab.value = ApprovedLauncherTab.APPS
      val result = directOperation(f, operations, "home")
      assertTrue(result.getBoolean("ok"))
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      assertTrue(f.launches.isEmpty())
      assertIdlePulse(f)
    }
  }

  @Test fun authenticatedCompanionAppsOperationRenewsNativeIdleAfterRetainedSelection() = withFixture { f ->
    withOperations(f) { operations ->
      val result = directOperation(f, operations, "apps")
      assertTrue(result.getBoolean("ok"))
      assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
      assertTrue(f.launches.isEmpty())
      assertIdlePulse(f)
    }
  }

  @Test fun authenticatedCompanionLaunchOperationRenewsNativeIdleAfterLaunchingApp() = withFixture { f ->
    withOperations(f) { operations ->
      val result = directOperation(f, operations, "launch", "example.player")
      assertTrue(result.getBoolean("ok"))
      assertEquals(listOf(f.appIntent), f.launches.toList())
      assertIdlePulse(f)
    }
  }

  @Test fun sharedCompanionActionsDefaultToNavigationWithoutAnIdlePulse() = withFixture { f ->
    withOperations(f) { operations ->
      for (action in listOf("apps", "home", "launch")) {
        val body = JSONObject().put("action", action)
        if (action == "launch") body.put("packageName", "example.player")
        assertTrue(operations.action(body).getBoolean("ok"))
      }
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      assertEquals(listOf(f.appIntent), f.launches.toList())
      assertNoIdleSignal(f)
    }
  }

  @Test fun scheduledSceneNavigationDoesNotRenewNativeIdle() = withFixture { f ->
    withOperations(f) { operations ->
      val scene = JSONObject().put("id", "background-navigation").put("name", "Background navigation")
          .put("steps", JSONArray()
              .put(JSONObject().put("type", "key").put("action", "apps"))
              .put(JSONObject().put("type", "key").put("action", "home"))
              .put(JSONObject().put("type", "launch").put("packageName", "example.player")))
      val operation = checkNotNull(RemoteScenes.start(f.context, scene, scheduled = true))
      val completed = checkNotNull(operations.history.find(operation.getString("id")))
      assertEquals("succeeded", completed.getString("state"))
      assertEquals(3, completed.getJSONArray("results").length())
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      assertEquals(listOf(f.appIntent), f.launches.toList())
      assertNoIdleSignal(f)
    }
  }

  private fun assertSuccessful(response: FleetHttpServer.Response) {
    assertEquals(200, response.status)
    assertTrue(response.json().getBoolean("ok"))
  }

  private fun assertIdlePulse(f: Fixture) {
    // Production deliberately catches callback failures; assert captured ordering on this thread.
    f.acquireAssertions.firstOrNull()?.let { throw it }
    assertEquals("Successful user navigation must renew Android's native idle activity once", 1, f.locks.size)
    assertEquals(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, f.locks.single().flags)
    assertEquals("immortal:remote-navigation", f.locks.single().tag)
    Mockito.verify(f.wakeLock, Mockito.times(1)).acquire(1_000L)
    Mockito.verify(f.wakeLock, Mockito.never()).acquire()
    Mockito.verify(f.wakeLock, Mockito.times(1)).release()
  }

  private fun assertNoIdleSignal(f: Fixture) {
    assertTrue(f.locks.isEmpty())
    Mockito.verifyNoInteractions(f.power, f.wakeLock)
  }

  private fun assertHomeLaunch(f: Fixture, tab: String) {
    val launch = f.intents.getValue(f.launches.single())
    assertEquals(HomeActivity::class.java, launch.target)
    assertEquals(tab, launch.extras["approved_home_tab"])
    assertEquals(false, launch.extras["approved_home_search"])
    assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
        Intent.FLAG_ACTIVITY_SINGLE_TOP, launch.flags)
  }

  /** Drive the posted admission, then interrupt its waiter only after Android's action has started. */
  private fun withStartedRequest(f: Fixture, entered: CountDownLatch, release: CountDownLatch,
      block: (() -> Runnable, () -> Thread) -> Unit) {
    var posted: Runnable? = null
    var uiWorker: Thread? = null
    val wasInterrupted = Thread.currentThread().isInterrupted
    assertFalse("The fixture requires an uninterrupted HTTP worker", wasInterrupted)
    Mockito.mockConstruction(Handler::class.java) { handler, _ ->
      Mockito.doAnswer {
        posted = it.getArgument(0)
        uiWorker = Thread { f.runOnMain(checkNotNull(posted)) }.apply { start() }
        check(entered.await(2, TimeUnit.SECONDS))
        Thread.currentThread().interrupt()
        true
      }.`when`(handler).post(Mockito.any(Runnable::class.java))
    }.use {
      try { block({ checkNotNull(posted) }, { checkNotNull(uiWorker) }) }
      finally {
        Thread.interrupted()
        release.countDown()
        uiWorker?.join(2_000)
        assertFalse("The admitted Android action must not outlive its fixture", uiWorker?.isAlive == true)
        if (wasInterrupted) Thread.currentThread().interrupt()
      }
    }
  }

  private fun directOperation(f: Fixture, operations: RemoteCompanionOperations,
      action: String, packageName: String? = null): JSONObject {
    val routes = RemoteCompanionRoutes(f.context)
    // Start with a warmed real route cache: catalog refresh is unrelated to action navigation.
    field(routes, "catalog").set(routes, emptyList<CatalogApp>())
    val body = JSONObject().put("type", "action").put("action", action)
    if (packageName != null) body.put("packageName", packageName)
    val response = f.request("POST", "/remote/operation", body, handle = routes::handle)
    assertEquals(202, response.status)
    assertTrue(response.json().getBoolean("ok"))
    val id = response.json().getJSONObject("operation").getString("id")
    val poll = f.request("GET", "/remote/operations", handle = routes::handle)
    assertSuccessful(poll)
    val completed = checkNotNull(operations.history.find(id))
    assertEquals(id, poll.json().getJSONArray("operations").getJSONObject(0).getString("id"))
    assertEquals("succeeded", completed.getString("state"))
    assertEquals(1, completed.getJSONArray("results").length())
    return completed.getJSONArray("results").getJSONObject(0)
  }

  private fun withOperations(f: Fixture, block: (RemoteCompanionOperations) -> Unit) {
    // Keep real action execution and history; replace only storage, worker and deadline boundaries.
    val fixtureFile = Files.createTempFile("public-navigation-store", ".json").toFile().apply { writeText("{}") }
    try {
    val stream = Mockito.mock(FileOutputStream::class.java)
    Mockito.mockConstruction(AtomicFile::class.java) { file, _ ->
      `when`(file.openRead()).thenAnswer { FileInputStream(fixtureFile) }
      `when`(file.startWrite()).thenReturn(stream)
    }.use {
      val worker = Mockito.mock(ExecutorService::class.java)
      Mockito.doAnswer { it.getArgument<Runnable>(0).run(); null }.`when`(worker)
          .execute(Mockito.any(Runnable::class.java))
      Mockito.mockStatic(Executors::class.java).use { executors ->
        executors.`when`<ExecutorService> {
          Executors.newSingleThreadExecutor(Mockito.any(ThreadFactory::class.java))
        }.thenReturn(worker)
        Mockito.mockConstruction(ScheduledThreadPoolExecutor::class.java) { scheduler, _ ->
          val future = Mockito.mock(ScheduledFuture::class.java)
          `when`(scheduler.schedule(Mockito.any(Runnable::class.java), Mockito.anyLong(), Mockito.eq(TimeUnit.MINUTES)))
              .thenReturn(future)
        }.use {
          @Suppress("UNCHECKED_CAST")
          val instances = RemoteCompanionOperations::class.java.getDeclaredField("instances")
              .apply { isAccessible = true }.get(null) as MutableMap<String, RemoteCompanionOperations>
          val saved = instances.remove(f.context.packageName)
          try { block(RemoteCompanionOperations.get(f.context)) }
          finally {
            instances.remove(f.context.packageName)
            if (saved != null) instances[f.context.packageName] = saved
          }
        }
      }
    }
    } finally { fixtureFile.delete() }
  }

  private data class Launch(val target: Any?, var flags: Int = 0,
      val extras: MutableMap<String, Any?> = linkedMapOf())
  private data class Lock(val flags: Int, val tag: String, val thread: Thread)

  private class Fixture(val mainLooper: Looper, var callerOnMain: Boolean) {
    val context = Mockito.mock(Context::class.java)
    val packages = Mockito.mock(PackageManager::class.java)
    val dashboard = Mockito.mock(SharedPreferences::class.java)
    val power = Mockito.mock(PowerManager::class.java)
    val wakeLock = Mockito.mock(PowerManager.WakeLock::class.java)
    val home = Mockito.mock(HomeActivity::class.java, Mockito.CALLS_REAL_METHODS)
    val lifecycle = Mockito.mock(Lifecycle::class.java)
    val tab = mutableStateOf(ApprovedLauncherTab.HOME)
    val search = mutableStateOf(0)
    val appIntent = Mockito.mock(Intent::class.java, Mockito.RETURNS_SELF)
    val dashboardUri = Mockito.mock(Uri::class.java)
    val normalizedDashboardUri = Mockito.mock(Uri::class.java)
    val intents = linkedMapOf<Intent, Launch>()
    val launches = CopyOnWriteArrayList<Intent>()
    val locks = CopyOnWriteArrayList<Lock>()
    val events = CopyOnWriteArrayList<String>()
    val acquireAssertions = CopyOnWriteArrayList<Throwable>()
    @Volatile var wakeHeld = false
    var beforeLaunch: () -> Unit = {}
    var onAcquire: () -> Unit = {}
    val routes = RemoteRoutes(context)
    init {
      `when`(dashboardUri.normalizeScheme()).thenReturn(normalizedDashboardUri)
      setDelegate("launcherTabState", tab)
      setDelegate("appsSearchOpenRequest", search)
      Mockito.doReturn(lifecycle).`when`(home).lifecycle
      Mockito.doReturn(true).`when`(home).hasWindowFocus()
      Mockito.doReturn(false).`when`(home).isDestroyed
      Mockito.doReturn(false).`when`(home).isFinishing
      `when`(lifecycle.currentState).thenReturn(Lifecycle.State.RESUMED)
      val pairing = Mockito.mock(SharedPreferences::class.java)
      val fleet = Mockito.mock(SharedPreferences::class.java)
      `when`(context.getSharedPreferences("remote_pairing", Context.MODE_PRIVATE)).thenReturn(pairing)
      `when`(context.getSharedPreferences("fleet_agent", Context.MODE_PRIVATE)).thenReturn(fleet)
      `when`(context.getSharedPreferences("immortal_home_dashboard", Context.MODE_PRIVATE)).thenReturn(dashboard)
      `when`(pairing.getBoolean("enabled", false)).thenReturn(true)
      `when`(pairing.getString("sessions", null)).thenReturn("synthetic-navigation-session")
      `when`(fleet.getString("token", null)).thenReturn("synthetic-navigation-fleet-token")
      `when`(dashboard.getString("url", "")).thenReturn("https://dashboard.invalid/")
      `when`(context.packageManager).thenReturn(packages)
      `when`(context.packageName).thenReturn("com.immortal.launcher.navigationtest")
      `when`(context.applicationContext).thenReturn(context)
      `when`(context.noBackupFilesDir).thenReturn(File("/synthetic/remote-navigation"))
      `when`(packages.getLaunchIntentForPackage("example.player")).thenReturn(appIntent)
      `when`(context.getSystemService(PowerManager::class.java)).thenReturn(power)
      `when`(context.getSystemService(Context.POWER_SERVICE)).thenReturn(power)
      `when`(power.isInteractive).thenReturn(true)
      `when`(wakeLock.isHeld).thenAnswer { wakeHeld }
      Mockito.doAnswer {
        beforeLaunch()
        launches += it.getArgument<Intent>(0)
        events += "launch"
        null
      }.`when`(context).startActivity(Mockito.any(Intent::class.java))
      Mockito.doAnswer {
        locks += Lock(it.getArgument(0), it.getArgument(1), Thread.currentThread())
        events += "lock"
        wakeLock
      }.`when`(power).newWakeLock(Mockito.anyInt(), Mockito.anyString())
      Mockito.doAnswer {
        events += "acquire"
        wakeHeld = true
        runCatching { onAcquire() }.exceptionOrNull()?.let { acquireAssertions += it }
        null
      }.`when`(wakeLock).acquire(Mockito.anyLong())
      Mockito.doAnswer { events += "release"; wakeHeld = false; null }.`when`(wakeLock).release()
    }
    private fun setDelegate(name: String, state: MutableState<*>) {
      HomeActivity::class.java.getDeclaredField(name + "$" + "delegate")
          .apply { isAccessible = true }.set(home, state)
    }
    fun runOnMain(runnable: Runnable) {
      Mockito.mockStatic(Looper::class.java).use { loopers ->
        loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(mainLooper)
        loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(mainLooper)
        runnable.run()
      }
    }
    fun key(action: String) = request("POST", "/remote/key", JSONObject().put("action", action))
    fun launch() = request("POST", "/remote/launch", JSONObject().put("packageName", "example.player"))
    fun request(method: String, path: String, body: JSONObject = JSONObject(), authorized: Boolean = true,
        handle: (FleetHttpServer.Request) -> FleetHttpServer.Response = routes::handle): FleetHttpServer.Response {
      val bytes = body.toString().toByteArray(Charsets.UTF_8)
      return handle(FleetHttpServer.Request(method, path, "",
          if (authorized) mapOf("authorization" to "Bearer synthetic-navigation-session") else emptyMap(),
          ByteArrayInputStream(bytes), bytes.size))
    }
  }

  private class Globals {
    private val objects = listOf(
        QuickBar to listOf("host", "wm", "view", "barVisible", "switcher", "pinButton",
            "pinAvailable", "pinned", "frameCovered"),
        PinnedAppScreensaver to listOf("owner", "expiredPin"),
        ImmortalDreamNavigation to listOf("active", "preview"),
    )
    private val saved = objects.flatMap { (owner, names) -> names.map { name ->
      Triple(owner, field(owner, name), field(owner, name).get(owner))
    } }
    private val registry = field(ApprovedHomeNavigation, "hosts").get(ApprovedHomeNavigation)
    private val registration = field(registry, "live").get(registry)
    private val stockHandoff = DreamPolicy.inStockHandoff
    private val userExit = DreamPolicy.userExitAt
    fun isolate() {
      for ((owner, names) in objects) for (name in names) {
        val slot = field(owner, name)
        slot.set(owner, when {
          slot.type == Boolean::class.javaPrimitiveType -> false
          name == "active" || name == "preview" -> WeakReference<Any>(null)
          else -> null
        })
      }
      field(registry, "live").set(registry, WeakReference<HomeActivity>(null))
      DreamPolicy.inStockHandoff = false
      DreamPolicy.userExitAt = 0L
    }
    fun restore() {
      saved.forEach { (owner, slot, value) -> slot.set(owner, value) }
      field(registry, "live").set(registry, registration)
      DreamPolicy.inStockHandoff = stockHandoff
      DreamPolicy.userExitAt = userExit
    }
  }

  private fun withFixture(retained: Boolean = true, onMain: Boolean = true, block: (Fixture) -> Unit) {
    val mainLooper = Mockito.mock(Looper::class.java)
    Mockito.mockStatic(Looper::class.java).use { loopers ->
      loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(mainLooper)
      loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(mainLooper)
      // Initialize QuickBar with a neutral Handler before installing any fixture queue callbacks.
      val globals = Mockito.mockConstruction(Handler::class.java).use { Globals() }
      try {
        globals.isolate()
        val f = Fixture(mainLooper, onMain)
        loopers.`when`<Looper> { Looper.myLooper() }.thenAnswer { if (f.callerOnMain) mainLooper else null }
        if (retained) ApprovedHomeNavigation.resumed(f.home)
        Mockito.mockConstruction(Intent::class.java,
            Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { intent, construction ->
          val launch = Launch(construction.arguments().getOrNull(1))
          f.intents[intent] = launch
          Mockito.doAnswer { launch.flags = launch.flags or it.getArgument<Int>(0); intent }
              .`when`(intent).addFlags(Mockito.anyInt())
          Mockito.doAnswer { launch.extras[it.getArgument<String>(0)] = it.getArgument(1); intent }
              .`when`(intent).putExtra(Mockito.anyString(), Mockito.anyString())
          Mockito.doAnswer { launch.extras[it.getArgument<String>(0)] = it.getArgument<Boolean>(1); intent }
              .`when`(intent).putExtra(Mockito.anyString(), Mockito.anyBoolean())
        }.use {
          Mockito.mockStatic(Uri::class.java).use { uris ->
            uris.`when`<Uri> { Uri.parse(Mockito.anyString()) }.thenReturn(f.dashboardUri)
            block(f)
            // Also cover bounded-wake cases that do not use assertIdlePulse.
            f.acquireAssertions.firstOrNull()?.let { throw it }
          }
        }
      } finally { globals.restore() }
    }
  }

  private fun FleetHttpServer.Response.json() = JSONObject(String(checkNotNull(jsonBytes), Charsets.UTF_8))
  private companion object {
    fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
  }
}
