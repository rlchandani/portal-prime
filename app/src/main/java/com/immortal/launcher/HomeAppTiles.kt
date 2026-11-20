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
import androidx.compose.foundation.background
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

internal const val ICON_WIDGETS =
    "M3 3h8v8H3V3zm10 0h8v5h-8V3zM3 13h5v8H3v-8zm7 0h11v8H10v-8z"



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
