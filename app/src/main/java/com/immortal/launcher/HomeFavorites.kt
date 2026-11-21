/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.Image
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
private val FAVORITE_TILE_HEIGHT = 72.dp
private val FAVORITE_ICON_SIZE = 46.dp
/** Upright Mini: six ~112 dp tiles, so the label sits under the icon instead of beside it. */
private val COMPACT_FAVORITE_TILE_HEIGHT = 88.dp

internal fun favoriteTileHeight(layout: LayoutClass): Dp =
    if (layout.compactPortrait) COMPACT_FAVORITE_TILE_HEIGHT else FAVORITE_TILE_HEIGHT

@Composable
internal fun ApprovedFavoriteTile(app: AppEntry, modifier: Modifier, onClick: () -> Unit, onLongClick: () -> Unit,
                                 layout: LayoutClass = LayoutClass.LARGE) {
  if (layout.compactPortrait) {
    ApprovedHomeSurface(modifier.height(COMPACT_FAVORITE_TILE_HEIGHT), radius = 16.dp) {
      Column(Modifier.fillMaxSize().combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
          .padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center) {
        Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Text(app.label, color = ApprovedHomePalette.Text, fontSize = 16.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp))
      }
    }
    return
  }
  ApprovedHomeSurface(modifier.height(FAVORITE_TILE_HEIGHT), radius = 16.dp) {
    Row(Modifier.fillMaxSize().combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Image(app.icon, contentDescription = null, modifier = Modifier.size(FAVORITE_ICON_SIZE))
      Text(app.label, color = ApprovedHomePalette.Text, fontSize = if (layout.compactLandscape) 19.sp else 22.sp,
          lineHeight = 30.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}
