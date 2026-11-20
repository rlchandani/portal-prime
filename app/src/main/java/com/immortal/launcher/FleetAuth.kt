/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** The fleet API's bearer gate. Pure. */
internal object FleetAuth {
  private const val SCHEME = "Bearer "

  /**
   * True when [authorization] (the raw header value) carries [token]. The `Bearer ` prefix is
   * optional and surrounding spaces are ignored, as fleetctl and the phone remote have always
   * relied on; the compare is constant-time.
   */
  fun authorized(authorization: String?, token: String): Boolean {
    val got = authorization?.removePrefix(SCHEME)?.trim() ?: return false
    return constantTimeEquals(got, token)
  }

  /** Length-checked constant-time token compare. */
  fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var r = 0
    for (i in a.indices) r = r or (a[i].code xor b[i].code)
    return r == 0
  }
}
