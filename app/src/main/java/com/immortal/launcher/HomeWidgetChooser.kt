/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

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
      HomeWidgetStore.KIND_SPORTS -> "Sports"
      HomeWidgetStore.KIND_OPTIONS -> "Options"
      HomeWidgetStore.KIND_HA -> "Home Assistant"
      HomeWidgetStore.KIND_TODAY -> "Today"
      HomeWidgetStore.KIND_QUICK_CONTROLS -> "Quick Controls"
      HomeWidgetStore.KIND_PHOTO_MEMORY -> "Photo Memory"
      HomeWidgetStore.KIND_WIFI -> "Wi-Fi"
      else -> "Widget"
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
  val context = androidx.compose.ui.platform.LocalContext.current
  // Jarvis personalization's Sports widget is offered only while "learn from conversations" is on.
  val sports = remember { com.immortal.launcher.memory.JarvisMemory.enabled(context) }
  val choices = remember(advanced, kinds, builtIns, sports) {
    (if (sports) builtIns.dropLast(1) + Triple(HomeWidgetStore.KIND_SPORTS, "Sports", "interest_ball") + builtIns.last()
        else builtIns)
        .filter { advanced || it.first != HomeWidgetStore.KIND_OPTIONS }
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
