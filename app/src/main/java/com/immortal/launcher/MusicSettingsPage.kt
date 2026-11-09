/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.immortal.launcher.settings.MusicSettings
import org.json.JSONObject
import kotlin.math.roundToInt

private val LEVEL_LABEL_WIDTH = 240.dp
private val LEVEL_VALUE_WIDTH = 110.dp

/**
 * Settings › Music: "Music follows you" ([FollowMeSettingsSection]), the quiet-hours music cap (written through [MusicSettings.domain], like the
 * phone remote) and "Clear music history", which clears Recently played on every Portal.
 */
@Composable
internal fun MusicSettingsContent() {
  val context = LocalContext.current
  var settings by remember { mutableStateOf(MusicNightConfig.load(context)) }
  fun apply(key: String, value: Any) {
    MusicSettings.domain.apply(context, JSONObject().put(key, value))
    settings = MusicNightConfig.load(context)
  }
  SectionLabel("Rooms")
  FollowMeSettingsSection()
  SectionLabel("Quiet hours")
  Card {
    ToggleRow("Quieter music during quiet hours", settings.quietCap) { apply("quietCap", it) }
    if (settings.quietCap) {
      Divider()
      LevelRow(settings.quietMaxLevel) { apply("quietMaxLevel", it) }
    }
  }
  FooterNote("Music can't go above this level while quiet hours are on. Quiet hours are set in Sounds. " +
      "Announcements aren't affected.")
  SectionLabel("History")
  Card { ClearHistoryRow() }
  FooterNote("Recently played is kept on your Portals and shown on paired phones. It's never sent anywhere else.")
}

@Composable
private fun LevelRow(level: Int, onChange: (Int) -> Unit) {
  var shown by remember(level) { mutableStateOf(level.toFloat()) }
  Row(Modifier.fillMaxWidth().defaultMinSize(minHeight = Sizes.rowMinHeight).padding(horizontal = 16.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically) {
    Text("Loudest music", color = IosLabel, fontSize = Sizes.rowTitle, modifier = Modifier.width(LEVEL_LABEL_WIDTH))
    Slider(value = shown, onValueChange = { shown = it }, onValueChangeFinished = { onChange(shown.roundToInt()) },
        valueRange = MusicNightCapMath.MIN_LEVEL.toFloat()..MusicNightCapMath.SCALE.toFloat(),
        steps = MusicNightCapMath.SCALE - MusicNightCapMath.MIN_LEVEL - 1,
        colors = SliderDefaults.colors(thumbColor = IosBlue, activeTrackColor = IosBlue, inactiveTrackColor = IosDivider),
        modifier = Modifier.weight(1f).semantics { contentDescription = "Loudest music during quiet hours" })
    Text("${shown.roundToInt()} of ${MusicNightCapMath.SCALE}", color = IosSecondary, fontSize = Sizes.rowValue,
        modifier = Modifier.width(LEVEL_VALUE_WIDTH), textAlign = TextAlign.End)
  }
}

/** Asks once before clearing; the row then says it's done. */
@Composable
private fun ClearHistoryRow() {
  val context = LocalContext.current
  var confirming by remember { mutableStateOf(false) }
  var cleared by remember { mutableStateOf(false) }
  when {
    cleared -> NavRow("Music history cleared", enabled = false) {}
    confirming -> {
      NavRow("Clear on every Portal?", value = "Clear") {
        MusicHistoryFeature.clear(context)
        MusicLocalSearch.clear(context)
        confirming = false
        cleared = true
      }
      Divider()
      NavRow("Keep it", onClick = { confirming = false })
    }
    else -> NavRow("Clear music history", subLabel = "Recently played on every Portal, and this Portal's searches") {
      confirming = true
    }
  }
}
