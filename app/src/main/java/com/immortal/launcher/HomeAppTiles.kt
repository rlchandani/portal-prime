/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.view.ViewGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.immortal.launcher.ui.components.GlassCard
import com.immortal.launcher.ui.theme.ContentSecondary

private const val UPDATE_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000 // 6 hours

// --- tile sizing ----------------------------------------------------------------
// The grid's tile edge, provided once at the top of the tree so every tile
// (apps, folders, built-ins, the drag ghost) follows the user's size setting.
// Standard is the original 6-column/88dp look; Large is 5 columns of 110dp tiles,
// closer to the stock Portal launcher.
private val LocalTileDp = compositionLocalOf { 88.dp }

private fun tileDpFor(size: String): Dp =
    when (size) {
      ImmortalSettings.SIZE_XL -> 140.dp
      ImmortalSettings.SIZE_LARGE -> 110.dp
      else -> 88.dp
    }

private fun gridColumnsFor(size: String): Int =
    when (size) {
      ImmortalSettings.SIZE_XL -> 4
      ImmortalSettings.SIZE_LARGE -> 5
      else -> 6
    }

@Composable
private fun PortalHomeTile(onClick: () -> Unit) {
  // Bridge to Meta's stock launcher — the only context allowed to open the
  // trusted-caller apps (Contacts, Camera, Photos), so this is how the user
  // reaches calling. Tapping Portal's home button returns to Immortal.
  BuiltInTile(
      label = "Calls",
      background = Color(0xFF1FA463),
      glyph = ICON_CALL,
      onClick = onClick,
  )
}

// Material-style glyph paths (24x24 viewport), rendered crisply as vectors.
private const val ICON_CALL =
    "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
private const val ICON_DOWNLOAD = "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z"
private const val ICON_IMAGE =
    "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"
internal const val ICON_WIDGETS =
    "M3 3h8v8H3V3zm10 0h8v5h-8V3zM3 13h5v8H3v-8zm7 0h11v8H10v-8z"
private const val ICON_HELP =
    "M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4z"
private const val ICON_GEAR =
    "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"

/** A built-in launcher tile: a rounded colour tile with a centered white vector
 * glyph, styled to sit naturally beside real app icons. */
@Composable
private fun BuiltInTile(
    label: String,
    background: Color,
    glyph: String,
    onClick: () -> Unit,
) {
  val path = remember(glyph) { PathParser().parsePathString(glyph).toPath() }
  val tileDp = LocalTileDp.current
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier.fillMaxWidth().padding(4.dp).tvFocusable(RoundedCornerShape(22.dp)) { onClick() },
  ) {
    GlassCard(
        modifier = Modifier.size(tileDp),
        cornerRadius = 20.dp,
    ) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(46.dp * (tileDp / 88.dp))) {
          val s = size.minDimension / 24f
          scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
        }
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        label,
        color = ContentSecondary,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
  }
}



@Composable
private fun StoreTile(onClick: () -> Unit) {
  BuiltInTile(
      label = "App Store",
      background = Color(0xFF2D6CDF),
      glyph = ICON_DOWNLOAD,
      onClick = onClick,
  )
}

@Composable
private fun ToolsTile(onClick: () -> Unit) {
  BuiltInTile(
      label = "Tools",
      background = Color(0xFF5A5F6A),
      glyph = ICON_WIDGETS,
      onClick = onClick,
  )
}

@Composable
private fun WidgetTile(
    widget: HomeWidgetStore.HomeWidget,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    editMode: Boolean,
    tileDp: Dp,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
  val info = remember(widget.appWidgetId) {
    if (widget.isAppWidget) manager.getAppWidgetInfo(widget.appWidgetId) else null
  }
  val height =
      tileDp * widget.spanY.toFloat() +
          20.dp * (widget.spanY - 1).coerceAtLeast(0).toFloat()

  LaunchedEffect(widget, tileDp) {
    if (widget.isAppWidget) updateWidgetSizeOptions(manager, widget, tileDp)
  }

  Box(modifier = modifier.fillMaxWidth().height(height).padding(4.dp)) {
    Surface(
        color = Color(0xFF252527),
        shape = RoundedCornerShape(20.dp),
        modifier =
            Modifier.fillMaxSize()
                .alpha(if (dimmed) 0.3f else 1f)
                .jiggle(editMode && !dimmed, widget.key.hashCode()),
    ) {
      if (!widget.isAppWidget) {
        ImmortalWidgetContent(widget = widget, modifier = Modifier.fillMaxSize())
      } else if (info != null) {
        AndroidView(
            factory = {
              host.createView(it, widget.appWidgetId, info).apply {
                setAppWidget(widget.appWidgetId, info)
                setPadding(0, 0, 0, 0)
                isFocusable = true
                isFocusableInTouchMode = true
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
              }
            },
            update = { view ->
              view.setAppWidget(widget.appWidgetId, info)
              view.isEnabled = !editMode
            },
            modifier = Modifier.fillMaxSize(),
        )
      } else {
        Box(
            Modifier.fillMaxSize().background(Color(0xFF303033)),
            contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WidgetGlyph()
            Text(
                "Widget unavailable",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                widget.providerPackage,
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp, start = 18.dp, end = 18.dp),
            )
          }
        }
      }
    }
    if (editMode) {
      // Dim/scrim only — the drag scrim and ✕ / resize controls are added by the enclosing
      // WidgetCell so they layer correctly above the hosted widget's own touch handling.
      Box(
          Modifier.fillMaxSize()
              .jiggle(!dimmed, widget.key.hashCode())
              .clip(RoundedCornerShape(20.dp))
              .background(Color(0x66000000)))
    }
  }
}

/**
 * Wraps a tile whose own content does NOT swallow touches (built-ins, folders, apps) with a
 * dragged-dim. The drag itself is handled by the stable container detector that wraps the pager, so
 * the gesture survives a page flip (letting a tile be dragged onto another page). Hosted widgets
 * still use their own in-page scrim handle (see [WidgetCell]), since their AndroidView swallows it.
 */
@Composable
private fun HomeTileFrame(
    modifier: Modifier,
    dragged: Boolean,
    content: @Composable () -> Unit,
) {
  Box(modifier = modifier.fillMaxWidth().alpha(if (dragged) 0f else 1f)) { content() }
}

/** Widget tile + the ✕ remove badge in edit mode. Drag is handled by the stable container detector
 * (which grabs widgets via the pointer Initial pass), so widgets drag and move across pages like
 * apps. Widgets are not user-resizable — they keep the size their provider declares. */
@Composable
private fun WidgetCell(
    widget: HomeWidgetStore.HomeWidget,
    host: AppWidgetHost,
    manager: AppWidgetManager,
    editMode: Boolean,
    tileDp: Dp,
    dimmed: Boolean,
    modifier: Modifier,
    onRemove: () -> Unit,
) {
  Box(modifier = modifier) {
    WidgetTile(
        widget = widget,
        host = host,
        manager = manager,
        editMode = editMode,
        tileDp = tileDp,
        dimmed = dimmed,
    )
    if (editMode) {
      RemoveBadge(
          size = 30.dp,
          modifier = Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp),
          onClick = onRemove,
      )
    }
  }
}

@Composable
private fun AppTile(
    app: AppEntry,
    editMode: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    onDelete: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      // In Manage mode the body tap is inert (drag to reorder, ✕ to remove); the
      // icon launches normally otherwise. A long-press enters Manage mode (iOS-style).
      modifier =
          modifier.fillMaxWidth().padding(4.dp).tvFocusable(
              RoundedCornerShape(22.dp),
              enabled = !editMode,
              onLongClick = onLongPress,
          ) {
            onClick()
          },
  ) {
    // The icon (and its delete badge) jiggle as a unit in Manage mode; the label stays still.
    Box(modifier = Modifier.jiggle(editMode && !dimmed, app.component.packageName.hashCode())) {
      Image(
          bitmap = app.icon,
          contentDescription = app.label,
          modifier =
              Modifier.size(LocalTileDp.current)
                  .alpha(if (dimmed) 0.3f else 1f),
      )
      if (editMode) {
        // Sits at the icon's outer top-right corner (nudged off the icon, iOS-style).
        RemoveBadge(
            size = 26.dp,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-9).dp),
            onClick = onDelete,
        )
      }
    }
    Spacer(Modifier.size(8.dp))
    Text(
        app.label,
        color = ContentSecondary,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
  }
}

// --- data ---------------------------------------------------------------------
