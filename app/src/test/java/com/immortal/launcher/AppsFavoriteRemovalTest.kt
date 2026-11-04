/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Actual compiled Apps menu callback and UserLayout persistence, with generated inventories. */
class AppsFavoriteRemovalTest {
  private val apps = (0..6).map { "generated.app$it/.Main" }
  private val shortcuts = (0..5).map { "generated.shortcut$it/.Main" }

  @Test fun removingOneOfSixInstalledAppsLeavesBothFavoriteProjections() {
    val f = Fixture(apps.take(6))
    f.click()
    f.assertRemoved(apps[0])
  }

  @Test fun removingTheOnlyInstalledAppUsesAvailableShortcutsAsReplacements() {
    val f = Fixture(apps.take(1))
    f.click()
    f.assertRemoved(apps[0])
    assertEquals(shortcuts, f.homeFavorites())
    assertEquals(shortcuts, f.appsFavorites())
  }

  @Test fun removingFromTwoInstalledAppsKeepsTheOtherAppAheadOfShortcuts() {
    val f = Fixture(apps.take(2))
    f.click()
    f.assertRemoved(apps[0])
    assertEquals(listOf(apps[1]) + shortcuts.take(5), f.homeFavorites())
    assertEquals(f.homeFavorites(), f.appsFavorites())
  }

  @Test fun removalRetainsExplicitShortcutOrderAndStaleIdsWithoutDuplicates() {
    val f = Fixture(apps.take(3),
        listOf(shortcuts[2], apps[0], "missing.app/.Main", shortcuts[2], apps[1]))
    f.click()
    assertEquals(listOf(apps[1], apps[2], shortcuts[2], "missing.app/.Main",
        shortcuts[0], shortcuts[1], shortcuts[3], shortcuts[4], shortcuts[5], apps[0]),
        UserLayout.loadOrder(f.context))
    f.assertRemoved(apps[0])
  }

  @Test fun addingFavoriteRetainsItsExactExistingOrderPolicy() {
    val f = Fixture(apps, listOf(apps[1], shortcuts[2], "missing.app/.Main"), target = apps[6])
    f.click(isFavorite = false)
    assertEquals(listOf(apps[6], apps[1], apps[0], apps[2], apps[3], apps[4], apps[5],
        shortcuts[2], "missing.app/.Main"), UserLayout.loadOrder(f.context))
    assertEquals(apps[6], f.homeFavorites().first())
    assertEquals(apps[6], f.appsFavorites().first())
    f.assertSavedAndDismissed()
  }

  @Test fun sevenInstalledAppsAlreadyProvideEnoughRemovalReplacements() {
    val f = Fixture(apps)
    f.click()
    f.assertRemoved(apps[0])
    assertEquals(apps.drop(1), f.homeFavorites())
    assertEquals(apps.drop(1), f.appsFavorites())
  }

  @Test fun staleRemovalCannotOverwriteAnAcknowledgedFavoriteOrder() = stale(isFavorite = true)
  @Test fun staleAddCannotOverwriteAnAcknowledgedFavoriteOrder() = stale(isFavorite = false)

  private fun stale(isFavorite: Boolean) {
    val f = Fixture(apps.take(3), listOf(apps[0]))
    val acknowledged = listOf(shortcuts[4], apps[2], apps[1], apps[0])
    f.raw = UserLayout.serializeOrder(acknowledged)
    val before = f.raw
    Mockito.mockStatic(Toast::class.java).use { toast ->
      toast.`when`<Toast> {
        Toast.makeText(f.context,
            "Favorites changed on another screen. Review the layout and try again.",
            Toast.LENGTH_LONG)
      }.thenReturn(Mockito.mock(Toast::class.java))
      f.click(isFavorite)
    }
    assertEquals(0, f.writes)
    assertEquals(before, f.raw)
    assertEquals(acknowledged, UserLayout.loadOrder(f.context))
    assertEquals(acknowledged, f.order.value)
    assertNull(f.menu.value)
    Mockito.verify(f.prefs, Mockito.never()).edit()
  }

  private inner class Fixture(
      val installed: List<String>, initialOrder: List<String> = emptyList(),
      target: String = installed.first(),
  ) {
    val context = Mockito.mock(Context::class.java)
    val prefs = Mockito.mock(SharedPreferences::class.java)
    var raw = org.json.JSONArray(initialOrder).toString()
    var writes = 0
    val order: MutableState<List<String>>
    private val type = Class.forName("com.immortal.launcher.AppItem")
    private val selected = item(target)
    val menu = mutableStateOf<Any?>(selected)
    private val ordered: List<Any>

    init {
      Mockito.`when`(context.getSharedPreferences("immortal_layout", Context.MODE_PRIVATE))
          .thenReturn(prefs)
      Mockito.`when`(prefs.getString("home_order", null)).thenAnswer { raw }
      Mockito.`when`(prefs.edit()).thenAnswer {
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        var staged: String? = null
        Mockito.`when`(editor.putString(Mockito.eq("home_order"), Mockito.anyString()))
            .thenAnswer { staged = it.getArgument(1); editor }
        Mockito.doAnswer { raw = checkNotNull(staged); writes++; null }.`when`(editor).apply()
        editor
      }
      order = mutableStateOf(UserLayout.loadOrder(context))
      ordered = UserLayout.applyOrder(installed, order.value) { it }.map(::item)
    }

    private fun item(id: String): Any {
      val component = Mockito.mock(ComponentName::class.java)
      Mockito.`when`(component.flattenToString()).thenReturn(id)
      return type.getDeclaredConstructor(String::class.java, ComponentName::class.java,
          ImageBitmap::class.java, Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
          .apply { isAccessible = true }
          .newInstance("Generated app", component, Mockito.mock(ImageBitmap::class.java), 0L, 0L)
    }

    fun click(isFavorite: Boolean = true) {
      val state = MutableState::class.java
      val allowed = setOf(type, List::class.java, Boolean::class.javaPrimitiveType,
          state, Context::class.java)
      val callback = Class.forName("com.immortal.launcher.AppSwitcherActivityKt")
          .declaredMethods.single { method ->
            Modifier.isPrivate(method.modifiers) && Modifier.isStatic(method.modifiers) &&
                method.returnType == Unit::class.java && method.parameterCount in 6..7 &&
                method.parameterTypes.first() == type &&
                method.parameterTypes.all { it in allowed } &&
                method.parameterTypes.count { it == List::class.java } == method.parameterCount - 5 &&
                method.parameterTypes.count { it == state } == 2 &&
                method.parameterTypes.count { it == Context::class.java } == 1 &&
                method.parameterTypes.count { it == Boolean::class.javaPrimitiveType } == 1
          }
      // Two explicitly admitted ABIs: baseline captures ordered only; candidate also captures
      // built-in IDs. Bind first/second List and first/second state by actual capture type.
      var lists = 0
      var states = 0
      val args = callback.parameterTypes.map {
        when (it) {
          type -> selected
          List::class.java -> if (lists++ == 0) ordered else shortcuts
          Boolean::class.javaPrimitiveType -> isFavorite
          state -> if (states++ == 0) order else menu
          Context::class.java -> context
          else -> error("Unexpected Apps favorite callback capture")
        }
      }.toTypedArray()
      callback.isAccessible = true
      callback.invoke(null, *args)
    }

    fun homeFavorites(): List<String> = UserLayout.applyOrder(
        installed + shortcuts, UserLayout.loadOrder(context)) { it }.take(6)

    // The existing Apps badge projection admits known IDs before selecting its first six.
    fun appsFavorites(): List<String> {
      val known = (installed + shortcuts).toSet()
      return (UserLayout.loadOrder(context).filter { it in known } + installed + shortcuts)
          .distinct().take(6)
    }

    fun assertSavedAndDismissed() {
      assertEquals(1, writes)
      assertEquals(UserLayout.loadOrder(context), order.value)
      assertNull(menu.value)
    }
    fun assertRemoved(id: String) {
      assertSavedAndDismissed()
      assertEquals(id, UserLayout.loadOrder(context).last())
      assertFalse("Removed app must leave Home favorites", id in homeFavorites())
      assertFalse("Removed app must lose the Apps favorite badge", id in appsFavorites())
      assertEquals(6, homeFavorites().size)
      assertEquals(homeFavorites(), appsFavorites())
    }
  }
}
