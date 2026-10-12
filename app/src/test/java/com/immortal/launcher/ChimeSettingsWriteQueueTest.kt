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
import com.immortal.launcher.settings.SettingsDomains
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
import org.mockito.Mockito.`when`

class ChimeSettingsWriteQueueTest {
  @Test fun realVolumeRegistryPersistsOffTheCallerThread() = runBlocking {
    val prefs = ScreensaverTestPrefs(mapOf("volume" to 70))
    `when`(prefs.context.getSharedPreferences("immortal_chime", Context.MODE_PRIVATE)).thenReturn(prefs)
    val caller = Thread.currentThread()
    var applyThread: Thread? = null
    val queue = ChimeSettingsWriteQueue { key, value ->
      applyThread = Thread.currentThread()
      key in SettingsDomains.chime.apply(prefs.context, JSONObject().put(key, value))
    }
    queue.submit("volume", 80)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(80, ChimeConfig.load(prefs.context).volume)
    assertNotSame(caller, applyThread)
    assertEquals(1, prefs.writeCount)
    assertEquals(0L, queue.failureCount)
  }

  @Test fun actualChimeValidationRejectsBadValueAndTheNextValidSaveWorks() = runBlocking {
    val prefs = ScreensaverTestPrefs(mapOf("volume" to 70))
    `when`(prefs.context.getSharedPreferences("immortal_chime", Context.MODE_PRIVATE)).thenReturn(prefs)
    val queue = ChimeSettingsWriteQueue { key, value ->
      key in SettingsDomains.chime.apply(prefs.context, JSONObject().put(key, value))
    }
    queue.submit("volume", 101)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(70, ChimeConfig.load(prefs.context).volume)
    assertEquals(0, prefs.writeCount)
    assertEquals(1L, queue.failureCount)
    queue.submit("volume", 60)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(60, ChimeConfig.load(prefs.context).volume)
    assertEquals(1, prefs.writeCount)
  }

  @Test fun registrySideEffectRunsOnTheWorkerAfterTheSetter() = runBlocking {
    val caller = Thread.currentThread()
    var stored = false
    var setterThread: Thread? = null
    var hookThread: Thread? = null
    var hooks = 0
    val prefs = ScreensaverTestPrefs()
    val domain = SettingsDomain("chime", "Sounds", { _: Context -> stored },
      listOf(BoolSpec("quietHoursOn", "Quiet hours", get = { it }, set = { _, value ->
        setterThread = Thread.currentThread(); stored = value
      })), onApplied = { _, keys ->
        assertEquals(setOf("quietHoursOn"), keys); assertTrue(stored)
        hookThread = Thread.currentThread(); hooks++
      })
    val queue = ChimeSettingsWriteQueue { key, value ->
      key in domain.apply(prefs.context, JSONObject().put(key, value))
    }
    queue.submit("quietHoursOn", true)
    withTimeout(3000) { queue.awaitIdle() }
    assertTrue(stored); assertEquals(1, hooks)
    assertNotSame(caller, setterThread); assertSame(setterThread, hookThread)
  }

  @Test fun leavingThePaneCancelsItsReadbackButNotTheAcceptedWrite() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    var stored = false
    var published = false
    val queue = ChimeSettingsWriteQueue { _, value ->
      started.complete(Unit); check(release.await(3, TimeUnit.SECONDS))
      stored = value as Boolean; true
    }
    try {
      queue.submit("quietHoursOn", true)
      withTimeout(3000) { started.await() }
      val pane = launch { queue.awaitIdle(); published = true }
      pane.cancelAndJoin()
      assertFalse(stored); assertFalse(published)
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertTrue(stored); assertFalse(published)
  }

  @Test fun latestPendingValuesPreserveCrossControlEventOrder() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val edits = CopyOnWriteArrayList<Pair<String, Any>>()
    val queue = ChimeSettingsWriteQueue { key, value ->
      if (key == "block") { started.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }
      edits += key to value; true
    }
    try {
      queue.submit("block", true)
      withTimeout(3000) { started.await() }
      repeat(100) { queue.submit("volume", it) }
      queue.submit("quietHoursOn", true)
      queue.submit("volume", 100)
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(listOf("block" to true, "quietHoursOn" to true, "volume" to 100), edits.toList())
  }

  @Test fun failedSaveIsReportedOnceWithoutBlockingOrReplayingLaterSaves() = runBlocking {
    val edits = CopyOnWriteArrayList<Any>()
    val queue = ChimeSettingsWriteQueue { _, value ->
      edits += value
      if (value == "fail") throw IllegalStateException("synthetic")
      true
    }
    queue.submit("volume", "fail")
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(1L, queue.failureCount)
    queue.submit("volume", 70)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(listOf("fail", 70), edits.toList())
    assertEquals(1L, queue.failureCount)
  }
}
