/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Endpoint-inclusive steps also work when the range is not an exact multiple of the step. */
internal fun stepSettingNumber(current: Int, delta: Int, min: Int, max: Int, wrap: Boolean): Int {
  require(min <= max)
  val next = current.toLong() + delta
  return if (wrap) {
    val range = max.toLong() - min + 1
    (Math.floorMod(next - min, range) + min).toInt()
  } else next.coerceIn(min.toLong(), max.toLong()).toInt()
}
