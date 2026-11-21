/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import com.immortal.launcher.MemoryHealthPolicy.Reason
import com.immortal.launcher.MemoryHealthPolicy.Status
import com.immortal.launcher.MemoryHealthPolicy.Values
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The restart policy, the on-device text formats it parses and the hourly log limit. Pure JVM. */
class MemoryHealthPolicyTest {
  private val mb = 1024L
  private val minute = 60_000L

  /** Family after its reboot (2026-11-19): system_server 121 MB, 700 MB available, 800 of 1536 MB swap free. */
  private val healthy = Values(
      systemServerPssKb = 121 * mb, systemServerSampleAgeMs = 10 * minute,
      memAvailableKb = 700 * mb, swapTotalKb = 1536 * mb, swapFreeKb = 800 * mb, ownPssKb = 190 * mb)

  @Test fun aHealthyPortalIsGood() {
    val result = MemoryHealthPolicy.evaluate(healthy)
    assertEquals(Status.GOOD, result.status)
    assertTrue(result.reasons.isEmpty())
  }

  @Test fun theLeakedSystemServerBeforeTheRebootNeedsARestartForEveryCause() {
    val leaked = healthy.copy(systemServerPssKb = 1905 * mb, memAvailableKb = 210 * mb, swapFreeKb = 40 * mb)
    val result = MemoryHealthPolicy.evaluate(leaked)
    assertEquals(Status.RESTART_RECOMMENDED, result.status)
    assertEquals(listOf(Reason.SYSTEM_SERVER_HIGH, Reason.SWAP_FREE_LOW, Reason.MEM_AVAILABLE_LOW), result.reasons)
  }

  @Test fun eachThresholdAloneFlagsARestartAndTheBoundaryItselfDoesNot() {
    assertEquals(listOf(Reason.SYSTEM_SERVER_HIGH),
        MemoryHealthPolicy.evaluate(healthy.copy(systemServerPssKb = 500 * mb + 1)).reasons)
    assertEquals(Status.GOOD, MemoryHealthPolicy.evaluate(healthy.copy(systemServerPssKb = 500 * mb)).status)

    assertEquals(listOf(Reason.MEM_AVAILABLE_LOW),
        MemoryHealthPolicy.evaluate(healthy.copy(memAvailableKb = 300 * mb - 1)).reasons)
    assertEquals(Status.GOOD, MemoryHealthPolicy.evaluate(healthy.copy(memAvailableKb = 300 * mb)).status)

    // 15 % of 1000 = 150: 149 is below, 150 is not.
    val swap = healthy.copy(swapTotalKb = 1000, swapFreeKb = 149)
    assertEquals(listOf(Reason.SWAP_FREE_LOW), MemoryHealthPolicy.evaluate(swap).reasons)
    assertEquals(Status.GOOD, MemoryHealthPolicy.evaluate(swap.copy(swapFreeKb = 150)).status)
  }

  @Test fun aStaleOrMissingSystemServerSampleIsNotJudged() {
    val stale = healthy.copy(systemServerPssKb = 1905 * mb, systemServerSampleAgeMs = 121 * minute)
    assertEquals(Status.GOOD, MemoryHealthPolicy.evaluate(stale).status)
    assertNull(stale.freshSystemServerPssKb)
    val atLimit = stale.copy(systemServerSampleAgeMs = MemoryHealthPolicy.SYSTEM_SERVER_MAX_AGE_MS)
    assertEquals(Status.RESTART_RECOMMENDED, MemoryHealthPolicy.evaluate(atLimit).status)
    val noAge = healthy.copy(systemServerPssKb = 1905 * mb, systemServerSampleAgeMs = null)
    assertEquals(Status.GOOD, MemoryHealthPolicy.evaluate(noAge).status)
  }

  @Test fun aPortalWithoutSwapIsJudgedOnTheOtherReadings() {
    val noSwap = healthy.copy(swapTotalKb = 0, swapFreeKb = 0)
    assertNull(noSwap.swapFreePercent)
    assertEquals(Status.GOOD, MemoryHealthPolicy.evaluate(noSwap).status)
  }

  @Test fun withNoReadingsAtAllTheStatusIsUnknownNotGood() {
    val result = MemoryHealthPolicy.evaluate(Values(ownPssKb = 190 * mb))
    assertEquals(Status.UNKNOWN, result.status)
    assertTrue(result.reasons.isEmpty())
  }

  @Test fun onlyMeminfoStillGivesAVerdict() {
    val meminfoOnly = Values(memAvailableKb = 700 * mb, swapTotalKb = 1536 * mb, swapFreeKb = 800 * mb)
    assertEquals(Status.GOOD, MemoryHealthPolicy.evaluate(meminfoOnly).status)
  }

  @Test fun reasonsReadInPlainWords() {
    val leaked = healthy.copy(systemServerPssKb = 1905 * mb, memAvailableKb = 210 * mb, swapFreeKb = 40 * mb)
    assertEquals("Android’s system process is using 1.9 GB.", MemoryHealthPolicy.describe(Reason.SYSTEM_SERVER_HIGH, leaked))
    assertEquals("Only 2% of swap is free.", MemoryHealthPolicy.describe(Reason.SWAP_FREE_LOW, leaked))
    assertEquals("Only 210 MB of memory is available.", MemoryHealthPolicy.describe(Reason.MEM_AVAILABLE_LOW, leaked))
    assertEquals("unknown", MemoryHealthPolicy.formatMb(null))
  }

  @Test fun meminfoParsesKilobyteValuesAndSkipsJunk() {
    val parsed = MemoryHealthPolicy.parseMeminfo("""
        MemTotal:        3884300 kB
        MemAvailable:     703532 kB
        SwapTotal:       1572860 kB
        SwapFree:         812345 kB
        HugePages_Total:       0
        garbage line
        Broken:          notanumber kB
    """.trimIndent())
    assertEquals(703532L, parsed["MemAvailable"])
    assertEquals(1572860L, parsed["SwapTotal"])
    assertEquals(812345L, parsed["SwapFree"])
    assertEquals(0L, parsed["HugePages_Total"])
    assertFalse("Broken" in parsed)
  }

  @Test fun ownPssComesFromTheSmapsRollupPssLineOnly() {
    // Debug.getPss() also asks the memtrack HAL, which SELinux denies an app on a Portal, so the
    // framework logged "failed to get memory consumption info: -1" on every sample.
    val rollup = """
        12c00000-7fdda63000 ---p 00000000 00:00 0                                [rollup]
        Rss:              334356 kB
        Pss:              264357 kB
        Pss_Anon:         170000 kB
        Shared_Clean:      67508 kB
    """.trimIndent()
    assertEquals(264357L, MemoryHealthPolicy.parseOwnPssKb(rollup))
    assertEquals(null, MemoryHealthPolicy.parseOwnPssKb("Rss: 12 kB\nPss: 0 kB"))
    assertEquals(null, MemoryHealthPolicy.parseOwnPssKb(""))
  }

  @Test fun theNewestSystemServerAmPssLineWinsAndOtherProcessesAreIgnored() {
    val events = """
        --------- beginning of events
        1795108641.120  1264  1293 I am_pss  : [1264,1000,system,130072576,112713728,7819264,195129344,2,0,15]
        1795108700.000  1264  1293 I am_pss  : [24621,10128,com.immortal.launcher.debug,208710656,192413696,521216,292352000,0,2,30]
        1795108800.000  1264  1293 I am_pss  : [2285,1000,com.facebook.alohasdk.platformsecurestate,7997440,1867776,4281344,40779776,2,0,5]
        1795110165.549  1264  1293 I am_pss  : [1264,1000,system,127205376,109699072,7924736,193073152,1,0,16]
        1795110200.5  1264  1293 I am_pss  : [1264,1000,system,notanumber,0,0,0,1,0,16]
    """.trimIndent()
    val sample = MemoryHealthPolicy.parseSystemServerPss(events)!!
    assertEquals(127205376L / 1024, sample.pssKb)
    assertEquals(1795110165549L, sample.atEpochMs)
  }

  @Test fun noSystemServerLineMeansNoReading() {
    assertNull(MemoryHealthPolicy.parseSystemServerPss(""))
    assertNull(MemoryHealthPolicy.parseSystemServerPss("logcat: permission denied\n"))
    assertNull(MemoryHealthPolicy.parseSystemServerPss(
        "1795108700.000  1264  1293 I am_pss  : [24621,10128,com.immortal.launcher.debug,208710656,1,2,3,0,2,30]"))
  }

  @Test fun theLogThrottleAllowsOneLinePerHour() {
    val throttle = MemoryHealthPolicy.LogThrottle(MemoryHealth.LOG_INTERVAL_MS)
    assertTrue(throttle.shouldLog(1_000))
    // Four 15-minute samples within the hour stay quiet.
    for (sample in 1..3) assertFalse(throttle.shouldLog(1_000 + sample * MemoryHealth.SAMPLE_INTERVAL_MS))
    assertTrue(throttle.shouldLog(1_000 + MemoryHealth.LOG_INTERVAL_MS))
    assertFalse(throttle.shouldLog(1_000 + MemoryHealth.LOG_INTERVAL_MS + 1))
  }

  @Test fun theFleetJsonCarriesStatusReasonsAndValuesInMegabytes() {
    val leaked = healthy.copy(systemServerPssKb = 1905 * mb, swapFreeKb = 40 * mb)
    val sample = MemoryHealth.Sample(leaked, MemoryHealthPolicy.evaluate(leaked), 1_000_000L,
        MemoryHealth.SOURCE_EVENTS_LOG)
    val json = MemoryHealth.toJson(sample, nowEpochMs = 1_090_000L)
    assertEquals("restart_recommended", json.getString("status"))
    assertEquals("system_server_pss_high", json.getJSONArray("reasons").getString(0))
    assertEquals("swap_free_low", json.getJSONArray("reasons").getString(1))
    val values = json.getJSONObject("values")
    assertEquals(1905, values.getInt("systemServerPssMb"))
    assertEquals(10, values.getInt("systemServerSampleAgeMin"))
    assertEquals("am_pss_event_log", values.getString("systemServerSource"))
    assertEquals(700, values.getInt("memAvailableMb"))
    assertEquals(2, values.getInt("swapFreePercent"))
    assertEquals(190, values.getInt("ownPssMb"))
    assertEquals(500, json.getJSONObject("thresholds").getInt("systemServerPssMaxMb"))
    assertEquals(90, json.getInt("sampleAgeSec"))
  }

  @Test fun beforeTheFirstSampleTheFleetJsonSaysUnknown() {
    val json = MemoryHealth.toJson(null)
    assertEquals("unknown", json.getString("status"))
    assertEquals(0, json.getJSONArray("reasons").length())
    assertFalse(json.getBoolean("sampled"))
  }

  @Test fun unreadableSystemServerValuesSerializeAsNull() {
    val values = Values(memAvailableKb = 700 * mb)
    val sample = MemoryHealth.Sample(values, MemoryHealthPolicy.evaluate(values), 0L, MemoryHealth.SOURCE_UNAVAILABLE)
    val json = MemoryHealth.toJson(sample, nowEpochMs = 0L).getJSONObject("values")
    assertTrue(json.isNull("systemServerPssMb"))
    assertTrue(json.isNull("swapFreePercent"))
    assertEquals("unavailable", json.getString("systemServerSource"))
  }
}
