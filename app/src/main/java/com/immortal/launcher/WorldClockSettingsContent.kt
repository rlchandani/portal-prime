/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.app.Activity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** A curated set of cities for the world-clock picker (label → IANA tz id). */
private val WORLD_CLOCK_CITIES =
    listOf(
        "Cupertino" to "America/Los_Angeles",
        "Denver" to "America/Denver",
        "New York" to "America/New_York",
        "São Paulo" to "America/Sao_Paulo",
        "London" to "Europe/London",
        "Paris" to "Europe/Paris",
        "Berlin" to "Europe/Berlin",
        "Cape Town" to "Africa/Johannesburg",
        "Dubai" to "Asia/Dubai",
        "Mumbai" to "Asia/Kolkata",
        "Singapore" to "Asia/Singapore",
        "Tokyo" to "Asia/Tokyo",
        "Sydney" to "Australia/Sydney",
    )

/** The world-clock locations picker. Tapping a city toggles it; the widget shows the first four in
 * selection order. Back returns to the main settings page. */
@Composable
internal fun WorldClockScreen(onBack: () -> Unit) {
  SettingsScaffold(
      title = "World clock",
      subtitle =
          "Choose locations in display order. The widget fits complete clocks, up to four.",
      onBack = onBack,
  ) { WorldClockContent() }
}

/** Inlinable body of the World clock screen — rendered by [WorldClockScreen] (standalone Activity
 *  host) and by the two-panel settings detail panel. */
@Composable
internal fun WorldClockContent() {
  val context = LocalContext.current
  var selected by remember { mutableStateOf(ImmortalSettings.worldClockZones(context)) }

  Column {
      Card {
        LazyColumn(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp - 400).coerceAtLeast(400).dp)) {
        itemsIndexed(WORLD_CLOCK_CITIES, key = { _, city -> city.second }) { i, (label, zone) ->
          if (i > 0) Divider()
          val on = zone in selected
          val rank = selected.indexOf(zone)
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .defaultMinSize(minHeight = Sizes.rowMinHeight)
                      .tvFocusableRow(focusFill = IosFocusTint) {
                        selected = if (on) selected - zone else selected + zone
                        ImmortalSettings.setWorldClockZones(context, selected)
                      }
                      .semantics {
                        role = Role.Switch
                        toggleableState = ToggleableState(on)
                      }
                      .padding(horizontal = 28.dp, vertical = 22.dp),
              verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(label, color = IosLabel, fontSize = Sizes.rowTitle)
              Text(
                  zone.replace('_', ' '),
                  color = IosSecondary,
                  fontSize = Sizes.rowSub,
                  modifier = Modifier.padding(top = 2.dp),
              )
            }
            if (on && rank in 0..3) {
              Text(
                  "#${rank + 1}",
                  color = IosBlue,
                  fontSize = Sizes.rowSub,
                  fontWeight = FontWeight.SemiBold,
                  modifier = Modifier.padding(end = 14.dp),
              )
            } else if (on) {
              Text("hidden", color = IosSecondary, fontSize = Sizes.rowSub, modifier = Modifier.padding(end = 14.dp))
            }
            ApprovedSettingsSwitch(on)
          }
        }
      }
      }
      Text(
          "Tip: resize the widget to see more clocks. Re-pick a city to move it to the end of the order.",
          color = IosSecondary,
          fontSize = Sizes.rowSub,
          modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
      )
    }
}
