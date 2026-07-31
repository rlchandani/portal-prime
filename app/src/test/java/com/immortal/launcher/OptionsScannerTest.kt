/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure delta-band filtering + OCC-symbol decoding for the options scanner (no network). */
class OptionsScannerTest {

  private fun contract(symbol: String, delta: Double?, iv: Double? = 0.3, bid: Double? = 1.0, ask: Double? = 1.2) =
      AlpacaClient.OptionContract(symbol, delta, iv, bid, ask, last = 1.1)

  @Test
  fun `filterChain keeps only deltas within band and decodes the symbol`() {
    val chain =
        listOf(
            contract("AAPL241220C00300000", 0.25),
            contract("AAPL241220C00320000", 0.12), // below band
            contract("AAPL241220C00280000", 0.45), // above band
            contract("AAPL241220C00310000", 0.28),
        )
    val hits = OptionsScanner.filterChain("AAPL", chain, isCall = true, deltaMin = 0.20, deltaMax = 0.30)
    assertEquals(2, hits.size)
    val h = hits.first { it.strike == 300.0 }
    assertEquals("AAPL", h.underlying)
    assertEquals("2024-12-20", h.expiry)
    assertTrue(h.call)
    assertEquals(0.25, h.delta, 0.001)
    // mid = (1.0 + 1.2)/2
    assertEquals(1.1, h.mid!!, 0.001)
  }

  @Test
  fun `put deltas are matched by magnitude`() {
    val chain = listOf(contract("SPY241220P00400000", -0.25))
    val hits = OptionsScanner.filterChain("SPY", chain, isCall = false, deltaMin = 0.20, deltaMax = 0.30)
    assertEquals(1, hits.size)
    assertEquals(400.0, hits[0].strike, 0.001)
    assertEquals(-0.25, hits[0].delta, 0.001)
  }

  @Test
  fun `contracts without a delta are dropped`() {
    val chain = listOf(contract("AAPL241220C00300000", null))
    assertTrue(OptionsScanner.filterChain("AAPL", chain, true, 0.20, 0.30).isEmpty())
  }

  @Test
  fun `fractional strike is decoded`() {
    val chain = listOf(contract("AAPL241220C00302500", 0.25))
    val hits = OptionsScanner.filterChain("AAPL", chain, true, 0.20, 0.30)
    assertEquals(302.5, hits[0].strike, 0.001)
  }

  @Test
  fun `expiryWindow spans now to now plus days in UTC iso`() {
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val now = fmt.parse("2026-01-10")!!.time
    val (gte, lte) = OptionsScanner.expiryWindow(now, 45)
    assertEquals("2026-01-10", gte)
    assertEquals("2026-02-24", lte)
  }

  @Test
  fun `malformed occ symbols are skipped`() {
    val chain = listOf(contract("SHORT", 0.25), contract("AAPL241220X00300000", 0.25))
    // "SHORT" too short; the X (not C/P) contract is rejected.
    assertTrue(OptionsScanner.filterChain("AAPL", chain, true, 0.20, 0.30).isEmpty())
  }
}
