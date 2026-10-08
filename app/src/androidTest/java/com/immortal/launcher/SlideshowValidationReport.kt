/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.content.Context
import android.os.Bundle
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Reads only test-owned aggregates, after measurement has finished. */
internal object SlideshowValidationReport {
  private val runPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

  fun read(context: Context, runId: String): Bundle {
    require(runPattern.matches(runId))
    val folder = File(context.cacheDir, "portal-ux-e2e/slideshow-$runId")
    val reportFile = File(folder, "private.json")
    require(reportFile.length() in 1..256_000)
    val report = JSONObject(reportFile.readText())
    check(report.getString("phase") == "cleanup")
    val samplesFile = File(folder, "samples-private.jsonl")
    require(samplesFile.length() in 1..16_000_000)
    val samples = ArrayList<JSONObject>()
    samplesFile.useLines { lines ->
      lines.forEach { line ->
        require(samples.size < 800 && line.length <= 24_000)
        val sample = JSONObject(line)
        if (sample.optString("phase") == "soak") samples.add(sample)
      }
    }
    val windows = JSONArray()
    if (samples.isNotEmpty()) {
      val begin = samples.first().getLong("elapsedMs")
      samples.groupBy { ((it.getLong("elapsedMs") - begin) / 900_000).toInt() }
          .toSortedMap().forEach { (index, window) ->
            val values = window.map { it.getJSONObject("memory").getLong("processPssKiB") }.sorted()
            windows.put(JSONObject().put("fromMinute", index * 15)
                .put("samples", values.size).put("pssKiBMin", values.first())
                .put("pssKiBMedian", values[values.size / 2]).put("pssKiBMax", values.last())
                .put("pssKiBMean", values.average()))
          }
    }
    val summary = JSONObject().put("completed", report.optBoolean("completed"))
        .put("soakSampleCount", samples.size).put("memoryWindows", windows)
        .put("memoryScope", "App process PSS; includes SDK, native, Java and reported graphics memory")
        .put("frames", report.getJSONObject("frames"))
        .put("measurements", report.getJSONObject("measurements"))
    return Bundle().apply { putString("slideshowValidation", summary.toString()) }
  }

  /** Keep aggregate reports, remove only generated/licensed fixture directories. */
  fun cleanupFixtures(context: Context): Bundle {
    var removed = 0
    File(context.cacheDir, "portal-ux-e2e").listFiles().orEmpty().forEach { folder ->
      val runId = folder.name.removePrefix("slideshow-")
      if (folder.isDirectory && folder.name.startsWith("slideshow-") && runPattern.matches(runId)) {
        val report = File(folder, "private.json")
        if (report.isFile && report.length() in 1..256_000 &&
            JSONObject(report.readText()).optString("phase") == "cleanup") {
          for (name in listOf("fixtures", "contract-storage")) {
            val generated = File(folder, name)
            if (generated.exists()) {
              check(generated.canonicalFile.parentFile == folder.canonicalFile)
              check(generated.deleteRecursively())
              removed++
            }
          }
        }
      }
    }
    return Bundle().apply { putInt("testFixtureDirectoriesRemoved", removed) }
  }
}
