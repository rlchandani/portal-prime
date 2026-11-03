/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class HomeWidgetMutationTest {
  private class Fixture {
    val prefs = ScreensaverTestPrefs()
    val context = prefs.context
    init {
      `when`(context.getSharedPreferences("immortal_home_widgets", Context.MODE_PRIVATE)).thenReturn(prefs)
      `when`(context.getSharedPreferences("immortal_layout", Context.MODE_PRIVATE)).thenReturn(prefs)
    }
    fun restore(widgets: List<HomeWidgetStore.HomeWidget>) {
      val layout = RemoteLayoutStore.read(context)
          .put("widgets", JSONArray(HomeWidgetStore.serialize(widgets)))
      assertTrue(RemoteLayoutPolicy.validate(layout, HomeWidgetStore.load(context)).valid)
      RemoteLayoutStore.apply(context, layout)
    }
  }
  private fun widget(kind: String, id: String) =
      HomeWidgetStore.HomeWidget(kind = kind, customId = "$kind:$id")

  @Test fun addUsesRemoteRestoredListRatherThanRetainedHomeSnapshot() {
    val f = Fixture(); val a = widget("weather", "a"); val b = widget("calendar", "b")
    HomeWidgetStore.save(f.context, listOf(a))
    val retained = HomeWidgetStore.load(f.context)
    f.restore(listOf(a, b))
    val c = widget("stocks", "c")
    assertEquals(listOf(a, b, c), HomeWidgetStore.add(f.context, c))
    assertEquals(listOf(a), retained)
    assertEquals(listOf(a, b, c), HomeWidgetStore.load(f.context))
  }

  @Test fun staleResizeCannotEraseRemoteAddedWidget() {
    val f = Fixture(); val a = widget("weather", "a"); val b = widget("calendar", "b")
    HomeWidgetStore.save(f.context, listOf(a))
    val retained = HomeWidgetStore.load(f.context)
    f.restore(listOf(a, b))
    val writes = f.prefs.writeCount
    assertFalse(HomeWidgetStore.replaceIfUnchanged(f.context, retained, listOf(a.copy(spanX = 1))))
    assertEquals(listOf(a, b), HomeWidgetStore.load(f.context))
    assertEquals(writes, f.prefs.writeCount)
  }

  @Test fun staleRemoveCannotReintroduceRemoteRemovedWidget() {
    val f = Fixture(); val a = widget("weather", "a"); val b = widget("calendar", "b")
    HomeWidgetStore.save(f.context, listOf(a, b))
    val retained = HomeWidgetStore.load(f.context)
    f.restore(listOf(a))
    assertFalse(HomeWidgetStore.replaceIfUnchanged(f.context, retained, HomeWidgetStore.without(retained, a.key)))
    assertEquals(listOf(a), HomeWidgetStore.load(f.context))
  }

  @Test fun staleReorderCannotUndoRemoteResize() {
    val f = Fixture(); val a = widget("weather", "a"); val b = widget("calendar", "b")
    HomeWidgetStore.save(f.context, listOf(a, b))
    val retained = HomeWidgetStore.load(f.context)
    val resized = a.copy(spanX = 1, spanY = 1, explicitSize = true)
    f.restore(listOf(resized, b))
    assertFalse(HomeWidgetStore.replaceIfUnchanged(f.context, retained, retained.reversed()))
    assertEquals(listOf(resized, b), HomeWidgetStore.load(f.context))
  }

  @Test fun currentEditAndRetryAfterReloadKeepWorking() {
    val f = Fixture(); val a = widget("weather", "a"); val b = widget("calendar", "b")
    HomeWidgetStore.save(f.context, listOf(a))
    assertTrue(HomeWidgetStore.replaceIfUnchanged(f.context, listOf(a), listOf(a.copy(spanX = 1))))
    val old = HomeWidgetStore.load(f.context)
    f.restore(listOf(a, b))
    assertFalse(HomeWidgetStore.replaceIfUnchanged(f.context, old, emptyList()))
    val fresh = HomeWidgetStore.load(f.context)
    assertTrue(HomeWidgetStore.replaceIfUnchanged(f.context, fresh, HomeWidgetStore.without(fresh, a.key)))
    assertEquals(listOf(b), HomeWidgetStore.load(f.context))
  }

  @Test fun addDoesNotRestoreARemotelyRemovedWidgetOrItsOldSize() {
    val f = Fixture(); val a = widget("weather", "a"); val b = widget("calendar", "b")
    HomeWidgetStore.save(f.context, listOf(a, b))
    val resized = b.copy(spanX = 1, explicitSize = true)
    f.restore(listOf(resized))
    val c = widget("stocks", "c")
    assertEquals(listOf(resized, c), HomeWidgetStore.add(f.context, c))
  }

  @Test fun competingEditsFromOneSnapshotHaveExactlyOneWinner() {
    val f = Fixture(); val a = widget("weather", "a")
    HomeWidgetStore.save(f.context, listOf(a))
    val start = CountDownLatch(1); val workers = Executors.newFixedThreadPool(8)
    try {
      val edits = (1..8).map { i -> workers.submit<Boolean> {
        check(start.await(5, TimeUnit.SECONDS))
        HomeWidgetStore.replaceIfUnchanged(f.context, listOf(a),
            listOf(a, widget("calendar", "$i")))
      } }
      start.countDown()
      assertEquals(1, edits.count { it.get(5, TimeUnit.SECONDS) })
      assertEquals(2, HomeWidgetStore.load(f.context).size)
    } finally { workers.shutdownNow() }
  }

  @Test fun widgetObservationFiltersKeysAndSuppressesDisposedCallbacks() {
    val context = mock(Context::class.java)
    val prefs = mock(SharedPreferences::class.java)
    `when`(context.getSharedPreferences("immortal_home_widgets", Context.MODE_PRIVATE)).thenReturn(prefs)
    var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    doAnswer { call -> listener = call.getArgument(0); null }.`when`(prefs)
        .registerOnSharedPreferenceChangeListener(any(SharedPreferences.OnSharedPreferenceChangeListener::class.java))
    var changes = 0
    val stop = HomeWidgetStore.observe(context) { changes++ }
    val queued = listener!!
    queued.onSharedPreferenceChanged(prefs, "dashboard_cards_migrated")
    assertEquals(0, changes)
    queued.onSharedPreferenceChanged(prefs, "widgets")
    queued.onSharedPreferenceChanged(prefs, null)
    assertEquals(2, changes)
    stop(); stop()
    queued.onSharedPreferenceChanged(prefs, "widgets")
    assertEquals(2, changes)
    verify(prefs, times(1)).unregisterOnSharedPreferenceChangeListener(queued)
    verify(prefs, never()).edit()
  }

  @Test fun nativeWidgetWritersHoldTheRemoteRevisionAndApplyLock() {
    val f = Fixture(); val prefs = spy(f.prefs)
    `when`(f.context.getSharedPreferences("immortal_home_widgets", Context.MODE_PRIVATE)).thenReturn(prefs)
    doAnswer { call ->
      assertTrue("Widget write escaped remote revision/apply boundary",
          Thread.holdsLock(RemoteCompanionStore.mutationLock))
      call.callRealMethod()
    }.`when`(prefs).edit()
    val a = widget("weather", "a")
    HomeWidgetStore.save(f.context, listOf(a))
    HomeWidgetStore.add(f.context, widget("calendar", "b"))
    val fresh = HomeWidgetStore.load(f.context)
    assertTrue(HomeWidgetStore.replaceIfUnchanged(f.context, fresh, listOf(a)))
    HomeWidgetStore.migrateDashboardCards(f.context)
    assertEquals(4, HomeWidgetStore.load(f.context).size)
  }
}
