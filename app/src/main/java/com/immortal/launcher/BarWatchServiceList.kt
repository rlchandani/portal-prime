/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Preserve an already-canonical enabled entry; intentional repairs keep their original rules. */
internal object BarWatchServiceList {
  fun updated(current: String, component: String, on: Boolean): String {
    val entries = current.split(':').filter { it.isNotBlank() }
    val matches = entries.filter { it.equals(component, ignoreCase = true) }
    if (on && matches.size == 1 && matches.single() == component) return current
    val others = entries.filterNot { it.equals(component, ignoreCase = true) }
    return (if (on) others + component else others).joinToString(":")
  }
}
