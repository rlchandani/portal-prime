/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Optional percent changes must remain usable by the Home widget's numeric label. */
class CryptoChangeValidityTest {
  private val coins = listOf(CryptoConfig.Coin("bitcoin", "XBT"))

  private fun quote(change: String): CryptoFeed.Quote =
      CryptoFeed.parse(
          """[{"id":"bitcoin","symbol":"btc","current_price":123.0,
            "price_change_percentage_24h":$change,
            "sparkline_in_7d":{"price":[120.0,123.0]}}]""",
          coins,
      ).single().also {
        assertEquals("XBT", it.symbol)
        assertEquals(123.0, it.usd, 0.0)
        assertEquals(listOf(120.0, 123.0), it.spark)
      }

  @Test fun positiveOverflowChangeIsUnavailable() {
    assertNull(quote("\"1e400\"").change24h)
  }

  @Test fun negativeOverflowChangeIsUnavailable() {
    assertNull(quote("\"-1e400\"").change24h)
  }

  @Test fun finiteChangesKeepTheirValueIncludingZero() {
    listOf("-1.5", "0.0", "2.75").forEach { value ->
      assertEquals(value.toDouble(), quote(value).change24h!!, 0.0)
    }
  }

  @Test fun unavailableChangesRemainUnavailable() {
    listOf("null", "\"not a number\"", "\"NaN\"").forEach { value ->
      assertNull(quote(value).change24h)
    }
  }
}
