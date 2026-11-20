/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.components.Glass

internal fun customWidgetLabel(kind: String): String =
    when (kind) {
      HomeWidgetStore.KIND_WEATHER -> "Weather"
      HomeWidgetStore.KIND_WORLD_CLOCK -> "World Clock"
      HomeWidgetStore.KIND_TIMERS -> "Timers"
      HomeWidgetStore.KIND_CALENDAR -> "Calendar"
      HomeWidgetStore.KIND_ALARM -> "Alarm"
      HomeWidgetStore.KIND_CRYPTO -> "Crypto"
      HomeWidgetStore.KIND_TODO -> "Household list"
      HomeWidgetStore.KIND_ROUTINES -> "Routines"
      HomeWidgetStore.KIND_CHORES -> "Chores"
      HomeWidgetStore.KIND_CALLS -> "Calls"
      HomeWidgetStore.KIND_NEWS -> "News"
      HomeWidgetStore.KIND_STOCKS -> "Stocks"
      HomeWidgetStore.KIND_OPTIONS -> "Options"
      HomeWidgetStore.KIND_HA -> "Home Assistant"
      HomeWidgetStore.KIND_TODAY -> "Today"
      HomeWidgetStore.KIND_QUICK_CONTROLS -> "Quick Controls"
      HomeWidgetStore.KIND_PHOTO_MEMORY -> "Photo Memory"
      HomeWidgetStore.KIND_WIFI -> "Wi-Fi"
      else -> "Widget"
    }

@Composable
private fun AddWidgetButton(onClick: () -> Unit) {
  Surface(
      color = Color(0xFF5B6BC0),
      shape = RoundedCornerShape(30.dp),
      modifier = Modifier.width(124.dp).height(60.dp).tvFocusable(RoundedCornerShape(30.dp)) {
        onClick()
      },
  ) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp),
    ) {
      Text("+", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Light)
      Text("Widget", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
  }
}

@Composable
private fun WidgetPickerOverlay(
    providers: List<WidgetProviderEntry>,
    status: String?,
    onPick: (WidgetProviderEntry) -> Unit,
    onDismiss: () -> Unit,
) {
  val noRipple = remember { MutableInteractionSource() }
  BackHandler { onDismiss() }
  val gridFocus = remember { FocusRequester() }
  LaunchedEffect(providers) { runCatching { gridFocus.requestFocus() } }

  Box(
      contentAlignment = Alignment.Center,
      modifier =
          Modifier.fillMaxSize()
              .onPreviewKeyEvent { e ->
                if (e.key == Key.Back || e.key == Key.Escape) {
                  if (e.type == KeyEventType.KeyUp) onDismiss()
                  true
                } else false
              }
              .background(Glass.Scrim)
              .clickable(interactionSource = noRipple, indication = null) { onDismiss() },
  ) {
    com.immortal.launcher.ui.components.DarkDialogSurface(
        modifier =
            Modifier.widthIn(max = 920.dp)
                .fillMaxWidth(0.78f)
                .heightIn(max = 620.dp)
                .clickable(interactionSource = noRipple, indication = null) {},
    ) {
      Column(modifier = Modifier.padding(28.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Column(modifier = Modifier.weight(1f)) {
            Text("Add widget", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Choose a widget provider installed on this Portal.",
                color = Color(0xFF9A9A9A),
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
          }
          Surface(
              color = Color(0x33FFFFFF),
              shape = CircleShape,
              modifier = Modifier.size(44.dp).tvFocusable(CircleShape) { onDismiss() },
          ) {
            Box(contentAlignment = Alignment.Center) { Text("✕", color = Color.White, fontSize = 20.sp) }
          }
        }
        if (status != null) {
          Text(
              status,
              color = Color(0xFF8AB4F8),
              fontSize = 13.sp,
              modifier = Modifier.padding(top = 14.dp),
          )
        }
        Spacer(Modifier.size(20.dp))
        if (providers.isEmpty()) {
          Box(
              modifier = Modifier.fillMaxWidth().height(220.dp),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                "No widget providers are installed.",
                color = Color(0xFFB8B8B8),
                fontSize = 16.sp,
            )
          }
        } else {
          LazyVerticalGrid(
              columns = GridCells.Fixed(3),
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
              modifier = Modifier.focusRequester(gridFocus).focusGroup(),
          ) {
            items(providers, key = { it.info?.provider?.flattenToString() ?: "custom:${it.customKind}" }) { provider ->
              WidgetProviderTile(provider = provider, onClick = { onPick(provider) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WidgetProviderTile(provider: WidgetProviderEntry, onClick: () -> Unit) {
  Surface(
      color = Color(0xFF29292C),
      shape = RoundedCornerShape(18.dp),
      modifier = Modifier.tvFocusable(RoundedCornerShape(18.dp)) { onClick() },
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
          color = Color(0xFF38383C),
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier.size(58.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (provider.icon != null) {
            Image(
                bitmap = provider.icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)),
            )
          } else if (provider.customKind != null) {
            CustomWidgetGlyph(provider.customKind)
          } else {
            WidgetGlyph()
          }
        }
      }
      Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
        Text(
            provider.label,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            provider.packageLabel,
            color = Color(0xFF9A9A9A),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            "${provider.spanX} x ${provider.spanY}",
            color = Color(0xFF7C7C7C),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 5.dp),
        )
      }
    }
  }
}

@Composable
private fun CustomWidgetGlyph(kind: String) {
  val emoji =
      when (kind) {
        HomeWidgetStore.KIND_WEATHER -> "☁"
        HomeWidgetStore.KIND_WORLD_CLOCK -> "◷"
        HomeWidgetStore.KIND_TIMERS -> "⏱"
        HomeWidgetStore.KIND_CRYPTO -> "₿"
        HomeWidgetStore.KIND_TODO -> "✓"
        HomeWidgetStore.KIND_ROUTINES -> "★"
        HomeWidgetStore.KIND_CHORES -> "★"
        HomeWidgetStore.KIND_CALLS -> "☎"
        HomeWidgetStore.KIND_NEWS -> "📰"
        HomeWidgetStore.KIND_STOCKS -> "📈"
        HomeWidgetStore.KIND_OPTIONS -> "Δ"
        HomeWidgetStore.KIND_HA -> "🏠"
        HomeWidgetStore.KIND_WIFI -> "📶"
        else -> "+"
      }
  Text(emoji, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun WidgetGlyph() {
  val path = remember { PathParser().parsePathString(ICON_WIDGETS).toPath() }
  Canvas(Modifier.size(28.dp)) {
    val s = size.minDimension / 24f
    scale(s, s, pivot = Offset.Zero) { drawPath(path, Color.White) }
  }
}

@Composable
internal fun ApprovedWidgetLibrary(
    advanced: Boolean,
    configured: List<HomeWidgetStore.HomeWidget>,
    providers: List<WidgetProviderEntry>?,
    room: String,
    connected: Boolean?,
    onAdd: (String) -> Unit,
    onProvider: (WidgetProviderEntry) -> Unit,
    onDismiss: () -> Unit,
    onHome: () -> Unit,
    onCustomize: () -> Unit,
    onRefresh: () -> Unit,
    onPair: () -> Unit,
    onSleep: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
) {
  var showProviders by rememberSaveable { mutableStateOf(false) }
  val kinds = remember(configured) { configured.map { it.kind }.toSet() }
  val builtIns = remember {
    listOf(
        Triple(HomeWidgetStore.KIND_TODAY, "Today", "calendar"),
        Triple(HomeWidgetStore.KIND_HA, "Home Assistant", "ha_widget"),
        Triple(HomeWidgetStore.KIND_CALENDAR, "Calendar", "calendar"),
        Triple(HomeWidgetStore.KIND_WEATHER, "Weather", "weather"),
        Triple(HomeWidgetStore.KIND_ALARM, "Alarm", "alarm"),
        Triple(HomeWidgetStore.KIND_WORLD_CLOCK, "World clock", "clock"),
        Triple(HomeWidgetStore.KIND_CRYPTO, "Crypto", "calculator"),
        Triple(HomeWidgetStore.KIND_QUICK_CONTROLS, "Quick controls", "tools"),
        Triple(HomeWidgetStore.KIND_PHOTO_MEMORY, "Photo memory", "photo"),
        Triple(HomeWidgetStore.KIND_TIMERS, "Timers", "timer"),
        Triple(HomeWidgetStore.KIND_TODO, "Household list", "notes"),
        Triple(HomeWidgetStore.KIND_WIFI, "Wi-Fi", "wifi"),
        Triple(HomeWidgetStore.KIND_CHORES, "Chores", "tools"),
        Triple(HomeWidgetStore.KIND_CALLS, "Calls", "calls"),
        Triple(HomeWidgetStore.KIND_NEWS, "News", "globe"),
        Triple(HomeWidgetStore.KIND_STOCKS, "Stocks", "calculator"),
        Triple(HomeWidgetStore.KIND_OPTIONS, "Options", "calculator"),
        Triple(HomeWidgetStore.KIND_APP_WIDGET, "Android app widget", "apps"),
    )
  }
  val choices = remember(advanced, kinds, builtIns) {
    builtIns.filter { advanced || it.first != HomeWidgetStore.KIND_OPTIONS }
        .map { (kind, title, icon) ->
          ApprovedHomeLibraryCard(kind, title, icon, if (kind in kinds) "On your home" else "Add to your home")
        }
  }
  val providerByKey = remember(providers) {
    providers.orEmpty().associateBy { it.info!!.provider.flattenToString() }
  }
  val providerCards = remember(providerByKey) {
    providerByKey.map { (key, entry) ->
      ApprovedHomeLibraryCard(key, entry.label, "apps", entry.packageLabel, entry.icon)
    }
  }
  ApprovedHomeWidgetLibraryScreen(
      room, connected, if (showProviders) providerCards else choices, showProviders, providers == null,
      onSelect = { key ->
        if (showProviders) providerByKey[key]?.let(onProvider)
        else if (key == HomeWidgetStore.KIND_APP_WIDGET) showProviders = true
        else onAdd(key)
      },
      onBack = { if (showProviders) showProviders = false else onDismiss() },
      onHome = onHome, onCustomize = onCustomize, onRefresh = onRefresh,
      onPair = onPair, onSleep = onSleep, onSearch = onSearch, onSettings = onSettings,
  )
}
