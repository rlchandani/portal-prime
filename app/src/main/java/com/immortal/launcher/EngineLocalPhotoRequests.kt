/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import java.net.URI

/** Opaque image requests can name only this engine owner's frozen local photo list. */
internal class EngineLocalPhotoRequests(private val urls: List<String>, private val owner: Long) {
  private val localIndices = urls.withIndex().filter { it.value.startsWith("file://") }
      .associate { it.value to it.index }

  fun urlFor(source: String, edge: Int): String? = localIndices[source]?.let { index ->
    "$ORIGIN/$owner/$index/${EngineScaledCache.derivativeEdge(edge)}"
  }

  fun owns(url: String): Boolean = runCatching { URI(url).host == HOST }.getOrDefault(false)

  fun resolve(url: String): Pair<String, Int>? = runCatching {
    val uri = URI(url)
    if (uri.scheme != "https" || uri.host != HOST || uri.port != -1 ||
        uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return@runCatching null
    val parts = uri.rawPath.split('/')
    if (parts.size != 4 || parts[0].isNotEmpty() || parts[1].toLongOrNull() != owner) return@runCatching null
    val index = parts[2].toIntOrNull() ?: return@runCatching null
    val edge = parts[3].toIntOrNull() ?: return@runCatching null
    if (edge != 600 && edge != 1200 && edge != 1800 && edge != 2400) return@runCatching null
    val source = urls.getOrNull(index)?.takeIf { it.startsWith("file://") } ?: return@runCatching null
    source.removePrefix("file://") to edge
  }.getOrNull()

  private companion object {
    const val HOST = "immortal-photo.invalid"
    const val ORIGIN = "https://$HOST"
  }
}
