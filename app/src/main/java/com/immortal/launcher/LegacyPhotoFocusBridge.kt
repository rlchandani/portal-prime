/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** Read only the already-hydrated cache, for a frozen photo belonging to this engine. */
internal class LegacyPhotoFocusBridge(
    private val urls: List<String>,
    private val isCurrent: () -> Boolean,
    private val cachedMetadata: (String) -> String,
) {
  fun read(index: Int): String {
    if (!isCurrent()) return ""
    val url = urls.getOrNull(index) ?: return ""
    val entry = cachedMetadata(url)
    return if (isCurrent() && entry.length <= 256) entry else ""
  }
}
