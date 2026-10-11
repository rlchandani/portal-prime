/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Resolve only indices into the current frozen album; the bridge never accepts new URLs. */
internal object NativeFilmstripSelection {
  fun resolve(ids: List<Int>, sources: List<String>): List<String>? {
    if (ids.size !in 1..12 || ids.distinct().size != ids.size || ids.any { it !in sources.indices }) return null
    return ids.map(sources::get)
  }
  fun validToken(token: String): Boolean = token.length in 1..64 && token.all {
    it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
  }
}
