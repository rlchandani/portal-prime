/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Presentation only: enumeration, playback and persistence remain owned by the caller. */
@Composable
internal fun InstalledVoiceChoiceDialog(
    voices: List<InstalledVoiceChoice>, current: String, loading: Boolean, loadFailed: Boolean,
    enabled: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit,
) {
  var query by remember { mutableStateOf("") }
  val matches = remember(voices, query) { filterInstalledVoices(voices, query) }
  val scroll = rememberLazyListState()
  LaunchedEffect(matches, current) {
    if (matches.isNotEmpty()) scroll.scrollToItem(matches.indexOfFirst { it.name == current }.coerceAtLeast(0))
  }
  fun choose(name: String) { if (enabled) { onPick(name); onDismiss() } }
  ApprovedSettingsDialog("Choose a voice", onDismiss,
      help = "Choose an offline voice installed on this device. This changes spoken time only.") {
    IosTextField(query, { query = it }, "Search voices", modifier = Modifier.semantics {
      contentDescription = "Search installed voices"
    })
    VoiceChoiceRow("Default voice", "Use this device's default voice.", current.isBlank(), enabled,
        Modifier.padding(top = 16.dp)) { choose("") }
    if (current.isNotBlank() && !loading && voices.none { it.name == current }) {
      FooterNote("Your saved voice is unavailable. It stays selected until you choose another voice.")
    }
    Text(if (query.isBlank()) "${voices.size} installed voices" else "${matches.size} matching voices",
        color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    // Keep the chooser stable while typing; only its bounded list content changes.
    LazyColumn(Modifier.fillMaxWidth().height(360.dp).semantics {
      contentDescription = "Installed voices"
    }, state = scroll) {
      if (loading || loadFailed || voices.isEmpty() || matches.isEmpty()) {
        item(key = "voice-state") {
          Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
            Text(when {
              loading -> "Loading installed voices…"
              loadFailed -> "Couldn't load installed voices. Default voice is still available."
              voices.isEmpty() -> "No offline voices installed. Default voice is still available."
              else -> "No matching voices. Try a voice name, language or region."
            }, color = IosSecondary, fontSize = 23.sp, lineHeight = 34.sp)
          }
        }
      } else {
        items(matches, key = { it.name }) { voice ->
          VoiceChoiceRow(voice.label, voice.localeLabel, current == voice.name, enabled) { choose(voice.name) }
        }
      }
    }
    ApprovedSettingsTextButton("Cancel", onDismiss)
  }
}

@Composable
private fun VoiceChoiceRow(
    label: String, detail: String, isSelected: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
  val shape = RoundedCornerShape(16.dp)
  Row(modifier.fillMaxWidth().defaultMinSize(minHeight = 68.dp)
      .background(if (isSelected) IosFocusTint else Color.Transparent, shape)
      .tvFocusable(shape, focusScale = 1f, ringColor = IosBlue, enabled = enabled, onClick = onClick)
      .semantics(mergeDescendants = true) { selected = isSelected; role = Role.RadioButton }
      .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text(label, color = IosLabel, fontSize = 26.sp, lineHeight = 34.sp,
          fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
      if (detail.isNotBlank()) Text(detail, color = IosSecondary, fontSize = 22.sp, lineHeight = 30.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Text(if (isSelected) "✓" else "", color = IosBlue, fontSize = 28.sp,
        modifier = Modifier.width(36.dp).clearAndSetSemantics {})
  }
}
