/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The dashboard mock's extra line glyphs (rooms, scenes, lamp, plug, flame…) on the same 24-unit
 * grid as [CcIcons]; any other name comes from [CcIcons] so the two screens share one icon set.
 */
internal object HaDashboardIcons {

  private val paths: Map<String, String> = mapOf(
    "back" to "M15 5l-7 7 7 7",
    "gear" to "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.6 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.6-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z",
    "lamp" to "M8 3h8l3 8H5l3-8zM12 11v9M8 21h8",
    "plug" to "M9 2v5M15 2v5M6 7h12v4a6 6 0 0 1-12 0V7zM12 17v5",
    "flame" to "M12 3c1 3 5 5 5 10a5 5 0 0 1-10 0c0-2.5 1.5-4 2.5-5 .3 1.5 1 2.5 2 3 0-3 .5-5.5.5-8z",
    "sunrise" to "M3 18h18M6 18a6 6 0 0 1 12 0M12 3v5M9 6l3-3 3 3M4 12l1.4 1M20 12l-1.4 1M5 21h14",
    "sunset" to "M3 18h18M6 18a6 6 0 0 1 12 0M12 3v5M9 5l3 3 3-3M4 12l1.4 1M20 12l-1.4 1M5 21h14",
    "leave" to "M14 3h5a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1h-5M10 17l5-5-5-5M15 12H3",
    "sofa" to "M4 11V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v3M3 11a2 2 0 0 1 2 2v2h14v-2a2 2 0 1 1 2 2v3H3v-3a2 2 0 0 1 0-4zM6 18v2M18 18v2",
    "pot" to "M4 10h16v6a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4v-6zM2 10h2M20 10h2M9 6c0-1 1-1 1-2M14 6c0-1 1-1 1-2",
    "bed" to "M3 18V6M21 18v-5a3 3 0 0 0-3-3h-7v5M3 13h18M3 16h18M7 8.7a1.8 1.8 0 1 0 0 3.6a1.8 1.8 0 1 0 0-3.6z",
    "desk" to "M3 8h18M5 8v12M19 8v12M13 8v6h6M9 4h6v4H9z",
    "hall" to "M4 21V6l8-3 8 3v15M9 21v-7h6v7",
    "toy" to "M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16zM8 13s1.5 2 4 2 4-2 4-2M9 9.5h.01M15 9.5h.01",
    "tree" to "M12 21v-5M7 16h10l-3-4h2l-4-5h1.5L12 3 8.5 7H10l-4 5h2l-1 4z",
    "guest" to "M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM4 21c1-4 4.5-6 8-6s7 2 8 6",
    "alert" to "M12 3l10 18H2L12 3zM12 10v4.5M12 17.5h.01",
    "blinds" to "M4 3h16M5 3v11h14V3M5 7h14M5 11h14M12 14v3M12 17a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z",
  )

  // Transport glyphs are filled AND stroked in the mock, so the bar beside each triangle shows.
  private val solid: Map<String, String> = mapOf(
    "play" to "M8 5v14l11-7z",
    "prev" to "M18 6v12l-9-6zM6 6v12",
    "next" to "M6 6v12l9-6zM18 6v12",
    "pause" to "M8 5v14M16 5v14",
  )

  private val cache = HashMap<String, ImageVector>()

  operator fun get(name: String): ImageVector = synchronized(cache) {
    cache.getOrPut(name) {
      paths[name]?.let { build(name, it, filled = false) } ?: solid[name]?.let { build(name, it, filled = true) } ?: CcIcons[name]
    }
  }

  private fun build(name: String, d: String, filled: Boolean): ImageVector =
      ImageVector.Builder(name = "ha_$name", defaultWidth = 24.dp, defaultHeight = 24.dp,
          viewportWidth = 24f, viewportHeight = 24f)
          .addPath(addPathNodes(d), fill = if (filled) SolidColor(Color.Black) else null, stroke = SolidColor(Color.Black),
              strokeLineWidth = if (name == "pause") PAUSE_STROKE else STROKE,
              strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
          .build()

  private const val STROKE = 1.9f
  private const val PAUSE_STROKE = 3.2f
}

/** A dashboard glyph at [size], tinted [tint]; decorative (the enclosing control names it). */
@Composable
internal fun HaIcon(name: String, tint: Color, size: Dp, modifier: Modifier = Modifier) {
  Icon(HaDashboardIcons[name], contentDescription = null, tint = tint, modifier = modifier.size(size))
}
