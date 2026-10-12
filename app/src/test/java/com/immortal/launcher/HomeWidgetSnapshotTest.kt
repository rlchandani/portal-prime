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
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class HomeWidgetSnapshotTest {
  private class Fixture {
    val raw = AtomicReference<String?>(null)
    val prefs = mock(SharedPreferences::class.java)
    val context = mock(Context::class.java)
    init {
      `when`(context.getSharedPreferences("immortal_home_widgets", Context.MODE_PRIVATE)).thenReturn(prefs)
      `when`(prefs.getString("widgets", null)).thenAnswer { raw.get() }
      HomeWidgetStore.load(context) // Clear a previous fixture's process snapshot.
    }
    fun read() = HomeWidgetStore.load(context)
    fun set(widgets: List<HomeWidgetStore.HomeWidget>) { raw.set(HomeWidgetStore.serialize(widgets)) }
  }
  private fun widget(id: String, x: Int = 2, y: Int = 1) = HomeWidgetStore.HomeWidget(
      kind = HomeWidgetStore.KIND_WEATHER, customId = id, spanX = x, spanY = y, explicitSize = true)

  @Test fun warmHomeResumesReuseOneDecodedPlacementList() {
    val f = Fixture(); f.set(listOf(widget("first"), widget("second", 1, 1)))
    val first = f.read()
    repeat(20) { assertSame(first, f.read()) }
    verify(f.prefs, times(22)).getString("widgets", null)
  }

  @Test fun resizeReorderAndRemoteReplacementReadCurrentPreferences() {
    val f = Fixture(); val a = widget("a"); val b = widget("b")
    f.set(listOf(a, b)); val first = f.read()
    val changed = listOf(b.copy(spanX = 1, spanY = 2), a)
    f.set(changed)
    val second = f.read()
    assertEquals(changed, second); assertNotSame(first, second); assertSame(second, f.read())
  }

  @Test fun deletionClearsSnapshotEvenWhenSameJsonIsRestoredLater() {
    val f = Fixture(); f.set(listOf(widget("restored"))); val first = f.read()
    val saved = f.raw.get(); f.raw.set(null)
    assertTrue(f.read().isEmpty())
    f.raw.set(saved); val restored = f.read()
    assertEquals(first, restored); assertNotSame(first, restored); assertSame(restored, f.read())
  }

  @Test fun ordinarySaveBecomesVisibleOnNextReadWithoutInvalidationHook() {
    val f = Fixture(); f.set(listOf(widget("old"))); val old = f.read()
    val editor = mock(SharedPreferences.Editor::class.java)
    `when`(f.prefs.edit()).thenReturn(editor)
    val newWidgets = listOf(widget("new", 1, 2))
    val encoded = HomeWidgetStore.serialize(newWidgets)
    `when`(editor.putString("widgets", encoded)).thenReturn(editor)
    doAnswer { f.raw.set(encoded); null }.`when`(editor).apply()
    HomeWidgetStore.save(f.context, newWidgets)
    val fresh = f.read(); assertEquals(newWidgets, fresh); assertNotSame(old, fresh)
    assertSame(fresh, f.read()); verify(editor, times(1)).apply()
  }

  @Test fun sharedPlacementListCannotBeMutatedByCaller() {
    val f = Fixture(); f.set(listOf(widget("owned"))); val saved = f.read()
    assertThrows(UnsupportedOperationException::class.java) {
      @Suppress("UNCHECKED_CAST")
      (saved as MutableList<HomeWidgetStore.HomeWidget>).clear()
    }
    assertEquals(listOf(widget("owned")), f.read())
  }

  @Test fun simultaneousReadersShareTheSameSnapshot() {
    val f = Fixture(); f.set((1..24).map { widget("widget-$it") })
    val gate = CountDownLatch(1); val workers = Executors.newFixedThreadPool(8)
    try {
      val pending = (1..8).map { workers.submit<List<HomeWidgetStore.HomeWidget>> {
        check(gate.await(5, TimeUnit.SECONDS)); f.read()
      } }
      gate.countDown(); val results = pending.map { it.get(5, TimeUnit.SECONDS) }
      assertEquals(24, results.first().size)
      results.forEach { assertSame(results.first(), it) }
    } finally { workers.shutdownNow() }
  }

  @Test fun oversizedPlacementJsonRemainsSupportedWithoutRetention() {
    val f = Fixture(); val widgets = (1..300).map { widget("large-widget-$it") }
    f.set(widgets); assertTrue(f.raw.get()!!.length > 32_768)
    val first = f.read(); val second = f.read()
    assertEquals(widgets, first); assertEquals(first, second); assertNotSame(first, second)
  }

  @Test fun malformedInputStaysTolerantAndCannotHideLaterValidWidgets() {
    val f = Fixture(); f.raw.set("broken-json")
    assertTrue(f.read().isEmpty()); assertTrue(f.read().isEmpty())
    val valid = listOf(widget("valid")); f.set(valid)
    assertEquals(valid, f.read())
  }
}
