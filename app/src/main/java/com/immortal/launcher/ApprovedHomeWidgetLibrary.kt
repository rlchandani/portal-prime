/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

internal data class ApprovedHomeLibraryCard(
    val key: String,
    val title: String,
    val icon: String,
    val description: String,
    val image: ImageBitmap? = null,
)

/** Full-screen native host: keeps Activity-owned app-widget binding results in Home. */
@Composable
internal fun ApprovedHomeWidgetLibraryScreen(
    room: String,
    connected: Boolean?,
    cards: List<ApprovedHomeLibraryCard>,
    providers: Boolean,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onCustomize: () -> Unit,
    onRefresh: () -> Unit,
    onPair: () -> Unit,
    onSleep: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
  Dialog(
      onDismissRequest = onBack,
      properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
  ) {
    val view = LocalView.current
    DisposableEffect(view) {
      (view.parent as? DialogWindowProvider)?.window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
          hide(WindowInsetsCompat.Type.systemBars())
          systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
      }
      onDispose { }
    }
    // Library and the retained launcher share the saved appearance.
    CompositionLocalProvider(LocalApprovedHomePopup provides true) {
      Column(Modifier.fillMaxSize().background(ApprovedHomePalette.Background)) {
        Row(Modifier.fillMaxWidth().height(103.dp).padding(horizontal = 56.dp),
            verticalAlignment = Alignment.CenterVertically) {
          Row(Modifier.heightIn(min = 68.dp)
              .semantics { contentDescription = "Home" }
              .tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f,
                  ringColor = ApprovedHomePalette.Accent, onClick = onHome),
              verticalAlignment = Alignment.CenterVertically) {
            Text("immortal", color = ApprovedHomePalette.Text, fontSize = 30.sp,
                fontWeight = FontWeight.Medium, letterSpacing = (-.6).sp)
          }
          Spacer(Modifier.width(30.dp))
          Box(Modifier.width(1.dp).height(40.dp).background(ApprovedHomePalette.Border))
          Text(room, color = ApprovedHomePalette.Secondary, fontSize = 22.sp,
              modifier = Modifier.padding(start = 24.dp))
          Row(Modifier.padding(start = 24.dp).heightIn(min = 68.dp)
              .tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f,
                  ringColor = ApprovedHomePalette.Accent, onClick = onBack)
              .padding(horizontal = 20.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ApprovedHomeIcon("back", Modifier.size(28.dp), ApprovedHomePalette.Accent)
            Text(if (providers) "Back" else "Home", color = ApprovedHomePalette.Accent,
                fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
          }
          Spacer(Modifier.weight(1f))
          ApprovedHomeIcon("wifi", Modifier.size(22.dp))
          Text(when (connected) { true -> "Connected"; false -> "Offline"; null -> "Connecting" },
              color = ApprovedHomePalette.Secondary, fontSize = 20.sp,
              modifier = Modifier.padding(start = 10.dp, end = 30.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LibraryRoundAction("remote", "Connect your phone", onPair)
            LibraryRoundAction("sleep", "Sleep screen", onSleep)
            ApprovedThemeButton()
            LibraryRoundAction("search", "Search apps and settings", onSearch)
            LibraryRoundAction("settings", "Settings", onSettings)
          }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ApprovedHomePalette.Border))
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 56.dp, end = 56.dp, top = 36.dp, bottom = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
          item(key = "heading", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
            Column(Modifier.padding(top = 12.dp, bottom = 6.dp)) {
              Text(if (providers) "Android app widgets" else "Home & widgets",
                  color = ApprovedHomePalette.Text, fontSize = 44.sp, lineHeight = 56.sp,
                  fontWeight = FontWeight.Medium, letterSpacing = (-.5).sp)
              Text(if (providers) "Choose a widget from an installed app."
                  else "Add, configure or remove any existing widget family.",
                  color = ApprovedHomePalette.Secondary, fontSize = 23.sp, lineHeight = 33.sp,
                  modifier = Modifier.padding(top = 12.dp))
              Row(Modifier.padding(top = 34.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LibraryPrimaryAction(if (providers) "Refresh" else "Customize home",
                    if (providers) onRefresh else onCustomize)
                if (providers) ApprovedHomeTextButton("Home & widgets",
                    Modifier.heightIn(min = 68.dp), onClick = onBack)
              }
            }
          }
          if (providers && cards.isEmpty()) {
            item(key = "provider-status", span = { GridItemSpan(maxLineSpan) }, contentType = "status") {
              Text(if (loading) "Loading installed app widgets…" else "No app widgets installed.",
                  color = ApprovedHomePalette.Secondary, fontSize = 23.sp, lineHeight = 33.sp,
                  modifier = Modifier.padding(28.dp))
            }
          }
          items(cards, key = { it.key }, contentType = { "widget-choice" }) { card ->
            ApprovedHomeSurface(
                Modifier.fillMaxWidth().heightIn(min = 244.dp)
                    .tvFocusable(RoundedCornerShape(24.dp), focusScale = 1f,
                        ringColor = ApprovedHomePalette.Accent) { onSelect(card.key) },
                radius = 24.dp,
            ) {
              Column(Modifier.fillMaxWidth().padding(28.dp),
                  verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (card.image != null) Image(card.image, contentDescription = null, modifier = Modifier.size(38.dp))
                else ApprovedHomeIcon(card.icon, Modifier.size(38.dp), ApprovedHomePalette.Accent)
                Text(card.title, color = ApprovedHomePalette.Text, fontSize = 28.sp, lineHeight = 38.sp,
                    fontWeight = FontWeight.Medium)
                Text(card.description, color = ApprovedHomePalette.Secondary,
                    fontSize = 22.sp, lineHeight = 31.sp)
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LibraryRoundAction(icon: String, label: String, onClick: () -> Unit) {
  Box(Modifier.size(68.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome)
      .semantics { contentDescription = label }
      .tvFocusable(CircleShape, focusScale = 1f, ringColor = ApprovedHomePalette.Accent, onClick = onClick),
      contentAlignment = Alignment.Center) {
    ApprovedHomeIcon(icon, Modifier.size(30.dp), ApprovedHomePalette.Text)
  }
}

@Composable
private fun LibraryPrimaryAction(label: String, onClick: () -> Unit) {
  val palette = com.immortal.launcher.ui.theme.LocalApprovedPalette.current
  Box(Modifier.heightIn(min = 68.dp).clip(RoundedCornerShape(16.dp))
      .background(palette.accent)
      .tvFocusable(RoundedCornerShape(16.dp), focusScale = 1f,
          ringColor = if (palette.dark) palette.background else androidx.compose.ui.graphics.Color.White,
          onClick = onClick)
      .padding(horizontal = 26.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
    Text(label, color = if (palette.dark) palette.background else androidx.compose.ui.graphics.Color.White,
        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium)
  }
}
