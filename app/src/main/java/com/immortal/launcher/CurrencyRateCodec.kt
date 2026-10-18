/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import java.time.LocalDate
import org.json.JSONObject

/** One response decoder for network and offline cached currency rates. */
internal object CurrencyRateCodec {
  private val REFERENCE_DATE_SHAPE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")

  fun decode(json: String, expectedBase: String, saved: Boolean): Map<String, CurrencyRate>? =
      runCatching {
            val response = JSONObject(json)
            // Legacy caches can omit metadata; supplied metadata must describe this request.
            if (response.has("base")) {
              require((response.opt("base") as? String)?.equals(expectedBase, ignoreCase = true) == true)
            }
            if (response.has("amount")) require(response.getDouble("amount") == 1.0)
            val r = response.getJSONObject("rates")
            val referenceDate = parseReferenceDate(response.opt("date") as? String)
            buildMap {
              r.keys().forEach { k ->
                val value = r.getDouble(k)
                require(value.isFinite() && value > 0.0)
                put(k, CurrencyRate(value, referenceDate, saved))
              }
            }
          }
          .getOrNull()
          ?.takeIf { it.isNotEmpty() }

  /** A bad date only drops metadata; otherwise usable rates keep their original semantics. */
  private fun parseReferenceDate(date: String?): String? {
    if (date == null || !REFERENCE_DATE_SHAPE.matches(date)) return null
    return runCatching { LocalDate.parse(date); date }.getOrNull()
  }

}
