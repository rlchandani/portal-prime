/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.*
import org.junit.Test

class CatalogSnapshotDeliveryTest {
  @Test fun failedNetworkRefreshPreservesTheNewerCachedCatalog() {
    assertEquals(listOf("new-app"),
        retainedCatalogSnapshot(emptyList(), listOf("new-app"), listOf("old-app")))
    assertEquals(listOf("remote-app"),
        retainedCatalogSnapshot(listOf("remote-app"), listOf("cached-app"), listOf("old-app")))
    assertEquals(listOf("bundled-app"),
        retainedCatalogSnapshot(emptyList(), null, listOf("bundled-app")))
  }

  @Test fun rapidReentrySharesOneFetchAndReceivesTheLatestSnapshot() {
    val cache = CatalogSnapshotDelivery<String>()
    val first = mutableListOf<List<String>>()
    val second = mutableListOf<List<String>>()
    val a = cache.subscribe { first.add(it) }
    val b = cache.subscribe { second.add(it) }
    assertTrue(a.startLoad)
    assertFalse(b.startLoad)
    cache.publish(listOf("bundled")).forEach { it(listOf("bundled")) }
    cache.publish(listOf("remote")).forEach { it(listOf("remote")) }
    assertEquals(first, second)
    assertEquals(listOf(listOf("bundled"), listOf("remote")), first)
  }

  @Test fun leavingAScreenRemovesItsInterestWithoutCancellingOtherConsumers() {
    val cache = CatalogSnapshotDelivery<String>()
    val removed = cache.subscribe { fail("Disposed subscriber was notified") }
    val active = mutableListOf<List<String>>()
    cache.subscribe { active.add(it) }
    removed.cancel()
    cache.publish(listOf("updated")).forEach { it(listOf("updated")) }
    assertEquals(listOf(listOf("updated")), active)
  }

  @Test fun laterEntriesCanRenderImmediatelyWhileRefreshing() {
    val cache = CatalogSnapshotDelivery<String>()
    cache.subscribe { }
    cache.publish(listOf("ready"))
    cache.complete()
    val next = cache.subscribe { }
    assertEquals(listOf("ready"), next.snapshot)
    assertTrue(next.startLoad)
    assertFalse(cache.subscribe { }.startLoad)
  }

  @Test fun subscriberJoiningBeforeTerminalCompletionReceivesTheFinalValue() {
    val cache = CatalogSnapshotDelivery<String>()
    cache.subscribe { }
    cache.publish(listOf("bundled"))
    val late = mutableListOf<List<String>>()
    assertFalse(cache.subscribe { late.add(it) }.startLoad)
    val finalValue = listOf("remote")
    cache.complete(finalValue).forEach { it(finalValue) }
    assertEquals(listOf(finalValue), late)
    assertTrue(cache.subscribe { }.startLoad)
  }

  @Test fun differentInputsNeverReceiveAnotherRequestsResults() {
    val requests = CatalogRequestDelivery<List<String>, String>()
    val bundled = mutableListOf<List<String>>()
    val remote = mutableListOf<List<String>>()
    val same = mutableListOf<List<String>>()
    assertTrue(requests.subscribe(listOf("old-app")) { bundled.add(it) }.startLoad)
    assertTrue(requests.subscribe(listOf("old-app", "new-app")) { remote.add(it) }.startLoad)
    assertFalse(requests.subscribe(listOf("old-app")) { same.add(it) }.startLoad)
    requests.complete(listOf("old-app")).forEach { it(listOf("old-update")) }
    assertEquals(bundled, same)
    assertTrue(remote.isEmpty())
    requests.complete(listOf("old-app", "new-app")).forEach { it(listOf("new-update")) }
    assertEquals(listOf(listOf("new-update")), remote)
    assertTrue(requests.subscribe(listOf("old-app")) { }.startLoad)
  }

  @Test fun cancelledRequestInterestDoesNotConsumeAnotherConsumersResult() {
    val requests = CatalogRequestDelivery<String, String>()
    val removed = requests.subscribe("catalog") { fail("Cancelled interest delivered") }
    val active = mutableListOf<List<String>>()
    requests.subscribe("catalog") { active.add(it) }
    removed.cancel()
    requests.complete("catalog").forEach { it(listOf("ready")) }
    assertEquals(listOf(listOf("ready")), active)
  }
}
