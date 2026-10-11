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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SystemTouchSoundsLoadTest {
  @Test fun soundPermissionAndStateReadsRunOffTheInputThread() = runBlocking {
    val caller = Thread.currentThread()
    var reader: Thread? = null
    val loaded = loadSystemTouchSounds { reader = Thread.currentThread(); SystemTouchSoundsSnapshot(true, false) }
    assertEquals(SystemTouchSoundsSnapshot(true, false), loaded)
    assertNotSame(caller, reader)
  }

  @Test fun frameworkFailureDoesNotInventAnEnabledState() = runBlocking {
    assertNull(loadSystemTouchSounds { throw SecurityException() })
  }

  @Test fun cancellationPropagates() = runBlocking {
    try { loadSystemTouchSounds { throw CancellationException() }; fail("Must propagate cancellation") }
    catch (_: CancellationException) { }
  }

  @Test fun departedPaneCannotPublishAnInFlightSystemRead() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    var published = false
    val job = launch {
      loadSystemTouchSounds {
        started.complete(Unit)
        check(release.await(3, TimeUnit.SECONDS))
        SystemTouchSoundsSnapshot(true, true)
      }
      published = true
    }
    try { withTimeout(3000) { started.await() }; job.cancel() }
    finally { release.countDown() }
    withTimeout(3000) { job.join() }
    assertFalse(published)
  }

  @Test fun slowAudioWriteKeepsFinalRequestedValueOffTheInputThread() = runBlocking {
    val caller = Thread.currentThread()
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val applied = CopyOnWriteArrayList<Boolean>()
    var writer: Thread? = null
    val queue = LatestSystemWrites<Unit, Boolean>(keyOf = { Unit }) { enabled ->
      writer = Thread.currentThread()
      if (applied.isEmpty()) { started.complete(Unit); check(release.await(3, TimeUnit.SECONDS)) }
      applied += enabled
    }
    try {
      queue.submit(true)
      withTimeout(3000) { started.await() }
      queue.submit(true); queue.submit(false)
    } finally { release.countDown() }
    withTimeout(3000) { queue.awaitIdle() }
    assertEquals(listOf(true, false), applied.toList())
    assertNotSame(caller, writer)
  }

  @Test fun soundFailureDoesNotBlockLaterWrites() = runBlocking {
    var failed = false
    var enabled = true
    val queue = LatestSystemWrites<Unit, Boolean>(keyOf = { Unit }, onFailure = { failed = true }) {
      if (it) throw SecurityException()
      enabled = it
    }
    queue.submit(true)
    withTimeout(3000) { queue.awaitIdle() }
    queue.submit(false)
    withTimeout(3000) { queue.awaitIdle() }
    assertTrue(failed)
    assertFalse(enabled)
  }
}
