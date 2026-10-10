/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

/** Real resolver and file identity; only decoder submission is intercepted. No image decoding. */
class EngineScaledCacheResolutionTest {
  private class Fixture(val root: File, maximum: Int) {
    val original = File(root, "synthetic-original.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    val dir = File(root, "derivatives").apply { mkdir() }
    val cache = EngineScaledCache(dir, maximum)
    val observations = ArrayList<List<Any>>()
    val measurement = EngineImageResolutionMeasurement { result, requested, delivered, cap ->
      observations.add(listOf(result, requested, delivered, cap))
    }
    fun ready(edge: Int): File = File(dir, EngineScaledCache.keyFor(original.path,
        original.length(), original.lastModified(), edge)).apply { writeBytes(byteArrayOf(4)) }
  }

  private fun fixture(maximum: Int = 2400, block: (Fixture, () -> Int) -> Unit) {
    val root = Files.createTempDirectory("native-resolution-test").toFile()
    Mockito.mockConstruction(EngineDerivativeWork.Owner::class.java).use { owners ->
      val f = Fixture(root, maximum)
      val owner = owners.constructed().single()
      val submissions = { Mockito.mockingDetails(owner).invocations.count { it.method.name == "submit" } }
      try { block(f, submissions) } finally { f.cache.cancel(); root.deleteRecursively() }
    }
  }

  @Test fun requestedHitRecordsTheChosenTierAndDoesNotSubmitDecoding() = fixture { f, submitted ->
    val chosen = f.ready(1200)
    assertEquals(chosen, f.cache.resolveAndRequest(f.original.path, 1000, f.measurement))
    assertEquals(listOf(listOf(EngineImageResolutionResult.REQUESTED_TIER_READY, 1200, 1200, 2400)), f.observations)
    assertEquals(0, submitted())
  }

  @Test fun largerHitRecordsFirstReadyLargerTierAndSubmitsOnlyRequestedWork() = fixture { f, submitted ->
    val chosen = f.ready(1800); f.ready(2400)
    assertEquals(chosen, f.cache.resolveAndRequest(f.original.path, 601, f.measurement))
    assertEquals(listOf(listOf(EngineImageResolutionResult.LARGER_TIER_READY, 1200, 1800, 2400)), f.observations)
    assertEquals(1, submitted())
  }

  @Test fun customMaximumHitUsesActualCustomDerivative() = fixture(1600) { f, submitted ->
    val chosen = f.ready(1600)
    assertEquals(chosen, f.cache.resolveAndRequest(f.original.path, 1000, f.measurement))
    assertEquals(listOf(listOf(EngineImageResolutionResult.LARGER_TIER_READY, 1200, 1600, 1600)), f.observations)
    assertEquals(1, submitted())
  }

  @Test fun originalFallbackIncludesSmallOriginalAndNeverServesSmallerOrEmptyDerivative() = fixture { f, submitted ->
    f.ready(600); f.ready(1200).writeBytes(byteArrayOf())
    assertNull(f.cache.resolveAndRequest(f.original.path, 1000, f.measurement))
    assertEquals(listOf(listOf(EngineImageResolutionResult.ORIGINAL_FALLBACK, 1200, 0, 2400)), f.observations)
    assertEquals(1, submitted())
  }

  @Test fun missingOriginalRecordsMissingAndSubmitsNothing() = fixture { f, submitted ->
    f.original.delete()
    assertNull(f.cache.resolveAndRequest(f.original.path, 1000, f.measurement))
    assertEquals(listOf(listOf(EngineImageResolutionResult.MISSING_ORIGINAL, 1200, 0, 2400)), f.observations)
    assertEquals(0, submitted())
  }

  @Test fun observationDoesNotRelookupFilesAfterTheChosenBranch() = fixture { f, submitted ->
    val chosen = f.ready(1200)
    val measurement = EngineImageResolutionMeasurement { result, requested, delivered, cap ->
      chosen.delete(); f.ready(2400)
      f.measurement.record(result, requested, delivered, cap)
    }
    assertEquals(chosen, f.cache.resolveAndRequest(f.original.path, 1000, measurement))
    assertEquals(EngineImageResolutionResult.REQUESTED_TIER_READY, f.observations.single()[0])
    assertEquals(1200, f.observations.single()[2]); assertEquals(0, submitted())
  }

  @Test fun throwingInstrumentationPreservesEveryResultAndAddsZeroDecoderSubmissions() {
    for (branch in listOf("requested", "larger", "original", "missing")) fixture { f, submitted ->
      when (branch) {
        "requested" -> f.ready(1200)
        "larger" -> f.ready(2400)
        "missing" -> f.original.delete()
      }
      val baseline = f.cache.resolveAndRequest(f.original.path, 1000)
      val baselineSubmissions = submitted()
      var calls = 0
      val throwing = EngineImageResolutionMeasurement { _, _, _, _ -> calls++; throw AssertionError("synthetic sink") }
      val measured = f.cache.resolveAndRequest(f.original.path, 1000, throwing)
      assertEquals(branch, baseline, measured)
      assertEquals(branch, 1, calls)
      assertEquals(branch, baselineSubmissions, submitted() - baselineSubmissions)
    }
  }
}
