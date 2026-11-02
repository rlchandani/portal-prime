/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bulk update receipts and discovery completion, without installers or Android Context. */
class FleetUpdateResultsTest {

  private fun runUpdates(outcomes: List<Pair<String, String>>): JSONObject {
    val targets = outcomes.map { it.first }
    val answers = outcomes.toMap()
    val calls = mutableListOf<String>()
    val result =
        FleetRoutes.updateResult(targets) { pkg ->
          calls.add(pkg)
          answers.getValue(pkg)
        }

    assertEquals("Install each selected package once, in order, without retries", targets, calls)
    val updated = result.getJSONArray("updated")
    assertEquals(outcomes.size, updated.length())
    outcomes.forEachIndexed { index, (pkg, outcome) ->
      val receipt = updated.getJSONObject(index)
      assertEquals("Package at index $index", pkg, receipt.getString("packageName"))
      assertEquals("Exact result at index $index", outcome, receipt.getString("result"))
    }
    return result
  }

  private fun assertSummary(
      result: JSONObject,
      ok: Boolean,
      count: Int,
      installedCount: Int,
      failedCount: Int,
  ) {
    assertTrue("ok must be a literal JSON Boolean", result.get("ok") is Boolean)
    assertEquals(ok, result.get("ok"))
    assertEquals(count, result.getInt("count"))
    assertTrue("installedCount must be present", result.has("installedCount"))
    assertTrue("failedCount must be present", result.has("failedCount"))
    assertEquals(installedCount, result.getInt("installedCount"))
    assertEquals(failedCount, result.getInt("failedCount"))
  }

  @Test
  fun allInstalledReportsLiteralSuccessAndExactOrderedReceipts() {
    val result =
        runUpdates(
            listOf(
                "org.public.zebra" to "installed",
                "org.public.alpha" to "installed",
                "org.public.middle" to "installed"))
    assertSummary(result, ok = true, count = 3, installedCount = 3, failedCount = 0)
  }

  @Test
  fun mixedResultsReportFailureAndContinueAfterFirstFailureWithoutRetry() {
    val result =
        runUpdates(
            listOf(
                "org.public.zebra" to "failed",
                "org.public.alpha" to "installed",
                "org.public.middle" to "timeout"))
    assertSummary(result, ok = false, count = 3, installedCount = 1, failedCount = 2)
  }

  @Test
  fun allFailedReportsFailureAndPreservesEveryOutcome() {
    val result =
        runUpdates(
            listOf(
                "org.public.zebra" to "busy",
                "org.public.alpha" to "paused",
                "org.public.middle" to "timeout"))
    assertSummary(result, ok = false, count = 3, installedCount = 0, failedCount = 3)
  }

  @Test
  fun unknownProgressBusyPausedAndTimeoutNeverCountAsInstalled() {
    for (outcome in
        listOf(
            "unknown_result",
            "catalog_entry_missing",
            "Downloading…",
            "Installing…",
            "Resolving…",
            "busy",
            "paused",
            "timeout",
            "failed",
            "installed_pending",
            "")) {
      val result = runUpdates(listOf("org.public.app" to outcome))
      assertSummary(result, ok = false, count = 1, installedCount = 0, failedCount = 1)
    }
  }

  @Test
  fun emptyTargetsAreCompletedSuccessWithoutCallingInstaller() {
    val result =
        FleetRoutes.updateResult(emptyList()) {
          throw AssertionError("An empty selection must not invoke an installer")
        }
    assertSummary(result, ok = true, count = 0, installedCount = 0, failedCount = 0)
    assertEquals(0, result.getJSONArray("updated").length())
  }

  @Test
  fun successfulFirstPackageDoesNotHideLaterFailure() {
    val result =
        runUpdates(
            listOf(
                "org.public.zebra" to "installed",
                "org.public.alpha" to "failed"))
    assertSummary(result, ok = false, count = 2, installedCount = 1, failedCount = 1)
  }

  @Test
  fun incompleteDiscoveryWithEmptyMapIsUnconfirmed() {
    assertNull(FleetRoutes.completedUpdates(completed = false, updates = emptyMap()))
  }

  @Test
  fun incompleteDiscoveryWithPartialResultsIsUnconfirmed() {
    val partial = linkedMapOf("org.public.zebra" to 2L, "org.public.alpha" to 4_294_967_296L)
    assertNull(FleetRoutes.completedUpdates(completed = false, updates = partial))
    assertEquals(
        linkedMapOf("org.public.zebra" to 2L, "org.public.alpha" to 4_294_967_296L), partial)
  }

  @Test
  fun completedDiscoveryPreservesSuppliedPackagesAndLongVersions() {
    val updates = linkedMapOf("org.public.zebra" to 2L, "org.public.alpha" to 4_294_967_296L)
    val result = FleetRoutes.completedUpdates(completed = true, updates = updates)
    assertEquals(updates, result)
  }

  @Test
  fun completedDiscoveryWithNoUpdatesIsSuccessfulEmptyResult() {
    assertEquals(
        emptyMap<String, Long>(),
        FleetRoutes.completedUpdates(completed = true, updates = emptyMap()))
  }
}
