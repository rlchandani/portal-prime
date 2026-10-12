/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
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
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.`when`

class SunriseSettingsWriteQueueTest {
  private fun prefs(): ScreensaverTestPrefs = ScreensaverTestPrefs(mapOf("ramp" to 20)).also {
    `when`(it.context.getSharedPreferences("immortal_sunrise", Context.MODE_PRIVATE)).thenReturn(it)
  }
  private fun domain(onApplied: (Context, Set<String>) -> Unit = { _, _ -> }) = SettingsDomain(
    "sunrise", "Sunrise alarm", SunriseConfig::load, SettingsDomains.sunrise.specs,
    onApplied = onApplied)

  @Test fun actualRampSpecWritesOnlyTheEditedFieldAndSchedulesOffCallerThread() = runBlocking {
    val prefs = prefs()
    val caller = Thread.currentThread()
    var hookThread: Thread? = null
    var hooks = 0
    val domain = domain { context, keys ->
      assertEquals(setOf("rampMinutes"), keys)
      assertEquals(25, SunriseConfig.load(context).rampMinutes)
      hookThread = Thread.currentThread(); hooks++
    }
    val queue = SunriseSettingsWriteQueue { applySunriseSettingEdit(prefs.context, it, domain) }
    queue.submit("rampMinutes", 25)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(25, SunriseConfig.load(prefs.context).rampMinutes)
    assertNotSame(caller, hookThread); assertEquals(1, hooks)
    assertEquals(1, prefs.writeCount); assertEquals(0L, queue.failureCount)
  }

  @Test fun invalidRampIsRejectedWithoutWritesAndLaterValidSaveSucceeds() = runBlocking {
    val prefs = prefs()
    val queue = SunriseSettingsWriteQueue { applySunriseSettingEdit(prefs.context, it, domain()) }
    queue.submit("rampMinutes", 61)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(0, prefs.writeCount); assertEquals(1L, queue.failureCount)
    queue.submit("rampMinutes", 25)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(25, SunriseConfig.load(prefs.context).rampMinutes)
    assertEquals(1, prefs.writeCount)
  }

  @Test fun daysAreStoredBeforeTheSingleRegistryHookWithoutOverwritingOtherFields() = runBlocking {
    val prefs = prefs()
    val caller = Thread.currentThread()
    var hooks = 0
    val domain = domain { context, keys ->
      assertEquals(setOf("enabled"), keys)
      assertEquals(setOf(1, 7), SunriseConfig.load(context).days)
      assertNotSame(caller, Thread.currentThread()); hooks++
    }
    val queue = SunriseSettingsWriteQueue { applySunriseSettingEdit(prefs.context, it, domain) }
    queue.submitDays(setOf(1, 7))
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(1, hooks); assertEquals(20, SunriseConfig.load(prefs.context).rampMinutes)
    assertFalse(SunriseConfig.load(prefs.context).enabled)
    assertEquals(0L, queue.failureCount)
  }

  @Test fun invalidDaysAreRejectedWithoutChangingTheAlarm() = runBlocking {
    val prefs = prefs()
    var hooks = 0
    val queue = SunriseSettingsWriteQueue { applySunriseSettingEdit(prefs.context, it,
      domain { _, _ -> hooks++ }) }
    queue.submitDays(setOf(0, 8))
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(0, prefs.writeCount); assertEquals(0, hooks)
    assertEquals(1L, queue.failureCount)
  }

  @Test fun submittedDaysAreAnImmutableSnapshot() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val edits = CopyOnWriteArrayList<SunriseSettingEdit>()
    val queue = SunriseSettingsWriteQueue { edit ->
      if (edit is SunriseSettingEdit.Scalar && edit.key == "block") {
        started.complete(Unit); check(release.await(3, TimeUnit.SECONDS))
      }
      edits += edit; true
    }
    val days = mutableSetOf(2, 3)
    try {
      queue.submit("block", true); withTimeout(3000) { started.await() }
      queue.submitDays(days); days.clear(); days += 7
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(setOf(2, 3), (edits.last() as SunriseSettingEdit.Days).values)
  }

  @Test fun acceptedWriteSurvivesPaneReadbackCancellation() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    var saved = false
    var published = false
    val queue = SunriseSettingsWriteQueue {
      started.complete(Unit); check(release.await(3, TimeUnit.SECONDS)); saved = true; true
    }
    try {
      queue.submit("rampMinutes", 25); withTimeout(3000) { started.await() }
      val pane = launch { queue.awaitIdle(); published = true }
      pane.cancelAndJoin(); assertFalse(saved); assertFalse(published)
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertTrue(saved); assertFalse(published)
  }

  @Test fun pendingEditsCoalesceByFieldAndKeepFinalEventOrder() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val edits = CopyOnWriteArrayList<SunriseSettingEdit>()
    val queue = SunriseSettingsWriteQueue { edit ->
      if (edit is SunriseSettingEdit.Scalar && edit.key == "block") {
        started.complete(Unit); check(release.await(3, TimeUnit.SECONDS))
      }
      edits += edit; true
    }
    try {
      queue.submit("block", true); withTimeout(3000) { started.await() }
      repeat(100) { queue.submit("rampMinutes", it) }
      queue.submitDays(setOf(2)); queue.submit("rampMinutes", 25)
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(listOf(SunriseSettingEdit.Scalar("block", true),
      SunriseSettingEdit.Days(setOf(2)), SunriseSettingEdit.Scalar("rampMinutes", 25)), edits.toList())
  }

  @Test fun recreatedPaneReadsTheWritersFailureAndAValidRetryClearsIt() = runBlocking {
    val queue = SunriseSettingsWriteQueue { edit -> (edit as SunriseSettingEdit.Scalar).value != 0 }
    queue.submit("rampMinutes", 0); withTimeout(3000) { queue.awaitIdle() }
    val reopened = SettingsSaveState().completeRead(0, queue.failureCount, queue.lastSaveFailed)
    assertTrue(reopened.failed); assertFalse(reopened.saving)
    queue.submit("rampMinutes", 25); withTimeout(3000) { queue.awaitIdle() }
    val reopenedAgain = SettingsSaveState().completeRead(0, queue.failureCount, queue.lastSaveFailed)
    assertFalse(reopenedAgain.failed); assertFalse(reopenedAgain.saving)
  }

  @Test fun failuresAreNotReplayedAndDoNotPreventTheNextSave() = runBlocking {
    val edits = CopyOnWriteArrayList<SunriseSettingEdit>()
    val queue = SunriseSettingsWriteQueue { edit ->
      edits += edit
      if ((edit as SunriseSettingEdit.Scalar).value == 0) error("synthetic")
      true
    }
    queue.submit("rampMinutes", 0); withTimeout(3000) { queue.awaitIdle() }
    queue.submit("rampMinutes", 25); withTimeout(3000) { queue.awaitIdle() }
    assertEquals(2, edits.size); assertEquals(1L, queue.failureCount)
  }
}
