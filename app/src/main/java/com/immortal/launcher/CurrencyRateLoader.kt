/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import kotlinx.coroutines.delay

/** Cache hits bypass the picker debounce; cancellation still owns both worker requests. */
internal suspend fun <T> loadCurrencyRate(
    cached: suspend () -> T?,
    fetch: suspend () -> T?,
    debounce: suspend () -> Unit = { delay(250L) },
): T? {
  cached()?.let { return it }
  debounce()
  return fetch()
}
