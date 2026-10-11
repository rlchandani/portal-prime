/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.settings.BoolSpec
import com.immortal.launcher.settings.SettingsDomain
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.mock

class ScreensaverSettingsWriteQueueTest {
  private val context = mock(Context::class.java)
  private fun edit(domain: String = "screensaver", key: String = "shuffle", value: Any = true) =
    ScreensaverSettingEdit(domain, key, value)

  @Test fun registryAndSideEffectsRunOffTheInputThread() = runBlocking {
    val caller = Thread.currentThread()
    var stored = false
    var setterThread: Thread? = null
    var hookThread: Thread? = null
    var hooks = 0
    val domain = SettingsDomain("screensaver", "Screensaver", { _: Context -> stored },
      listOf(BoolSpec("shuffle", "Shuffle", get = { it }, set = { _, value ->
        setterThread = Thread.currentThread(); stored = value
      })), onApplied = { _, keys ->
        assertEquals(setOf("shuffle"), keys); hookThread = Thread.currentThread(); hooks++
      })
    val queue = ScreensaverSettingsWriteQueue {
      it.key in domain.apply(context, JSONObject().put(it.key, it.value))
    }
    queue.submit(edit())
    withTimeout(3000) { queue.awaitIdle() }
    assertTrue(stored); assertEquals(1, hooks)
    assertNotSame(caller, setterThread); assertNotSame(caller, hookThread)
    assertEquals(0L, queue.failureCount)
  }

  @Test fun domainValidationRejectsBadTypesWithoutSideEffectsAndAllowsTheNextEdit() = runBlocking {
    var stored = false
    var hooks = 0
    val domain = SettingsDomain("screensaver", "Screensaver", { _: Context -> stored },
      listOf(BoolSpec("shuffle", "Shuffle", get = { it }, set = { _, value -> stored = value })),
      onApplied = { _, _ -> hooks++ })
    val queue = ScreensaverSettingsWriteQueue {
      it.key in domain.apply(context, JSONObject().put(it.key, it.value))
    }
    queue.submit(edit(value = "invalid"))
    withTimeout(3000) { queue.awaitIdle() }
    assertFalse(stored); assertEquals(0, hooks); assertEquals(1L, queue.failureCount)
    queue.submit(edit(value = true))
    withTimeout(3000) { queue.awaitIdle() }
    assertTrue(stored); assertEquals(1, hooks)
  }

  @Test fun sameKeyInDifferentDomainsDoesNotDropEitherPendingEdit() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val applied = CopyOnWriteArrayList<ScreensaverSettingEdit>()
    val queue = ScreensaverSettingsWriteQueue {
      if (it.key == "block") { started.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }
      applied += it; true
    }
    try {
      queue.submit(edit(key = "block"))
      withTimeout(3000) { started.await() }
      queue.submit(edit("screensaver", "enabled", false))
      queue.submit(edit("calendar", "enabled", true))
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(listOf(edit(key = "block"), edit("screensaver", "enabled", false),
      edit("calendar", "enabled", true)), applied.toList())
  }

  @Test fun exceptionsAreReportedAndDoNotBlockLaterEdits() = runBlocking {
    var stored = false
    val queue = ScreensaverSettingsWriteQueue {
      if (it.value == "fail") throw IllegalStateException("write failed")
      stored = it.value as Boolean; true
    }
    queue.submit(edit(value = "fail"))
    withTimeout(3000) { queue.awaitIdle() }
    queue.submit(edit(value = true))
    withTimeout(3000) { queue.awaitIdle() }
    assertTrue(stored); assertEquals(1L, queue.failureCount)
  }

  @Test fun departingPaneCancelsItsReadbackButNotTheAcceptedSave() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    var stored = false
    var published = false
    val queue = ScreensaverSettingsWriteQueue {
      started.complete(Unit); check(release.await(3, TimeUnit.SECONDS))
      stored = it.value as Boolean; true
    }
    try {
      queue.submit(edit(value = true))
      withTimeout(3000) { started.await() }
      val pane = launch { queue.awaitIdle(); published = true }
      pane.cancelAndJoin()
      assertFalse(stored); assertFalse(published)
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertTrue(stored); assertFalse(published)
  }
}
