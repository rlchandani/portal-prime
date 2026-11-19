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
 * The Calling mock's line icons (24-unit viewBox, 2-unit round strokes), copied path for path from
 * the mock's `IC` table (rects and circles converted to paths), built once and cached.
 */
internal object CallIcons {
  private data class P(val d: String, val fill: Boolean = false)

  private val paths: Map<String, List<P>> = mapOf(
    "phone" to listOf(P("M5 4h3.5l2 5-2.5 1.6a11 11 0 0 0 5.4 5.4L15 13.5l5 2V19a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z")),
    "video" to listOf(P("M5.5 6h7a3 3 0 0 1 3 3v6a3 3 0 0 1 -3 3h-7a3 3 0 0 1 -3 -3v-6a3 3 0 0 1 3 -3z"), P("M15.5 10.5 21 7.5v9l-5.5-3")),
    "videoOff" to listOf(P("M15.5 10.5 21 7.5v9l-5.5-3M10 6h2.5a3 3 0 0 1 3 3v4M15.5 17.5a3 3 0 0 1-3 .5h-7a3 3 0 0 1-3-3V9a3 3 0 0 1 1.6-2.6M3 3l18 18")),
    "mic" to listOf(P("M12 3h0a3 3 0 0 1 3 3v5a3 3 0 0 1 -3 3h-0a3 3 0 0 1 -3 -3v-5a3 3 0 0 1 3 -3z"), P("M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21")),
    "micOff" to listOf(P("M9 9V6a3 3 0 0 1 5.8-1M15 10.5V11a3 3 0 0 1-4.7 2.5M5.5 11a6.5 6.5 0 0 0 10.9 4.8M18.5 11a6.4 6.4 0 0 1-.5 2.4M12 17.5V21M3 3l18 18")),
    "end" to listOf(P("M2.3 14.6c5.5-5.2 13.9-5.2 19.4 0 .4.4.4 1 0 1.4l-2.2 2.2c-.4.4-1 .4-1.4.1l-2.6-1.9c-.3-.2-.4-.5-.4-.8v-2.5a10.7 10.7 0 0 0-6.2 0v2.5c0 .3-.1.6-.4.8l-2.6 1.9c-.4.3-1 .3-1.4-.1L2.3 16c-.4-.4-.4-1 0-1.4z", fill = true)),
    "follow" to listOf(P("M3 8V5.5A2.5 2.5 0 0 1 5.5 3H8M16 3h2.5A2.5 2.5 0 0 1 21 5.5V8M21 16v2.5a2.5 2.5 0 0 1-2.5 2.5H16M8 21H5.5A2.5 2.5 0 0 1 3 18.5V16"), P("M9.2 10a2.8 2.8 0 1 0 5.6 0a2.8 2.8 0 1 0 -5.6 0z"), P("M7.5 17.5a4.5 4.5 0 0 1 9 0")),
    "vol" to listOf(P("M4 9.5h3.5L12 5.5v13l-4.5-4H4z"), P("M15.5 9a4.5 4.5 0 0 1 0 6M18.2 6.3a8.4 8.4 0 0 1 0 11.4")),
    "volLow" to listOf(P("M5 9.5h3.5L13 5.5v13l-4.5-4H5z"), P("M16.5 9.5a3.5 3.5 0 0 1 0 5")),
    "back" to listOf(P("M15 5l-7 7 7 7")),
    "moon" to listOf(P("M20 14.5A8.2 8.2 0 1 1 9.5 4a6.6 6.6 0 0 0 10.5 10.5z")),
    "lock" to listOf(P("M7.5 10.5h9a2.5 2.5 0 0 1 2.5 2.5v5.5a2.5 2.5 0 0 1 -2.5 2.5h-9a2.5 2.5 0 0 1 -2.5 -2.5v-5.5a2.5 2.5 0 0 1 2.5 -2.5z"), P("M8.2 10.5V7.8a3.8 3.8 0 0 1 7.6 0v2.7")),
    "shield" to listOf(P("M12 3 20 6v5.5c0 5-3.4 8.3-8 9.5-4.6-1.2-8-4.5-8-9.5V6z"), P("m8.8 12 2.2 2.2 4.2-4.4")),
    "house" to listOf(P("M3 11 12 4l9 7"), P("M5.5 9.5V20h13V9.5"), P("M10 20v-5h4v5")),
    "clock" to listOf(P("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z"), P("M12 7v5l3.2 2")),
    "check" to listOf(P("m5 12.5 4.5 4.5L19 7.5")),
    "wifi" to listOf(P("M2.5 8.8a14 14 0 0 1 19 0M5.5 12.2a9.6 9.6 0 0 1 13 0M8.6 15.5a5.2 5.2 0 0 1 6.8 0"), P("M11 19a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z", fill = true)),
    "wifiOff" to listOf(P("M2.5 8.8A14 14 0 0 1 7.6 5.6M12 4.5a14 14 0 0 1 9.5 4.3M5.5 12.2a9.6 9.6 0 0 1 4.3-2.4M15.8 10.4a9.6 9.6 0 0 1 2.7 1.8M8.6 15.5a5.2 5.2 0 0 1 6.8 0M3 3l18 18")),
    "gear" to listOf(P("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z"), P("M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7")),
    "bell" to listOf(P("M6 16.5V11a6 6 0 0 1 12 0v5.5l1.8 2H4.2z"), P("M10 21h4")),
    "bellOff" to listOf(P("M8.5 5.6A6 6 0 0 1 18 11v4M6 11v5.5l-1.8 2H17M10 21h4M3 3l18 18")),
    "door" to listOf(P("M5.5 21V4.2c0-.7.5-1.2 1.2-1.2h8.6l3.2 2.2V21"), P("M3 21h18"), P("M11.6 12.5a0.9 0.9 0 1 0 1.8 0a0.9 0.9 0 1 0 -1.8 0z", fill = true)),
    "star" to listOf(P("m12 3.5 2.6 5.3 5.8.8-4.2 4.1 1 5.8-5.2-2.7-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z")),
    "info" to listOf(P("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z"), P("M12 11v5.5M12 7.6v.1")),
    "warn" to listOf(P("M12 3.5 21.5 20h-19z"), P("M12 10v4.5M12 17.4v.1")),
    "camera" to listOf(P("M4 8h3l2-2.5h6L17 8h3v11H4z"), P("M8.6 13.2a3.4 3.4 0 1 0 6.8 0a3.4 3.4 0 1 0 -6.8 0z")),
    "shutter" to listOf(P("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z"), P("m12 3 4 7.5M21 12l-8 2M12 21 8 13.5M3 12l8-2")),
    "inc" to listOf(P("M17 7 7 17M7 9v8h8")),
    "redo" to listOf(P("M4 12a8 8 0 1 0 2.3-5.7L4 8.6M4 4v4.6h4.6")),
    "trash" to listOf(P("M4 7h16M9.5 7V4.5h5V7M6.5 7l1 13.5h9l1-13.5")),
    "x" to listOf(P("M6 6l12 12M18 6 6 18")),
    "msg" to listOf(P("M5.7 5.5h7.6a3.2 3.2 0 0 1 3.2 3.2v6.6a3.2 3.2 0 0 1 -3.2 3.2h-7.6a3.2 3.2 0 0 1 -3.2 -3.2v-6.6a3.2 3.2 0 0 1 3.2 -3.2z"),
        P("m16.5 10 5-3v10l-5-3"), P("M7.3 12a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z", fill = true)),
    "plus" to listOf(P("M12 5v14M5 12h14")),
    "addp" to listOf(P("M5.2 8a3.8 3.8 0 1 0 7.6 0a3.8 3.8 0 1 0 -7.6 0z"), P("M2.5 20.5a6.5 6.5 0 0 1 13 0M19 7.5v6M16 10.5h6")),
    "users" to listOf(P("M5.6 8a3.4 3.4 0 1 0 6.8 0a3.4 3.4 0 1 0 -6.8 0z"), P("M2.6 20a6.4 6.4 0 0 1 12.8 0"), P("M14.5 9a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0z"), P("M17 14.3a4.9 4.9 0 0 1 4.6 5")),
    "key" to listOf(P("M3.5 15a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0z"), P("m11.2 11.8 8.8-8.8M16.5 6.5 19 9M14.5 8.5 16.5 10.5")),
    "eye" to listOf(P("M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"), P("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z")),
    "arrowL" to listOf(P("M19 12H5M11 6l-6 6 6 6")),
    "msg" to listOf(P("M5.7 5.5h7.6a3.2 3.2 0 0 1 3.2 3.2v6.6a3.2 3.2 0 0 1 -3.2 3.2h-7.6a3.2 3.2 0 0 1 -3.2 -3.2v-6.6a3.2 3.2 0 0 1 3.2 -3.2z"),
        P("m16.5 10 5-3v10l-5-3"), P("M7.3 12a2.2 2.2 0 1 0 4.4 0a2.2 2.2 0 1 0 -4.4 0z", fill = true)),
    "play" to listOf(P("M7.5 4.6v14.8c0 .8.9 1.3 1.5.8l11.3-7.4c.6-.4.6-1.2 0-1.6L9 3.8c-.6-.5-1.5 0-1.5.8z", fill = true)),
    "pause" to listOf(P("M7.4 4.5h1.2a1.4 1.4 0 0 1 1.4 1.4v12.2a1.4 1.4 0 0 1 -1.4 1.4h-1.2a1.4 1.4 0 0 1 -1.4 -1.4v-12.2a1.4 1.4 0 0 1 1.4 -1.4z", fill = true),
        P("M15.4 4.5h1.2a1.4 1.4 0 0 1 1.4 1.4v12.2a1.4 1.4 0 0 1 -1.4 1.4h-1.2a1.4 1.4 0 0 1 -1.4 -1.4v-12.2a1.4 1.4 0 0 1 1.4 -1.4z", fill = true)),
    "send" to listOf(P("M3.5 11.5 20.5 4l-6.5 17-3-7.5z"), P("M11 13.5 20.5 4")),
    "x" to listOf(P("M6 6l12 12M18 6 6 18")),
  )

  private val cache = HashMap<String, ImageVector>()

  operator fun get(name: String): ImageVector = synchronized(cache) {
    cache.getOrPut(name) { build(name, paths[name] ?: paths.getValue("info")) }
  }

  private fun build(name: String, parts: List<P>): ImageVector =
      ImageVector.Builder(name = "call_$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f,
          viewportHeight = 24f).apply {
        parts.forEach { p ->
          if (p.fill) addPath(addPathNodes(p.d), fill = SolidColor(Color.Black))
          else addPath(addPathNodes(p.d), stroke = SolidColor(Color.Black), strokeLineWidth = STROKE,
              strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
      }.build()

  private const val STROKE = 2f
}

@Composable
internal fun CallIcon(name: String, tint: Color, size: Dp = 34.dp, modifier: Modifier = Modifier) {
  Icon(CallIcons[name], contentDescription = null, tint = tint, modifier = modifier.size(size))
}
