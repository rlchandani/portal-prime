/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.PortalPrimeTheme

/**
 * Tools list and in-tab detail workspaces, also embedded in the native Home launcher.
 * Only explicit camera viewing and lamp preview actions hand off to full-screen Activities.
 */
class ToolsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.Theme_SampleApp)
    super.onCreate(savedInstanceState)
    setContent { PortalPrimeTheme(darkTheme = false) { ApprovedToolsScreen(::finish) } }
  }
}

/** One retained Tools host; only explicit fullscreen actions launch another Activity. */
@Composable
internal fun ApprovedToolsScreen(
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    libraryRequest: Int = 0,
) = ApprovedToolsHost(onExit, modifier, embedded, libraryRequest)

/** A small circular ✕ close button (top-right of the folder / tool cards). */
@Composable
internal fun CloseCircle(onClick: () -> Unit) {
  Surface(
      color = IosDivider,
      shape = CircleShape,
      modifier = Modifier.size(68.dp).tvFocusable(CircleShape, focusScale = 1f) { onClick() },
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text("✕", color = IosSecondary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
  }
}
