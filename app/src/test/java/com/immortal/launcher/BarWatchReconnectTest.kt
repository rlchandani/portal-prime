/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.stubbing.Answer

/** Invoke the real reconnect boundary; only Android storage and the delayed queue are generated. */
class BarWatchReconnectTest {
  private val own = "com.immortal.launcher.debug/com.immortal.launcher.BarWatchService"
  private val confirm = "com.immortal.launcher.debug/com.immortal.launcher.InstallConfirmService"
  private val a = "public.alpha/.Reader"
  private val b = "public.beta/.Reader"
  private val c = "public.gamma/.Reader"

  @Test fun actualInstallConfirmAppendSurvivesReconnectGap() {
    val f = withFixture("$a:$own:$b") {
      it.begin()
      SettingsGuard.enableInstallConfirm(it.context)
      assertEquals("The real installer enabler must append during the held gap", "$a:$b:$confirm", it.current)
      it.drain()
    }
    assertEquals("Reconnect must retain the installer service added during its gap", "$a:$b:$confirm:$own", f.current)
  }

  @Test fun foreignServiceAddedDuringGapRemainsEnabled() {
    val f = withFixture("$own:$a") {
      it.begin()
      it.current = "$a:$c"
      it.drain()
    }
    assertEquals("The current foreign addition must not disappear", "$a:$c:$own", f.current)
  }

  @Test fun foreignServiceRemovedDuringGapIsNotResurrected() {
    val f = withFixture("$a:$own:$b") {
      it.begin()
      it.current = b
      it.drain()
    }
    assertEquals("The removed foreign service must stay removed", "$b:$own", f.current)
  }

  @Test fun replacementForeignListAndItsOrderSurviveReconnect() {
    val f = withFixture("$a:$own:$b") {
      it.begin()
      it.current = "$c:$b:$c"
      it.drain()
    }
    assertEquals("Reconnect must use the replacement list, preserving foreign duplicates and order", "$c:$b:$c:$own", f.current)
  }

  @Test fun alreadyReaddedCanonicalWatcherKeepsCurrentRawList() {
    val latest = ":$c::$own:$b::"
    val f = withFixture("$own:$a") {
      it.begin()
      it.current = latest
      it.drain()
    }
    assertEquals("A current canonical watcher must not duplicate or rewrite the latest foreign list", latest, f.current)
  }

  @Test fun currentMixedCaseWatcherDuplicatesRepairWithoutLosingForeignEntries() {
    val f = withFixture("$a:$own") {
      it.begin()
      it.current = "${own.uppercase()}:$c:$own:$b"
      it.drain()
    }
    assertEquals("Watcher repair must preserve the foreign entries in the current list", "$c:$b:$own", f.current)
  }

  @Test fun overlappingReconnectCallbacksCannotReplayAnOlderForeignList() {
    val f = withFixture("$own:$a") {
      it.begin()
      it.current = "$a:$c"
      it.begin(expectedQueued = 2)
      it.current = "$c:$b"
      it.drain(reverse = true)
    }
    assertEquals("Both held callbacks must retain the most recent foreign list", "$c:$b:$own", f.current)
  }

  @Test fun unchangedForeignListRetainsDropAndDelayedRejoin() {
    val f = withFixture("$a:$own:$b") {
      it.begin()
      assertEquals("Only the watcher leaves during reconnect", "$a:$b", it.current)
      assertEquals(listOf(2000L), it.delays)
      assertEquals(listOf(1), it.masterWrites)
      it.drain()
    }
    assertEquals("$a:$b:$own", f.current)
    assertEquals(listOf("$a:$b", "$a:$b:$own"), f.listWrites)
    assertEquals(listOf(1, 1), f.masterWrites)
  }

  @Test fun emptyAndWatcherOnlyListsReconnectWithoutForeignEntries() {
    for (initial in listOf("", own)) {
      val f = withFixture(initial) {
        it.begin()
        assertEquals("", it.current)
        it.drain()
      }
      assertEquals(own, f.current)
      assertEquals(listOf(2000L), f.delays)
    }
  }

  @Test fun connectedWatcherDoesNotReadWriteOrScheduleReconnect() {
    val original = "$a:$own:$b"
    val f = withFixture(original, connected = true) {
      SettingsGuard.ensureBarWatchConnected(it.context)
    }
    assertEquals(original, f.current)
    assertEquals(0, f.secureReads)
    assertTrue(f.listWrites.isEmpty())
    assertTrue(f.masterWrites.isEmpty())
    assertTrue(f.delays.isEmpty())
    assertEquals(0, f.handlerConstructions)
    assertEquals(0, f.componentConstructions)
  }

  private inner class Fixture(initial: String) {
    val context = Mockito.mock(Context::class.java)
    val resolver = Mockito.mock(ContentResolver::class.java)
    var current = initial
    var secureReads = 0
    var handlerConstructions = 0
    var componentConstructions = 0
    val listWrites = mutableListOf<String>()
    val masterWrites = mutableListOf<Int>()
    val delays = mutableListOf<Long>()
    val queued = mutableListOf<Runnable>()
    val unexpected = mutableListOf<String>()

    init { Mockito.`when`(context.contentResolver).thenReturn(resolver) }

    fun begin(expectedQueued: Int = 1) {
      SettingsGuard.ensureBarWatchConnected(context)
      assertEquals("The actual method must enqueue the reconnect callback", expectedQueued, queued.size)
    }

    fun drain(reverse: Boolean = false) {
      val pending = queued.toList().let { if (reverse) it.reversed() else it }
      queued.clear()
      pending.forEach { it.run() }
      assertTrue("No real queue or additional delayed work is admitted", queued.isEmpty())
    }
  }

  private fun withFixture(initial: String, connected: Boolean = false, operation: (Fixture) -> Unit): Fixture {
    val f = Fixture(initial)
    // Reflection is solely ownership cleanup: keep the exact pre-test connection and capability epoch.
    // Every product operation under test is a public method, with no production test hook.
    val connection = RemoteInput::class.java.getDeclaredField("connection").apply { isAccessible = true }
    val previousConnection = connection.get(null)
    try {
      RemoteInput.unregister()
      if (connected) RemoteInput.register(Mockito.mock(AccessibilityService::class.java))
      Mockito.mockStatic(Log::class.java).use {
        Mockito.mockStatic(Looper::class.java).use { loops ->
          val main = Mockito.mock(Looper::class.java)
          loops.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
          Mockito.mockStatic(Settings.Secure::class.java, Answer<Any?> { call ->
            when (call.method.name) {
              "getString" -> {
                assertSame(f.resolver, call.arguments[0])
                assertEquals(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, call.arguments[1])
                f.secureReads++
                f.current
              }
              "putString" -> {
                assertSame(f.resolver, call.arguments[0])
                assertEquals(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, call.arguments[1])
                f.current = call.arguments[2] as String
                f.listWrites += f.current
                true
              }
              "putInt" -> {
                assertSame(f.resolver, call.arguments[0])
                assertEquals(Settings.Secure.ACCESSIBILITY_ENABLED, call.arguments[1])
                f.masterWrites += call.arguments[2] as Int
                true
              }
              else -> {
                f.unexpected += call.method.name
                Mockito.RETURNS_DEFAULTS.answer(call)
              }
            }
          }).use {
            Mockito.mockConstruction(ComponentName::class.java) { component, construction ->
              f.componentConstructions++
              assertSame(f.context, construction.arguments()[0])
              val name = when (construction.arguments()[1]) {
                BarWatchService::class.java -> own
                InstallConfirmService::class.java -> confirm
                else -> throw AssertionError("Unexpected component construction: ${construction.arguments()[1]}")
              }
              Mockito.`when`(component.flattenToString()).thenReturn(name)
            }.use {
              Mockito.mockConstruction(Handler::class.java) { handler, construction ->
                f.handlerConstructions++
                assertEquals(listOf(main), construction.arguments())
                Mockito.doAnswer { call ->
                  f.queued += call.getArgument<Runnable>(0)
                  f.delays += call.getArgument<Long>(1)
                  true
                }.`when`(handler).postDelayed(Mockito.any(Runnable::class.java), Mockito.anyLong())
              }.use { operation(f) }
            }
          }
        }
      }
      assertTrue("Unexpected secure-setting operation: ${f.unexpected}", f.unexpected.isEmpty())
      return f
    } finally {
      f.queued.clear()
      RemoteInput.unregister()
      connection.set(null, previousConnection)
    }
  }
}
