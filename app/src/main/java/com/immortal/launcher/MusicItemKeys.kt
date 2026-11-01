/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Bundle-saveable row keys, independent of playback IDs. Repeated tracks remain separate
 * occurrences; unrelated insertions do not renumber identifiable items.
 */
internal fun <T> musicItemKeys(items: List<T>, itemId: (T) -> String): List<String> {
  if (items.isEmpty()) return emptyList()
  val occurrences = HashMap<String, Int>()
  return items.mapIndexed { index, item ->
    val id = itemId(item)
    if (id.isBlank()) "missing:$index" else {
      val occurrence = occurrences[id] ?: 0
      occurrences[id] = occurrence + 1
      "item:${id.length}:$id:$occurrence"
    }
  }
}
