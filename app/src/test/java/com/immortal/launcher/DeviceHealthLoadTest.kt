/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DeviceHealthLoadTest {
  private fun snapshot() = DevicePermissions.Snapshot(
      listOf(DevicePermissions.Check("Screen off", "", false, "", "")), false)

  @Test fun permissionQueriesRunOffTheCallerThread() = runBlocking {
    val caller = Thread.currentThread()
    var reader: Thread? = null
    val snapshot = snapshot()
    val result = loadDeviceHealth {
      reader = Thread.currentThread()
      snapshot
    }
    assertNotSame(caller, reader)
    assertEquals(DeviceHealthLoad.Ready(snapshot), result)
  }

  @Test fun frameworkFailureDoesNotPublishHealthyPermissions() = runBlocking {
    assertEquals(DeviceHealthLoad.Unavailable, loadDeviceHealth { throw SecurityException() })
  }

  @Test fun cancellationPropagatesRatherThanPublishingFailure() = runBlocking {
    try {
      loadDeviceHealth { throw CancellationException("departed") }
      fail("Cancellation must propagate")
    } catch (_: CancellationException) { }
  }

  @Test fun departingScreenCannotPublishAnInFlightBinderResult() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    var published = false
    val job = launch {
      loadDeviceHealth {
        started.complete(Unit)
        check(release.await(3, TimeUnit.SECONDS))
        snapshot()
      }
      published = true
    }
    try {
      withTimeout(3000) { started.await() }
      job.cancel()
    } finally {
      release.countDown()
    }
    withTimeout(3000) { job.join() }
    assertFalse(published)
  }
}
