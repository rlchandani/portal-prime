/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SpeedTestPresentationTest {
  private val valid = SpeedTestValues(12.4, 1.5, 82.3, 14.7)
  private fun rows(status: SpeedTestDisplayStatus, mask: Int = 0, values: SpeedTestValues = valid) =
      SpeedTestPresentation.rows(values, status, mask, Locale.US).associate { it.label to it.value }

  @Test fun readyHasNoMeasurements() {
    assertEquals(listOf("Ping", "Jitter", "Download", "Upload"),
        SpeedTestPresentation.rows(SpeedTestValues(), SpeedTestDisplayStatus.READY, 0).map { it.label })
    assertTrue(rows(SpeedTestDisplayStatus.READY, values = SpeedTestValues()).values.all { it == "—" })
    assertNull(SpeedTestPresentation.caption(SpeedTestDisplayStatus.READY))
  }
  @Test fun liveValuesAreExplicitEstimates() {
    assertEquals("82.3 Mbps · Live", rows(SpeedTestDisplayStatus.RUNNING)["Download"])
    assertEquals("Live estimates. The test is still running.", SpeedTestPresentation.caption(SpeedTestDisplayStatus.RUNNING))
  }
  @Test fun liveNotYetMeasuredDoesNotInventZeroSpeed() {
    assertTrue(rows(SpeedTestDisplayStatus.RUNNING, values = SpeedTestValues()).values.all { it == "—" })
  }
  @Test fun stoppedValuesAreExplicitlyPartial() {
    assertEquals("14.7 Mbps · Partial", rows(SpeedTestDisplayStatus.STOPPED)["Upload"])
    assertEquals("Test stopped. These are partial estimates.", SpeedTestPresentation.caption(SpeedTestDisplayStatus.STOPPED))
  }
  @Test fun stoppedUnattemptedLegIsNotMeasured() {
    assertEquals("Not measured", rows(SpeedTestDisplayStatus.STOPPED, values = valid.copy(upMbps = 0.0))["Upload"])
  }
  @Test fun failedUploadDoesNotLookLikeCompletedLiveSample() {
    val result = rows(SpeedTestDisplayStatus.INCOMPLETE, SpeedTestPresentation.UPLOAD)
    assertEquals("Unavailable", result["Upload"])
    assertEquals("82.3 Mbps", result["Download"])
    assertEquals("12.4 ms", result["Ping"])
  }
  @Test fun failedPingAlsoHidesDependentJitter() {
    val result = rows(SpeedTestDisplayStatus.INCOMPLETE, SpeedTestPresentation.PING)
    assertEquals("Unavailable", result["Ping"]); assertEquals("Unavailable", result["Jitter"])
    assertEquals("14.7 Mbps", result["Upload"])
  }
  @Test fun failedDownloadRetainsSuccessfulUpload() {
    val result = rows(SpeedTestDisplayStatus.INCOMPLETE, SpeedTestPresentation.DOWNLOAD)
    assertEquals("Unavailable", result["Download"]); assertEquals("14.7 Mbps", result["Upload"])
  }
  @Test fun multipleFailedLegsHideOnlyThoseMeasurements() {
    val result = rows(SpeedTestDisplayStatus.INCOMPLETE, SpeedTestPresentation.DOWNLOAD or SpeedTestPresentation.UPLOAD)
    assertEquals("Unavailable", result["Download"]); assertEquals("Unavailable", result["Upload"])
    assertEquals("1.5 ms", result["Jitter"])
  }
  @Test fun incompleteExplainsRetainedMeasurements() {
    assertEquals("Some measurements are unavailable. Completed results are shown.",
        SpeedTestPresentation.caption(SpeedTestDisplayStatus.INCOMPLETE))
  }
  @Test fun completedKeepsOriginalUnitsAndFormatting() {
    assertEquals(mapOf("Ping" to "12.4 ms", "Jitter" to "1.5 ms", "Download" to "82.3 Mbps", "Upload" to "14.7 Mbps"), rows(SpeedTestDisplayStatus.COMPLETE))
  }
  @Test fun validZeroJitterIsRetained() {
    val values = valid.copy(jitterMs = 0.0)
    assertEquals(0, SpeedTestPresentation.effectiveMissingMask(emptySet(), values))
    assertEquals("0.0 ms", rows(SpeedTestDisplayStatus.COMPLETE, values = values)["Jitter"])
  }
  @Test fun zeroThroughputCannotComplete() {
    assertEquals(SpeedTestPresentation.DOWNLOAD or SpeedTestPresentation.UPLOAD,
        SpeedTestPresentation.effectiveMissingMask(emptySet(), valid.copy(downMbps = 0.0, upMbps = 0.0)))
  }
  @Test fun nonfiniteThroughputCannotComplete() {
    for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
      assertEquals(SpeedTestPresentation.UPLOAD,
          SpeedTestPresentation.effectiveMissingMask(emptySet(), valid.copy(upMbps = bad)))
      assertEquals("Unavailable", rows(SpeedTestDisplayStatus.COMPLETE, values = valid.copy(upMbps = bad))["Upload"])
    }
  }
  @Test fun invalidJitterMakesLatencyUnavailable() {
    for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
      assertEquals(SpeedTestPresentation.PING,
          SpeedTestPresentation.effectiveMissingMask(emptySet(), valid.copy(jitterMs = bad)))
      assertEquals("Unavailable", rows(SpeedTestDisplayStatus.COMPLETE, values = valid.copy(jitterMs = bad))["Jitter"])
    }
  }
  @Test fun suppliedFailuresRemainAuthoritativeEvenWithPositiveValues() {
    assertEquals(SpeedTestPresentation.UPLOAD,
        SpeedTestPresentation.effectiveMissingMask(setOf(SpeedTest.Phase.UPLOAD), valid))
  }
  @Test fun unknownFinishedPhaseCannotDeclareComplete() {
    assertEquals(SpeedTestPresentation.ALL,
        SpeedTestPresentation.effectiveMissingMask(setOf(SpeedTest.Phase.DONE), valid))
  }
  @Test fun singleFailedUploadGetsSpecificRecovery() {
    assertEquals("Upload couldn’t finish. Check your connection and run again.", SpeedTestPresentation.failureText(SpeedTestPresentation.UPLOAD))
  }
  @Test fun multipleFailureMessageNamesBothLegs() {
    assertEquals("Download and upload couldn’t finish. Check your connection and run again.",
        SpeedTestPresentation.failureText(SpeedTestPresentation.DOWNLOAD or SpeedTestPresentation.UPLOAD))
  }
  @Test fun latencyRecoveryIncludesJitterDependency() {
    assertEquals("Ping and jitter couldn’t finish. Check your connection and run again.",
        SpeedTestPresentation.failureText(SpeedTestPresentation.PING))
  }
  @Test fun formattingRetainsDeviceLocale() {
    assertEquals("82,3 Mbps", SpeedTestPresentation.rows(valid, SpeedTestDisplayStatus.COMPLETE, 0, Locale.GERMANY).first { it.label == "Download" }.value)
  }
  @Test fun renderingDoesNotMutateCapturedMeasurements() {
    val before = valid.copy()
    rows(SpeedTestDisplayStatus.INCOMPLETE, SpeedTestPresentation.ALL)
    assertEquals(before, valid)
  }
}
