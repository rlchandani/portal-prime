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
 * The mock's line icon set (24-unit viewBox, 2-unit round strokes), as Compose vectors built once
 * and cached. Names match the mock (`IP` table) so screens read like the spec: `CcIcons["garage"]`.
 */
internal object CcIcons {
  private data class P(val d: String, val fill: Boolean = false)

  private val paths: Map<String, List<P>> = mapOf(
    "map" to listOf(P("M9 4 3 6v14l6-2 6 2 6-2V4l-6 2-6-2zM9 4v14M15 6v14")),
    "cam" to listOf(P("M5 7h9a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-9a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z"), P("m16 11 5-3v8l-5-3")),
    "bolt" to listOf(P("M13 2 4 14h7l-1 8 9-12h-7l1-8z")),
    "bell" to listOf(P("M6 16v-5a6 6 0 0 1 12 0v5l2 2H4l2-2zM10 21h4")),
    "shield" to listOf(P("M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6l-8-3z")),
    "shieldck" to listOf(P("M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6l-8-3z"), P("m9 12 2 2 4-4")),
    "edit" to listOf(P("M4 20h4L19 9l-4-4L4 16v4zM14 6l4 4")),
    "gear" to listOf(P("M8.8 12a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0z"), P("M12 2.5v3M12 18.5v3M2.5 12h3M18.5 12h3M5.3 5.3l2.1 2.1M16.6 16.6l2.1 2.1M5.3 18.7l2.1-2.1M16.6 7.4l2.1-2.1")),
    "mic" to listOf(P("M12 3h0a3 3 0 0 1 3 3v5a3 3 0 0 1 -3 3h-0a3 3 0 0 1 -3 -3v-5a3 3 0 0 1 3 -3z"), P("M5 11a7 7 0 0 0 14 0M12 18v3")),
    "bulb" to listOf(P("M9 18h6M10 21h4M12 3a6 6 0 0 0-4 10.5c.8.8 1 1.5 1 2.5h6c0-1 .2-1.7 1-2.5A6 6 0 0 0 12 3z")),
    "lock" to listOf(P("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z"), P("M8 11V8a4 4 0 0 1 8 0v3")),
    "unlock" to listOf(P("M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-10a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z"), P("M8 11V8a4 4 0 0 1 7.6-1.8")),
    "garage" to listOf(P("M3 21V9l9-5 9 5v12M7 21v-9h10v9M7 15h10M7 18h10")),
    "therm" to listOf(P("M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0z")),
    "drop" to listOf(P("M12 3s6 7 6 11a6 6 0 0 1-12 0c0-4 6-11 6-11z")),
    "leak" to listOf(P("M12 2.5s5 6 5 9a5 5 0 0 1-10 0c0-3 5-9 5-9zM3 20.5c2-1 4 1 6 0s4 1 6 0 4 1 6 0")),
    "fan" to listOf(P("M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z"), P("M12 10c0-4 1-7 4-7 2 0 3 2 1 4l-3 3M14 12c4 0 7 1 7 4 0 2-2 3-4 1l-3-3M12 14c0 4-1 7-4 7-2 0-3-2-1-4l3-3M10 12c-4 0-7-1-7-4 0-2 2-3 4-1l3 3")),
    "blinds" to listOf(P("M3 4h18M5 4v12h14V4M5 8h14M5 12h14M12 16v4")),
    "tv" to listOf(P("M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z"), P("M8 21h8")),
    "speaker" to listOf(P("M9 3h6a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-6a3 3 0 0 1 -3 -3v-12a3 3 0 0 1 3 -3z"), P("M9 14a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z"), P("M11 7.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")),
    "play" to listOf(P("M8 5v14l11-7z", fill = true)),
    "pause" to listOf(P("M8 5h3v14H8zM13 5h3v14h-3z", fill = true)),
    "next" to listOf(P("M6 5v14l9-7zM17 5v14", fill = true)),
    "prev" to listOf(P("M18 5v14L9 12zM7 5v14", fill = true)),
    "door" to listOf(P("M6 21V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v17M3 21h18M14 12h.5")),
    "window" to listOf(P("M5 3h14a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-14a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1z"), P("M12 3v18M4 12h16")),
    "person" to listOf(P("M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z"), P("M4 21c1-4 4-6 8-6s7 2 8 6")),
    "sun" to listOf(P("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z"), P("M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4")),
    "battery" to listOf(P("M4.5 7h13a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2h-13a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z"), P("M22 11v2M6.5 10v4M10 10v4M13.5 10v4")),
    "grid" to listOf(P("M8 22 10 3h4l2 19M6 8h12M5 13h14M10 3l4.5 10M14 3 9.5 13")),
    "car" to listOf(P("M5 16v-4l2-5h10l2 5v4M3 16h18v3H3zM7 19v2M17 19v2"), P("M6.5 13.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z"), P("M15.5 13.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")),
    "charger" to listOf(P("M7 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-6a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z"), P("M15 8h2a2 2 0 0 1 2 2v6a2 2 0 0 0 2 2M9 8l-1.5 3h3L9 14")),
    "home" to listOf(P("M3 11 12 4l9 7M5 10v10h14V10")),
    "check" to listOf(P("m5 12.5 4.5 4.5L19 7")),
    "x" to listOf(P("M6 6l12 12M18 6 6 18")),
    "chev" to listOf(P("m9 6 6 6-6 6")),
    "chevL" to listOf(P("m15 6-6 6 6 6")),
    "plus" to listOf(P("M12 5v14M5 12h14")),
    "minus" to listOf(P("M5 12h14")),
    "wifi" to listOf(P("M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0"), P("M11 19.5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")),
    "wifioff" to listOf(P("M2 9a15 15 0 0 1 20 0M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M3 3l18 18")),
    "warn" to listOf(P("M12 3 2 20h20L12 3zM12 10v4M12 17h.01")),
    "moon" to listOf(P("M20 14A8 8 0 1 1 10 4a7 7 0 0 0 10 10z")),
    "film" to listOf(P("M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-12a2 2 0 0 1 2 -2z"), P("M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4")),
    "leave" to listOf(P("M14 4h5v16h-5M10 8l-4 4 4 4M6 12h10")),
    "washer" to listOf(P("M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z"), P("M7.5 13a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0z"), P("M7 6h2")),
    "dryer" to listOf(P("M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z"), P("M7.5 13a4.5 4.5 0 1 0 9 0a4.5 4.5 0 1 0 -9 0z"), P("M7 6h2M10 12c1-1 3 1 4 0")),
    "dish" to listOf(P("M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z"), P("M4 8h16M8 5.5h.01M8 12h8v5H8z")),
    "clock" to listOf(P("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z"), P("M12 7v5l3 2")),
    "search" to listOf(P("M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0z"), P("m20 20-4-4")),
    "smile" to listOf(P("M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z"), P("M8.5 14a4 4 0 0 0 7 0M9 9.5h.01M15 9.5h.01")),
    "cube" to listOf(P("m12 3 8 4.5v9L12 21l-8-4.5v-9L12 3zM12 12l8-4.5M12 12v9M12 12 4 7.5")),
    "plan" to listOf(P("M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1 -1 1h-14a1 1 0 0 1 -1 -1v-14a1 1 0 0 1 1 -1z"), P("M4 12h9V4M13 12v8")),
    "undo" to listOf(P("M9 14 4 9l5-5M4 9h11a5 5 0 0 1 0 10h-3")),
    "valve" to listOf(P("M3 12h3M18 12h3M6 8v8l12-8v8L6 8zM12 4v8M9 4h6")),
    "motion" to listOf(P("M11 4.5a2 2 0 1 0 4 0a2 2 0 1 0 -4 0z"), P("m8 21 2.5-6-2-3 2-4 3 3 3 1M10.5 15l3 2 1 4")),
    "refresh" to listOf(P("M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6")),
    "draw" to listOf(P("M4 3h16a1 1 0 0 1 1 1v16a1 1 0 0 1 -1 1h-16a1 1 0 0 1 -1 -1v-16a1 1 0 0 1 1 -1z"), P("M8 8h8v8H8z")),
    "select" to listOf(P("m5 3 14 8-6 2-2 6-6-16z")),
    "tablet" to listOf(P("M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z"), P("M9 21h6")),
    "phone" to listOf(P("M9 2h6a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-6a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2z"), P("M11 18h2")),
    "sparkle" to listOf(P("M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3zM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z")),
    "vol" to listOf(P("M4 9v6h4l5 4V5L8 9H4zM16 9a4 4 0 0 1 0 6M19 6a8 8 0 0 1 0 12")),
    "power" to listOf(P("M12 3v9M6.3 7a8 8 0 1 0 11.4 0")),
    "oven" to listOf(P("M6 3h12a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z"), P("M4 8h16M8 5.5h.01M12 5.5h.01M8 12h8v5H8z")),
    "heat" to listOf(P("M8 3c0 3-3 4-3 8a7 7 0 0 0 14 0c0-3-2-5-3-6 0 3-1.5 4-3 4 0-3 0-5-5-6z")),
    "key" to listOf(P("M4 15a4 4 0 1 0 8 0a4 4 0 1 0 -8 0z"), P("m11 12 9-9M17 6l2 2M15 8l2 2")),
    "door2" to listOf(P("M5 21V3h14v18M3 21h18"), P("M14.5 12a1 1 0 1 0 2 0a1 1 0 1 0 -2 0z")),
    "link" to listOf(P("M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1")),
    "qr" to listOf(P("M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h3v3h-3zM18 18h3v3h-3zM6 6h1v1H6zM17 6h1v1h-1zM6 17h1v1H6z")),
    "eye" to listOf(P("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"), P("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0z")),
    "hand" to listOf(P("M8 13V5a1.5 1.5 0 0 1 3 0v6M11 10V3.5a1.5 1.5 0 0 1 3 0V10M14 10V5a1.5 1.5 0 0 1 3 0v8M17 9.5a1.5 1.5 0 0 1 3 0V14a7 7 0 0 1-7 7h-1a7 7 0 0 1-6-3.5L4 14a1.5 1.5 0 0 1 2.5-1.5L8 14")),  )

  private val cache = HashMap<String, ImageVector>()

  /** The icon called [name]; unknown names fall back to "sparkle" like the mock. */
  operator fun get(name: String): ImageVector = synchronized(cache) {
    cache.getOrPut(name) { build(name, paths[name] ?: paths.getValue("sparkle")) }
  }

  private fun build(name: String, parts: List<P>): ImageVector =
      ImageVector.Builder(name = "cc_$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        parts.forEach { p ->
          if (p.fill) addPath(addPathNodes(p.d), fill = SolidColor(Color.Black))
          else addPath(addPathNodes(p.d), stroke = SolidColor(Color.Black), strokeLineWidth = STROKE,
              strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
      }.build()

  /** The glyph for an entity kind (room sheet tiles, plan markers, alerts). */
  fun forKind(kind: CcKind): String = when (kind) {
    CcKind.LIGHT -> "bulb"; CcKind.SWITCH -> "power"; CcKind.FAN -> "fan"; CcKind.LOCK -> "lock"
    CcKind.GARAGE -> "garage"; CcKind.COVER -> "blinds"; CcKind.CLIMATE -> "therm"; CcKind.MEDIA -> "tv"
    CcKind.CAMERA -> "cam"; CcKind.VALVE -> "valve"; CcKind.ALARM -> "shield"; CcKind.DOOR -> "door"
    CcKind.WINDOW -> "window"; CcKind.MOTION -> "motion"; CcKind.LEAK -> "leak"; CcKind.SMOKE -> "warn"
    CcKind.TEMPERATURE -> "therm"; CcKind.HUMIDITY -> "drop"; CcKind.POWER -> "bolt"; CcKind.ENERGY -> "bolt"
    CcKind.BATTERY -> "battery"; CcKind.APPLIANCE -> "washer"; CcKind.SCENE -> "sparkle"; CcKind.SCRIPT -> "sparkle"
    CcKind.PERSON -> "person"; CcKind.SENSOR -> "clock"; CcKind.OTHER -> "sparkle"
  }

  private const val STROKE = 2f
}

/** A mock icon at [size], tinted [tint]. */
@Composable
internal fun CcIcon(name: String, tint: Color, size: Dp = 28.dp, modifier: Modifier = Modifier) {
  Icon(CcIcons[name], contentDescription = null, tint = tint, modifier = modifier.size(size))
}
