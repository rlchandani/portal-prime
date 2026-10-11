/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import com.immortal.launcher.settings.NavSpec
import com.immortal.launcher.settings.SettingsDomains
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingNavigationValueTest {
  @Test fun serviceBackedLabelGetterRunsOffTheCallerThread() = runBlocking {
    val caller = Thread.currentThread()
    var reader: Thread? = null
    val result = loadSettingNavigationValue { reader = Thread.currentThread(); "Opens selected app" }
    assertEquals(SettingNavigationValue.Ready("Opens selected app"), result)
    assertNotSame(caller, reader)
  }

  @Test fun onlyTheServiceBackedNavigationValueUsesTheWorkerPolicy() {
    val navigation = SettingsDomains.screensaver.specs.filterIsInstance<NavSpec<*>>()
    assertEquals(setOf("dismissTarget"), navigation.filter { it.valueOnWorker }.map { it.key }.toSet())
  }

  @Test fun serviceFailureDoesNotInventAResolvedDestination() = runBlocking {
    assertEquals(SettingNavigationValue.Unavailable, loadSettingNavigationValue { throw SecurityException() })
  }

  @Test fun cancellationPropagates() = runBlocking {
    try { loadSettingNavigationValue { throw CancellationException() }; fail("Must propagate cancellation") }
    catch (_: CancellationException) { }
  }

  @Test fun departedPaneCannotPublishAnInFlightPackageLabel() = runBlocking {
    val started = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    var published = false
    val job = launch {
      loadSettingNavigationValue {
        started.complete(Unit)
        check(release.await(3, TimeUnit.SECONDS))
        "Opens selected app"
      }
      published = true
    }
    try { withTimeout(3000) { started.await() }; job.cancel() }
    finally { release.countDown() }
    withTimeout(3000) { job.join() }
    assertFalse(published)
  }
}
