/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** The requested entry is distinct from redirects and subsequent in-site navigation. */
internal class WebAppEntryState(initialUrl: String) {
  private var entry = initialUrl
  fun request(url: String): Boolean {
    if (entry == url) return false
    entry = url
    return true
  }
}
