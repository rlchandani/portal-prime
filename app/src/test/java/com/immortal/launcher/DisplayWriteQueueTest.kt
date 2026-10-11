/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DisplayWriteQueueTest {
  @Test fun deviceWritesDoNotRunOnTheInputThread() = runBlocking {
    val caller = Thread.currentThread()
    var writer: Thread? = null
    var brightness = 0
    val queue = DisplayWriteQueue { edit ->
      writer = Thread.currentThread()
      brightness = (edit as DisplayEdit.Brightness).percent
    }
    queue.submit(DisplayEdit.Brightness(62))
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(62, brightness)
    assertNotSame(caller, writer)
  }

  @Test fun blockedProviderCoalescesDragAndKeepsTheFinalValue() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val applied = CopyOnWriteArrayList<Int>()
    val queue = DisplayWriteQueue { edit ->
      val value = (edit as DisplayEdit.Brightness).percent
      if (value == 1) { started.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }
      applied += value
    }
    try {
      queue.submit(DisplayEdit.Brightness(1))
      withTimeout(3000) { started.await() }
      for (value in 2..100) queue.submit(DisplayEdit.Brightness(value))
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(listOf(1, 100), applied.toList())
  }

  @Test fun laterManualBrightnessWinsOverAnEarlierAutoChoice() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    var brightness = 0
    var automatic = false
    val queue = DisplayWriteQueue { edit ->
      when (edit) {
        is DisplayEdit.Volume -> { started.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }
        is DisplayEdit.Brightness -> { brightness = edit.percent; automatic = false }
        is DisplayEdit.AutoBrightness -> automatic = edit.enabled
      }
    }
    try {
      queue.submit(DisplayEdit.Volume(1))
      withTimeout(3000) { started.await() }
      queue.submit(DisplayEdit.Brightness(40))
      queue.submit(DisplayEdit.AutoBrightness(true))
      queue.submit(DisplayEdit.Brightness(70))
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(70, brightness)
    assertFalse(automatic)
  }

  @Test fun laterAutoChoiceWinsOverManualBrightness() = runBlocking {
    var automatic = false
    val queue = DisplayWriteQueue { edit ->
      when (edit) {
        is DisplayEdit.AutoBrightness -> automatic = edit.enabled
        is DisplayEdit.Brightness -> automatic = false
        else -> Unit
      }
    }
    queue.submit(DisplayEdit.Brightness(60))
    queue.submit(DisplayEdit.AutoBrightness(true))
    withTimeout(3000) { queue.awaitIdle() }
    assertTrue(automatic)
  }

  @Test fun volumeAndBrightnessBothRetainTheirLatestPendingValue() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    var brightness = 0
    var volume = 0
    val queue = DisplayWriteQueue { edit ->
      when (edit) {
        is DisplayEdit.AutoBrightness -> { started.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }
        is DisplayEdit.Brightness -> brightness = edit.percent
        is DisplayEdit.Volume -> volume = edit.percent
      }
    }
    try {
      queue.submit(DisplayEdit.AutoBrightness(false))
      withTimeout(3000) { started.await() }
      queue.submit(DisplayEdit.Brightness(40)); queue.submit(DisplayEdit.Volume(20))
      queue.submit(DisplayEdit.Brightness(75)); queue.submit(DisplayEdit.Volume(35))
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(75, brightness)
    assertEquals(35, volume)
  }

  @Test fun failedWriteDoesNotBlockLaterEditsOrResumeReads() = runBlocking {
    var failure: Exception? = null
    var volume = 0
    val queue = DisplayWriteQueue(onFailure = { failure = it }) { edit ->
      if (edit is DisplayEdit.Brightness) throw SecurityException("permission removed")
      volume = (edit as DisplayEdit.Volume).percent
    }
    queue.submit(DisplayEdit.Brightness(60))
    queue.submit(DisplayEdit.Volume(30))
    withTimeout(3000) { queue.awaitIdle() }
    assertTrue(failure is SecurityException)
    assertEquals(30, volume)
  }

  @Test fun awaitIdleWaitsForTheFinalWriteBeforeAResumeRead() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    var written = 0
    var read: Int? = null
    val queue = DisplayWriteQueue { edit ->
      started.complete(Unit)
      check(release.await(3, TimeUnit.SECONDS))
      written = (edit as DisplayEdit.Volume).percent
    }
    queue.submit(DisplayEdit.Volume(44))
    withTimeout(3000) { started.await() }
    val reader = launch { queue.awaitIdle(); read = written }
    try {
      kotlinx.coroutines.yield()
      assertNull(read)
    } finally { release.countDown() }
    withTimeout(3000) { reader.join() }
    assertEquals(44, read)
  }
}
