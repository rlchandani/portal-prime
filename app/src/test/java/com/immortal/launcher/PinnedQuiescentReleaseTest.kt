/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import java.lang.ref.WeakReference
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer

/** Real release, window readers, retirement and Home routing; mocks stop at Android boundaries. */
@Suppress("DEPRECATION") // Portal pools accessibility windows and roots.
class PinnedQuiescentReleaseTest {
  @Test fun hiddenBarWithoutPinCanReleaseRepeatedlyWithoutAcquiringWindowsOrRoots() = withHost { f ->
    f.setHostField("label", "Old label")
    f.setHostField("nativeDetachGraceUntil", 900L)
    val revision = f.policy.revision

    repeat(3) { f.host.release() }

    f.assertObservations(0)
    f.assertReleased()
    assertEquals(revision + 3L, f.policy.revision)
    assertEquals(0L, f.hostField("nativeDetachGraceUntil"))
    assertTrue(f.scheduled.isEmpty())
    Mockito.verifyNoInteractions(f.wm)
  }

  @Test fun hiddenColdHomeFallbackAndRepeatedIntentConsumptionAcquireNoWindowsOrRoots() = withHost { f ->
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.HOME, searchApps = true))
    val launch = f.launches.single()
    assertEquals(HomeActivity::class.java, launch.target)
    assertEquals("home", launch.extras[APPROVED_HOME_TAB])
    assertEquals(true, launch.extras[APPROVED_HOME_SEARCH])
    assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_NEW_TASK, launch.flags)
    assertEquals(ApprovedLauncherTab.HOME, f.tab.value)

    f.consume(launch.intent)
    f.consume(launch.intent)

    assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
    assertEquals(1, f.search.value)
    assertTrue(launch.extras.isEmpty())
    assertEquals(1, f.launches.size)
    Mockito.verify(f.home, Mockito.times(1)).selectApprovedHomeTab(ApprovedLauncherTab.HOME, true)
    f.assertObservations(0)
    f.assertReleased()
  }

  @Test fun hiddenFocusedHomeSelectionStillRunsNavigationWithoutAnAccessibilityRead() = withHost { f ->
    Mockito.doReturn(true).`when`(f.home).hasWindowFocus()
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.TOOLS))

    assertEquals(ApprovedLauncherTab.TOOLS, f.tab.value)
    f.assertObservations(0)
    assertTrue(f.launches.isEmpty())
    assertTrue(f.intents.isEmpty())
    Mockito.verify(f.home).selectApprovedHomeTab(ApprovedLauncherTab.TOOLS, false)
    f.assertReleased()
  }

  @Test fun visibleBarReleasePreservesFreshEligibilityObservation() = withHost { f ->
    QuickBar.setBarVisible(true)
    f.host.release()

    f.assertObservations(1)
    assertTrue(QuickBar.pinControlStatus().getBoolean("available"))
    assertFalse(QuickBar.pinControlStatus().getBoolean("selected"))
    assertFalse(QuickBar.pinControlStatus().getBoolean("frameCovered"))
  }

  @Test fun visibleBarStillObservesWhenTheActualHomeFallbackLaunchFails() = withHost { f ->
    QuickBar.setBarVisible(true)
    Mockito.doThrow(SecurityException("Synthetic launch denial")).`when`(f.context)
        .startActivity(Mockito.any(Intent::class.java))

    assertFalse(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))

    Mockito.verify(f.context, Mockito.times(1)).startActivity(Mockito.any(Intent::class.java))
    assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
    assertEquals(0, f.search.value)
    f.assertObservations(1)
    assertTrue(QuickBar.pinControlStatus().getBoolean("available"))
    assertFalse(f.host.pinned)
  }

  @Test fun hiddenReleaseClearsOldEligibilityBeforeBarRevealReadsTheNewForeground() = withHost { f ->
    f.host.windowsChanged()
    assertTrue(QuickBar.pinControlStatus().getBoolean("available"))
    f.foregroundPackage = "com.immortal.launcher"

    f.host.release()

    // Hidden release must discard old eligibility, even when the old app is still observable.
    assertFalse(QuickBar.pinControlStatus().getBoolean("available"))
    f.assertObservations(1)
    QuickBar.setBarVisible(true)
    assertFalse(QuickBar.pinControlStatus().getBoolean("available"))
    f.host.windowsChanged()
    assertFalse(QuickBar.pinControlStatus().getBoolean("available"))
    f.foregroundPackage = "com.synthetic.newforeground"
    f.host.windowsChanged()
    assertTrue(QuickBar.pinControlStatus().getBoolean("available"))
    f.assertObservations(3)
    assertEquals(listOf("com.synthetic.foreground", "com.immortal.launcher",
        "com.synthetic.newforeground"), f.observations.map { it.first().packageName })
  }

  @Test fun hiddenReleaseInvalidatesEligibilityEvenIfTheSameAppRemainsInFront() = withHost { f ->
    f.host.windowsChanged()
    assertTrue(QuickBar.pinControlStatus().getBoolean("available"))

    f.host.release()

    assertFalse(QuickBar.pinControlStatus().getBoolean("available"))
    f.assertObservations(1)
    QuickBar.setBarVisible(true)
    f.host.windowsChanged()
    assertTrue(QuickBar.pinControlStatus().getBoolean("available"))
    f.assertObservations(2)
  }

  @Test fun livePinReleaseStillObservesAfterClearingTheSession() = withHost { f ->
    assertTrue(f.policy.pin(PinnedWindowIdentity(f.foregroundPackage, 41)))
    QuickBar.setPinState(false, true, false)

    f.host.release()

    f.assertObservations(1)
    f.assertSessionCleared()
    assertTrue(QuickBar.pinControlStatus().getBoolean("available"))
  }

  @Test fun lostPinReleaseStillObservesAfterClearingTheLostGuard() = withHost { f ->
    assertTrue(f.policy.pin(PinnedWindowIdentity(f.foregroundPackage, 41)))
    val replacement = PinnedWindowIdentity(f.foregroundPackage, 42)
    assertFalse(f.policy.observe(listOf(replacement), replacement, covered = true))
    assertTrue(f.policy.lost)
    QuickBar.setPinState(false, true, true)

    f.host.release()

    f.assertObservations(1)
    f.assertSessionCleared()
    assertTrue(QuickBar.pinControlStatus().getBoolean("available"))
  }

  @Test fun expiredSessionWithoutTargetStillObservesOnRelease() = withHost { f ->
    f.policy.expire(null)
    assertNull(f.policy.target)
    assertTrue(f.host.pinned)
    QuickBar.setPinState(false, true, false)

    f.host.release()

    f.assertObservations(1)
    f.assertSessionCleared()
    assertTrue(QuickBar.pinControlStatus().getBoolean("available"))
  }

  @Test fun nativeCoverWithoutRemainingPinStillRequiresObservationAndClearsNativeState() = withHost { f ->
    assertTrue(f.policy.pin(PinnedWindowIdentity(f.foregroundPackage, 41)))
    f.host.nativeDreamAttached()
    f.policy.release() // Window observation may clear the pin before native ownership detaches.
    f.setHostField("nativeDetachGraceUntil", 900L)
    assertTrue(f.hostField("nativeCover") as Boolean)
    assertFalse(f.host.pinned)

    f.host.release()

    f.assertObservations(1)
    f.assertSessionCleared()
    assertFalse(f.hostField("nativeCover") as Boolean)
    assertEquals(0L, f.hostField("nativeDetachGraceUntil"))
    assertFalse(QuickBar.pinControlStatus().getBoolean("frameCovered"))
  }

  @Test fun coverWithoutRemainingPinIsHiddenAndRetiredBeforeTheRequiredObservation() = withHost { f ->
    val cover = f.cover()
    f.setHostField("surface", cover.value)
    QuickBar.setPinState(false, false, true)

    f.host.release()

    assertFalse(f.host.covered)
    Mockito.verify(cover.root).setVisibility(View.GONE)
    assertEquals(listOf("hide", "windows"), f.events)
    assertFalse(cover.renderer.stopped)
    assertEquals(1, f.retirement.pendingCount)
    Mockito.verify(f.wm, Mockito.never()).removeViewImmediate(Mockito.any(View::class.java))
    f.assertObservations(1)

    f.runScheduled(100L)
    assertTrue(cover.renderer.stopped)
    assertEquals(0, f.retirement.pendingCount)
    Mockito.verify(f.wm).removeViewImmediate(cover.root)
    Mockito.verify(cover.root).removeOnAttachStateChangeListener(Mockito.any(View.OnAttachStateChangeListener::class.java))
    assertTrue(f.scheduled.isEmpty())
  }

  @Test fun pendingRevealWithoutCurrentCoverIsCancelledBeforeRequiredObservation() = withHost { f ->
    val cover = f.cover()
    val request = f.pendingReveal(cover)
    f.setHostField("pendingReveal", request)
    f.handler.postDelayed(request, 50L)
    assertFalse(f.host.pinned)
    assertFalse(f.host.covered)

    f.host.release()

    assertNull(f.hostField("pendingReveal"))
    Mockito.verify(f.handler).removeCallbacks(request)
    assertFalse(f.scheduled.containsKey(request))
    assertEquals(listOf("cancelReveal", "windows"), f.events)
    assertEquals(0, f.retirement.pendingCount)
    assertFalse(cover.renderer.stopped)
    Mockito.verify(cover.root, Mockito.never()).setVisibility(Mockito.anyInt())
    f.assertObservations(1)
  }

  @Test fun pendingRetirementKeepsRepeatedReleaseObservableUntilCleanupCompletes() = withHost { f ->
    val cover = f.cover()
    f.setHostField("surface", cover.value)
    f.host.release()
    assertEquals(1, f.retirement.pendingCount)

    f.host.release()

    f.assertObservations(2)
    assertFalse(f.host.pinned)
    assertFalse(f.host.covered)
    assertFalse(cover.renderer.stopped)
    Mockito.verify(cover.root, Mockito.times(1)).setVisibility(View.GONE)
    f.runScheduled(100L)
    assertTrue(cover.renderer.stopped)
    assertEquals(0, f.retirement.pendingCount)
    Mockito.verify(f.wm, Mockito.times(1)).removeViewImmediate(cover.root)
  }

  @Test fun exhaustedRetirementStillRequiresObservationAndRetainsItsCapturedCleanupOwner() = withHost { f ->
    val cover = f.cover()
    f.removalFails = true
    f.setHostField("surface", cover.value)
    f.host.release()
    f.runScheduled(100L)
    f.runScheduled(200L)
    f.runScheduled(400L)
    assertEquals(1, f.retirement.pendingCount)
    assertEquals(1, f.retirement.failedCount)
    assertFalse(f.retirement.canCreateCover)
    assertTrue(cover.renderer.stopped)
    assertTrue(f.scheduled.isEmpty())

    f.host.release()

    f.assertObservations(2)
    assertEquals(1, f.retirement.pendingCount)
    assertEquals(1, f.retirement.failedCount)
    assertEquals(1, f.cleanups().size)
    assertTrue(f.scheduled.isEmpty())
    Mockito.verify(f.wm, Mockito.times(3)).removeViewImmediate(cover.root)
    Mockito.verify(cover.root, Mockito.never()).removeOnAttachStateChangeListener(
        Mockito.any(View.OnAttachStateChangeListener::class.java))
  }

  @Test fun closedHostStillClearsAndRetiresOwnershipWithoutObservingWindows() = withHost { f ->
    val cover = f.cover()
    assertTrue(f.policy.pin(PinnedWindowIdentity(f.foregroundPackage, 41)))
    f.setHostField("surface", cover.value)
    f.setHostField("nativeCover", true)
    val request = f.pendingReveal(cover)
    f.setHostField("pendingReveal", request)

    f.host.close()
    f.host.release()

    f.assertObservations(0)
    f.assertSessionCleared()
    assertNull(f.hostField("pendingReveal"))
    assertFalse(f.hostField("nativeCover") as Boolean)
    Mockito.verify(f.handler).removeCallbacks(request)
    Mockito.verify(cover.root, Mockito.times(1)).setVisibility(View.GONE)
    assertEquals(1, f.retirement.pendingCount)
    f.runScheduled(100L)
    assertEquals(0, f.retirement.pendingCount)
    assertTrue(cover.renderer.stopped)
  }

  @Test fun ownedDreamKeepsHiddenReleaseObservableWithoutFinishingIt() = withHost { f ->
    val dream = Mockito.mock(PhotoDreamService::class.java)
    ImmortalDreamNavigation.attached(dream)

    f.host.release()

    f.assertObservations(1)
    assertTrue(ImmortalDreamNavigation.hasOwnedSurface())
    Mockito.verify(dream, Mockito.never()).finish()
  }

  @Test fun ownedPreviewKeepsHiddenReleaseObservableWithoutFinishingIt() = withHost { f ->
    val preview = Mockito.mock(PhotoFramePreviewActivity::class.java)
    ImmortalDreamNavigation.attached(preview)

    f.host.release()

    f.assertObservations(1)
    assertTrue(ImmortalDreamNavigation.hasOwnedSurface())
    Mockito.verify(preview, Mockito.never()).finish()
  }

  @Test fun firstNavigationAdmissionRetiresDreamAndPreviewBeforeHomeFallbackEvenWhenLaunchFails() = withHost { f ->
    val dream = Mockito.mock(PhotoDreamService::class.java)
    val preview = Mockito.mock(PhotoFramePreviewActivity::class.java)
    ImmortalDreamNavigation.attached(dream)
    ImmortalDreamNavigation.attached(preview)
    val finish = Answer<Any?> {
      assertFalse(ImmortalDreamNavigation.hasOwnedSurface())
      assertTrue(DreamPolicy.userExitAt > 0L)
      assertTrue(f.launches.isEmpty())
      f.events += "finish"
      null
    }
    Mockito.doAnswer(finish).`when`(dream).finish()
    Mockito.doAnswer(finish).`when`(preview).finish()
    Mockito.doAnswer {
      assertEquals(listOf("windows", "finish", "finish"), f.events)
      throw SecurityException("Synthetic launch denial")
    }.`when`(f.context).startActivity(Mockito.any(Intent::class.java))

    assertFalse(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS))

    f.assertObservations(1)
    Mockito.verify(dream).finish()
    Mockito.verify(preview).finish()
    assertFalse(ImmortalDreamNavigation.hasOwnedSurface())
    assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
  }

  @Test fun homeIntentConsumptionRetiresOwnershipThatAppearedAfterNavigationAdmission() = withHost { f ->
    assertTrue(f.context.openApprovedHomeTab(ApprovedLauncherTab.APPS, searchApps = true))
    val launch = f.launches.single()
    val cover = f.cover()
    assertTrue(f.policy.pin(PinnedWindowIdentity(f.foregroundPackage, 41)))
    f.setHostField("surface", cover.value)
    f.setHostField("nativeCover", true)
    f.setHostField("label", "New pin")
    val request = f.pendingReveal(cover)
    f.setHostField("pendingReveal", request)
    val dream = Mockito.mock(PhotoDreamService::class.java)
    val preview = Mockito.mock(PhotoFramePreviewActivity::class.java)
    ImmortalDreamNavigation.attached(dream)
    ImmortalDreamNavigation.attached(preview)
    val finish = Answer<Any?> {
      assertFalse(f.host.pinned)
      assertFalse(f.host.covered)
      assertFalse(ImmortalDreamNavigation.hasOwnedSurface())
      assertNull(f.hostField("pendingReveal"))
      assertEquals("", f.hostField("label"))
      assertTrue(DreamPolicy.userExitAt > 0L)
      assertEquals(ApprovedLauncherTab.HOME, f.tab.value)
      f.events += "finish"
      null
    }
    Mockito.doAnswer(finish).`when`(dream).finish()
    Mockito.doAnswer(finish).`when`(preview).finish()

    f.consume(launch.intent)
    f.consume(launch.intent)

    assertEquals(ApprovedLauncherTab.APPS, f.tab.value)
    assertEquals(1, f.search.value)
    assertTrue(launch.extras.isEmpty())
    assertEquals(1, f.launches.size)
    Mockito.verify(dream, Mockito.times(1)).finish()
    Mockito.verify(preview, Mockito.times(1)).finish()
    Mockito.verify(f.home, Mockito.times(1)).selectApprovedHomeTab(ApprovedLauncherTab.APPS, true)
    Mockito.verify(f.handler).removeCallbacks(request)
    Mockito.verify(cover.root).setVisibility(View.GONE)
    f.assertObservations(1)
    assertEquals(listOf("cancelReveal", "hide", "windows", "finish", "finish"), f.events)
    f.assertSessionCleared()
    assertEquals(1, f.retirement.pendingCount)
    f.runScheduled(100L)
    assertTrue(cover.renderer.stopped)
    assertEquals(0, f.retirement.pendingCount)
  }

  private data class WindowSnapshot(val packageName: String, val window: AccessibilityWindowInfo,
      val root: AccessibilityNodeInfo, val foreground: Boolean)
  private data class CoverFixture(val value: Any, val root: FrameLayout,
      val renderer: PinnedRendererLifetime)
  private data class Launch(val intent: Intent, val target: Any?, var flags: Int = 0,
      val extras: MutableMap<String, Any?> = linkedMapOf())

  private class Fixture {
    val service = Mockito.mock(AccessibilityService::class.java)
    val wm = Mockito.mock(WindowManager::class.java)
    val context = Mockito.mock(Context::class.java)
    val home = Mockito.mock(HomeActivity::class.java, Mockito.CALLS_REAL_METHODS)
    val tab = mutableStateOf(ApprovedLauncherTab.HOME)
    val search = mutableStateOf(0)
    val intents = linkedMapOf<Intent, Launch>()
    val launches = mutableListOf<Launch>()
    val observations = mutableListOf<List<WindowSnapshot>>()
    val events = mutableListOf<String>()
    val scheduled = linkedMapOf<Runnable, Long>()
    var foregroundPackage = "com.synthetic.foreground"
    var removalFails = false
    private val attached = linkedMapOf<View, Boolean>()
    val host: PinnedAppFrameHost
    val handler: Handler
    val policy: PinnedCoverPolicy
    val retirement: PinnedSurfaceRetirement<View>

    init {
      `when`(service.getSystemService(Context.WINDOW_SERVICE)).thenReturn(wm)
      `when`(service.packageName).thenReturn("com.immortal.launcher")
      val packages = Mockito.mock(PackageManager::class.java)
      `when`(service.packageManager).thenReturn(packages)
      `when`(packages.queryIntentActivities(Mockito.any(Intent::class.java), Mockito.anyInt()))
          .thenReturn(emptyList())
      Mockito.doAnswer {
        events += "windows"
        val snapshot = listOf(window(foregroundPackage, 41, foreground = true),
            window("com.synthetic.background", 50, foreground = false))
        observations += snapshot
        snapshot.map { it.window }
      }.`when`(service).getWindows()
      Mockito.doAnswer {
        if (removalFails) throw IllegalStateException("Synthetic attached window removal failure")
        attached[it.getArgument<View>(0)] = false
        null
      }.`when`(wm).removeViewImmediate(Mockito.any(View::class.java))
      host = PinnedAppFrameHost(service)
      handler = hostField("main") as Handler
      policy = hostField("policy") as PinnedCoverPolicy
      @Suppress("UNCHECKED_CAST")
      val ledger = hostField("retirement") as PinnedSurfaceRetirement<View>
      retirement = ledger
      Mockito.doAnswer { scheduled[it.getArgument(0)] = 0L; true }
          .`when`(handler).post(Mockito.any(Runnable::class.java))
      Mockito.doAnswer { scheduled[it.getArgument(0)] = it.getArgument(1); true }
          .`when`(handler).postDelayed(Mockito.any(Runnable::class.java), Mockito.anyLong())
      Mockito.doAnswer {
        val runnable = it.getArgument<Runnable>(0)
        if (runnable === hostField("pendingReveal")) events += "cancelReveal"
        scheduled.remove(runnable)
        null
      }.`when`(handler).removeCallbacks(Mockito.any(Runnable::class.java))
      HomeActivity::class.java.getDeclaredField("launcherTab" + "$" + "delegate")
          .apply { isAccessible = true }.set(home, tab)
      HomeActivity::class.java.getDeclaredField("appsSearchOpenRequest" + "$" + "delegate")
          .apply { isAccessible = true }.set(home, search)
      val lifecycle = Mockito.mock(Lifecycle::class.java)
      Mockito.doReturn(lifecycle).`when`(home).lifecycle
      `when`(lifecycle.currentState).thenReturn(Lifecycle.State.RESUMED)
      Mockito.doReturn(false).`when`(home).hasWindowFocus()
      Mockito.doReturn(false).`when`(home).isDestroyed
      Mockito.doReturn(false).`when`(home).isFinishing
      Mockito.doAnswer {
        launches += checkNotNull(intents[it.getArgument<Intent>(0)])
        null
      }.`when`(context).startActivity(Mockito.any(Intent::class.java))
    }

    private fun window(pkg: String, id: Int, foreground: Boolean): WindowSnapshot {
      val window = Mockito.mock(AccessibilityWindowInfo::class.java)
      val root = Mockito.mock(AccessibilityNodeInfo::class.java)
      `when`(window.type).thenReturn(AccessibilityWindowInfo.TYPE_APPLICATION)
      `when`(window.id).thenReturn(id)
      `when`(window.isFocused).thenReturn(foreground)
      `when`(window.isActive).thenReturn(foreground)
      `when`(window.root).thenReturn(root)
      `when`(root.packageName).thenReturn(pkg)
      return WindowSnapshot(pkg, window, root, foreground)
    }

    fun assertObservations(count: Int) {
      assertEquals("Unexpected accessibility window acquisitions", count, observations.size)
      Mockito.verify(service, Mockito.times(count)).windows
      Mockito.verify(service, Mockito.never()).rootInActiveWindow
      for (snapshot in observations) for (entry in snapshot) {
        Mockito.verify(entry.window, Mockito.times(if (entry.foreground) 1 else 0)).root
        Mockito.verify(entry.window, Mockito.times(1)).recycle()
        if (entry.foreground) {
          Mockito.verify(entry.root, Mockito.times(1)).packageName
          Mockito.verify(entry.root, Mockito.times(1)).recycle()
          Mockito.verifyNoMoreInteractions(entry.root)
        } else Mockito.verifyNoInteractions(entry.root)
      }
    }

    fun assertSessionCleared() {
      assertFalse(host.pinned)
      assertFalse(host.covered)
      assertNull(policy.target)
      assertFalse(policy.lost)
      assertFalse(policy.expired)
      assertEquals("", hostField("label"))
      assertFalse(QuickBar.pinControlStatus().getBoolean("selected"))
      assertFalse(QuickBar.pinControlStatus().getBoolean("frameCovered"))
    }

    fun assertReleased() {
      assertSessionCleared()
      assertFalse(QuickBar.pinControlStatus().getBoolean("available"))
    }

    fun hostField(name: String): Any? = field(host, name).get(host)
    fun setHostField(name: String, value: Any?) { field(host, name).set(host, value) }
    fun cleanups() = hostField("cleanups") as Map<*, *>

    fun cover(): CoverFixture {
      val root = Mockito.mock(FrameLayout::class.java)
      attached[root] = true
      Mockito.doAnswer { attached[root] == true }.`when`(root).isAttachedToWindow
      Mockito.doAnswer { events += "hide"; null }.`when`(root).setVisibility(View.GONE)
      val type = Class.forName(PinnedAppFrameHost::class.java.name + "$" + "Cover")
      val constructor = type.declaredConstructors.single().apply { isAccessible = true }
      val value = constructor.newInstance(root, null, PinnedCoverMode.DAY,
          Mockito.mock(WindowManager.LayoutParams::class.java), 1L)
      return CoverFixture(value, root, field(value, "renderer").get(value) as PinnedRendererLifetime)
    }

    fun pendingReveal(cover: CoverFixture): Runnable {
      val type = Class.forName(PinnedAppFrameHost::class.java.name + "$" + "PendingReveal")
      return type.declaredConstructors.single().apply { isAccessible = true }
          .newInstance(host, cover.value, policy.revision, 900L) as Runnable
    }

    fun runScheduled(delay: Long) {
      val work = scheduled.entries.firstOrNull { it.value == delay }?.key
          ?: error("No real host callback scheduled at $delay ms")
      scheduled.remove(work)
      work.run()
    }

    fun consume(intent: Intent) {
      HomeActivity::class.java.getDeclaredMethod("consumeLauncherTabIntent", Intent::class.java)
          .apply { isAccessible = true }.invoke(home, intent)
    }

    fun close() {
      removalFails = false
      host.close()
      PinnedAppScreensaver.detach(host)
      repeat(20) {
        val work = scheduled.keys.firstOrNull() ?: return
        scheduled.remove(work)
        work.run()
      }
      check(scheduled.isEmpty()) { "Host callbacks escaped fixture cleanup" }
    }
  }

  /** Preserve weak registrations themselves so dismiss/clear cannot mutate the previous owner. */
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

  private fun withHost(block: (Fixture) -> Unit) {
    val mainLooper = Mockito.mock(Looper::class.java)
    Mockito.mockStatic(Looper::class.java).use { loopers ->
      loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(mainLooper)
      loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(mainLooper)
      Mockito.mockStatic(SystemClock::class.java).use { clocks ->
        clocks.`when`<Long> { SystemClock.elapsedRealtimeNanos() }.thenReturn(1_000_000L)
        // Keep constructor mocks neutral: QuickBar's lazily initialized Handler must never
        // retain a fixture queue/callback. Only the real host's private Handler is configured.
        Mockito.mockConstruction(Handler::class.java).use {
          Mockito.mockConstruction(Rect::class.java).use {
            val globals = Globals()
            var fixture: Fixture? = null
            try {
              globals.isolate()
              QuickBar.setBarVisible(false)
              QuickBar.setPinState(false, false, false)
              Mockito.mockConstruction(Intent::class.java,
                  Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { intent, construction ->
                val f = checkNotNull(fixture)
                val launch = Launch(intent, construction.arguments().getOrNull(1))
                f.intents[intent] = launch
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
              }.use {
                val f = Fixture()
                fixture = f
                PinnedAppScreensaver.attach(f.host)
                ApprovedHomeNavigation.resumed(f.home)
                block(f)
              }
            } finally {
              try { fixture?.close() } finally { globals.restore() }
            }
          }
        }
      }
    }
  }

  private companion object {
    fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }
  }
}
