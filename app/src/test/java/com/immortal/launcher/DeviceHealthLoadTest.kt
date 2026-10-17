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
  @Test fun recheckRetainsCompletedPermissionSnapshot() {
    val saved = snapshot()
    val state = DeviceHealthStatus().received(DeviceHealthLoad.Ready(saved)).beginCheck()
    assertSame(saved, state.snapshot)
    assertTrue(state.checking)
    assertFalse(state.failed)
    assertFalse(state.canRemoveAdmin)
  }

  @Test fun failedRecheckRetainsResultsWithoutClaimingFreshPermissions() {
    val saved = snapshot().copy(adminActive = true)
    val state = DeviceHealthStatus().received(DeviceHealthLoad.Ready(saved)).beginCheck()
        .received(DeviceHealthLoad.Unavailable)
    assertSame(saved, state.snapshot)
    assertFalse(state.checking)
    assertTrue(state.failed)
    assertFalse(state.canRemoveAdmin)
  }

  @Test fun successfulRetryReplacesStaleResultsAndClearsFailure() {
    val previous = snapshot()
    val latest = snapshot().copy(adminActive = true)
    val state = DeviceHealthStatus().received(DeviceHealthLoad.Ready(previous))
        .received(DeviceHealthLoad.Unavailable).beginCheck().received(DeviceHealthLoad.Ready(latest))
    assertSame(latest, state.snapshot)
    assertFalse(state.checking)
    assertFalse(state.failed)
    assertTrue(state.canRemoveAdmin)
  }

  @Test fun initialFailureHasNoPermissionSnapshot() {
    val state = DeviceHealthStatus().received(DeviceHealthLoad.Unavailable)
    assertNull(state.snapshot)
    assertTrue(state.failed)
    assertFalse(state.checking)
    assertFalse(state.canRemoveAdmin)
  }

  private fun controlsSnapshot(connected: Boolean = false, canRepair: Boolean = true) =
      DevicePermissions.Snapshot(listOf(DevicePermissions.controlCheck(connected, canRepair)), false)

  @Test fun completedDisconnectedControlsAllowReconnect() {
    val state = DeviceHealthStatus().received(DeviceHealthLoad.Ready(controlsSnapshot()))
    assertTrue(state.canReconnectControls)
  }

  @Test fun checkingRetainedControlsCannotAuthorizeReconnect() {
    val saved = controlsSnapshot()
    val state = DeviceHealthStatus().received(DeviceHealthLoad.Ready(saved))
        .received(DeviceHealthLoad.Checking)
    assertSame(saved, state.snapshot)
    assertFalse(state.canReconnectControls)
  }

  @Test fun failedRetainedControlsCannotAuthorizeReconnect() {
    val saved = controlsSnapshot()
    val state = DeviceHealthStatus().received(DeviceHealthLoad.Ready(saved)).beginCheck()
        .received(DeviceHealthLoad.Unavailable)
    assertSame(saved, state.snapshot)
    assertFalse(state.canReconnectControls)
  }

  @Test fun noCompletedSnapshotCannotAuthorizeReconnect() {
    assertFalse(DeviceHealthStatus().canReconnectControls)
    assertFalse(DeviceHealthStatus().received(DeviceHealthLoad.Unavailable).canReconnectControls)
  }

  @Test fun connectedOrUnrepairableControlsCannotAuthorizeReconnect() {
    for (snapshot in listOf(controlsSnapshot(connected = true), controlsSnapshot(canRepair = false), snapshot())) {
      assertFalse(DeviceHealthStatus().received(DeviceHealthLoad.Ready(snapshot)).canReconnectControls)
    }
  }

  @Test fun repairMarkerOnGrantedCheckCannotAuthorizeReconnect() {
    val check = DevicePermissions.controlCheck(connected = false, canRepair = true).copy(granted = true)
    val snapshot = DevicePermissions.Snapshot(listOf(check), false)
    assertFalse(DeviceHealthStatus().received(DeviceHealthLoad.Ready(snapshot)).canReconnectControls)
  }

  @Test fun retryRequiresFreshDisconnectedResultBeforeAllowingReconnect() {
    val failed = DeviceHealthStatus().received(DeviceHealthLoad.Ready(controlsSnapshot()))
        .received(DeviceHealthLoad.Unavailable)
    val retrying = failed.beginCheck()
    assertFalse(retrying.canReconnectControls)
    assertTrue(retrying.received(DeviceHealthLoad.Ready(controlsSnapshot())).canReconnectControls)
    assertFalse(retrying.received(DeviceHealthLoad.Ready(controlsSnapshot(connected = true))).canReconnectControls)
  }

}
