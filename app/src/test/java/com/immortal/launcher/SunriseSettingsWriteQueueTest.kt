/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import com.immortal.launcher.settings.IntSpec
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
  private fun prefs(initial: Map<String, Any?> = mapOf("ramp" to 20)): ScreensaverTestPrefs =
    ScreensaverTestPrefs(initial).also {
    `when`(it.context.getSharedPreferences("immortal_sunrise", Context.MODE_PRIVATE)).thenReturn(it)
  }
  private fun domain(onApplied: (Context, Set<String>) -> Unit = { _, _ -> }) = SettingsDomain(
    "sunrise", "Sunrise alarm", SunriseConfig::load, SettingsDomains.sunrise.specs,
    onApplied = onApplied)

  @Test fun validTimePairsUseOneRegistryHookAndPreserveUnrelatedFields() {
    for ((hour, minute) in listOf(7 to 3, 0 to 0, 23 to 59)) {
      val initial = mapOf("enabled" to true, "hour" to 22, "minute" to 17,
        "ramp" to 35, "chime" to false, "days" to setOf("1", "7"), "unrelated" to "retained")
      val prefs = prefs(initial)
      val before = SunriseConfig.load(prefs.context)
      val observed = mutableListOf<Pair<Set<String>, SunriseConfig.Config>>()
      val domain = domain { context, keys -> observed += keys to SunriseConfig.load(context) }
      assertTrue(applySunriseSettingEdit(prefs.context, SunriseSettingEdit.Time(hour, minute), domain))
      val expected = before.copy(hour = hour, minute = minute)
      assertEquals(expected, SunriseConfig.load(prefs.context))
      assertEquals(listOf(setOf("hour", "minute") to expected), observed)
      assertEquals(2, prefs.writeCount)
      assertEquals(initial + mapOf("hour" to hour, "minute" to minute), prefs.getAll())
    }
  }

  @Test fun timePrevalidationUsesTheDomainsIntSpecBounds() {
    val prefs = prefs()
    var hooks = 0
    val specs = SettingsDomains.sunrise.specs.map { spec -> when (spec.key) {
      "hour" -> IntSpec<SunriseConfig.Config>("hour", "Hour", { it.hour },
        SunriseConfig::setHour, min = 8, max = 9)
      "minute" -> IntSpec<SunriseConfig.Config>("minute", "Minute", { it.minute },
        SunriseConfig::setMinute, min = 15, max = 16)
      else -> spec
    } }
    val domain = SettingsDomain("sunrise", "Sunrise alarm", SunriseConfig::load, specs,
      onApplied = { _, _ -> hooks++ })
    assertFalse(applySunriseSettingEdit(prefs.context, SunriseSettingEdit.Time(7, 15), domain))
    assertFalse(applySunriseSettingEdit(prefs.context, SunriseSettingEdit.Time(8, 17), domain))
    assertEquals(0, prefs.writeCount); assertEquals(0, hooks)
    assertTrue(applySunriseSettingEdit(prefs.context, SunriseSettingEdit.Time(8, 15), domain))
    assertEquals(8, SunriseConfig.load(prefs.context).hour)
    assertEquals(15, SunriseConfig.load(prefs.context).minute)
    assertEquals(1, hooks)
  }

  @Test fun timeRequiresBothRegistrySpecsBeforeWritingEitherComponent() {
    for (missing in listOf("hour", "minute")) {
      val prefs = prefs()
      var hooks = 0
      val domain = SettingsDomain("sunrise", "Sunrise alarm", SunriseConfig::load,
        SettingsDomains.sunrise.specs.filterNot { it.key == missing },
        onApplied = { _, _ -> hooks++ })
      assertFalse(applySunriseSettingEdit(prefs.context, SunriseSettingEdit.Time(8, 3), domain))
      assertEquals(0, prefs.writeCount); assertEquals(0, hooks)
    }
  }

  @Test fun invalidTimeComponentRejectsTheWholePairBeforeAnyWriteOrHook() {
    for ((hour, minute) in listOf(-1 to 3, 24 to 3, 7 to -1, 7 to 60,
      -1 to 60, Int.MIN_VALUE to 3, 7 to Int.MAX_VALUE)) {
      val prefs = prefs()
      val before = SunriseConfig.load(prefs.context)
      var hooks = 0
      assertFalse(applySunriseSettingEdit(prefs.context, SunriseSettingEdit.Time(hour, minute),
        domain { _, _ -> hooks++ }))
      assertEquals(before, SunriseConfig.load(prefs.context))
      assertEquals(0, prefs.writeCount); assertEquals(0, hooks)
    }
  }

  @Test fun timeHookObservesTheFinalPairOnceOffCallerThread() = runBlocking {
    val prefs = prefs()
    val before = SunriseConfig.load(prefs.context)
    val caller = Thread.currentThread()
    val observed = CopyOnWriteArrayList<Triple<Set<String>, SunriseConfig.Config, Thread>>()
    val domain = domain { context, keys ->
      observed += Triple(keys, SunriseConfig.load(context), Thread.currentThread())
    }
    val queue = SunriseSettingsWriteQueue { applySunriseSettingEdit(prefs.context, it, domain) }
    queue.submitTime(7, 3)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(1, observed.size)
    assertEquals(setOf("hour", "minute"), observed.single().first)
    assertEquals(before.copy(hour = 7, minute = 3), observed.single().second)
    assertNotSame(caller, observed.single().third)
    assertEquals(2, prefs.writeCount); assertEquals(0L, queue.failureCount)
    assertFalse(queue.lastSaveFailed)
  }

  @Test fun invalidTimeSaveRecordsFailureAndAValidPairRetryClearsIt() = runBlocking {
    val prefs = prefs()
    var hooks = 0
    val queue = SunriseSettingsWriteQueue { applySunriseSettingEdit(prefs.context, it,
      domain { _, _ -> hooks++ }) }
    queue.submitTime(7, 60)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(0, prefs.writeCount); assertEquals(0, hooks)
    assertEquals(1L, queue.failureCount); assertTrue(queue.lastSaveFailed)
    queue.submitTime(7, 3)
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(7, SunriseConfig.load(prefs.context).hour)
    assertEquals(3, SunriseConfig.load(prefs.context).minute)
    assertEquals(1, hooks); assertEquals(1L, queue.failureCount)
    assertFalse(queue.lastSaveFailed)
  }

  @Test fun pendingTimesCoalesceAsWholeImmutablePairsWithScalarAndDayEventOrder() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val edits = CopyOnWriteArrayList<SunriseSettingEdit>()
    val queue = SunriseSettingsWriteQueue { edit ->
      if (edit is SunriseSettingEdit.Scalar && edit.key == "block") {
        started.complete(Unit); check(release.await(3, TimeUnit.SECONDS))
      }
      edits += edit; true
    }
    var hour = 6
    var minute = 10
    val days = mutableSetOf(2, 3)
    try {
      queue.submit("block", true); withTimeout(3000) { started.await() }
      queue.submitTime(hour, minute)
      hour = 22; minute = 55
      repeat(100) { queue.submitTime(it % 24, it % 60) }
      queue.submit("rampMinutes", 25)
      queue.submitDays(days); days.clear(); days += 7
      queue.submitTime(7, 3)
      queue.submit("chime", false)
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(22, hour); assertEquals(55, minute)
    assertEquals(listOf(SunriseSettingEdit.Scalar("block", true),
      SunriseSettingEdit.Scalar("rampMinutes", 25), SunriseSettingEdit.Days(setOf(2, 3)),
      SunriseSettingEdit.Time(7, 3), SunriseSettingEdit.Scalar("chime", false)), edits.toList())
    assertEquals(0L, queue.failureCount)
  }

  @Test fun submittedTimeIsAnImmutableSnapshot() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val edits = CopyOnWriteArrayList<SunriseSettingEdit>()
    val queue = SunriseSettingsWriteQueue { edit ->
      if (edit is SunriseSettingEdit.Scalar && edit.key == "block") {
        started.complete(Unit); check(release.await(3, TimeUnit.SECONDS))
      }
      edits += edit; true
    }
    var hour = 7
    var minute = 3
    try {
      queue.submit("block", true); withTimeout(3000) { started.await() }
      queue.submitTime(hour, minute); hour = 23; minute = 59
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(23, hour); assertEquals(59, minute)
    assertEquals(SunriseSettingEdit.Time(7, 3), edits.last())
  }

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
