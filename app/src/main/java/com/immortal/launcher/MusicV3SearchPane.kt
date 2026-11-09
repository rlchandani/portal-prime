/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Search tab. Searching costs daily budget, so it runs only on Search/Enter ([onSubmit]) or a tap on
 * a search/artist suggestion; typing only shows local [suggestions] (no quota). An empty field shows
 * Recent searches and Recently played. Recent chips reopen saved results for free, even while resting.
 */
@Composable
internal fun MusicV3SearchPane(
    state: MusicV3SearchState,
    suggestions: List<MusicSuggestion>,
    onQuery: (String) -> Unit,
    onSubmit: (String) -> Unit,
    actions: MusicV3Actions,
    onFocus: (Boolean) -> Unit = {},
    autoFocus: Boolean = false,
) {
  val run = { q: String -> if (MusicV3Rules.canSubmit(q, state.remaining, state.recent)) onSubmit(q) }
  Column(Modifier.fillMaxSize()) {
    SearchField(state, onQuery, onSubmit, onFocus, autoFocus)
    state.hint?.let { Hint(it) }
    if (state.resting) RestingNotice()
    Box(Modifier.weight(1f).padding(top = 12.dp)) {
      when {
        state.fieldEmpty -> MusicV3EmptyField(state.recent, run, actions)
        state.showsSuggestions && suggestions.isNotEmpty() -> MusicV3SuggestionList(suggestions, run, actions)
        else -> SearchBody(state, run, actions)
      }
    }
  }
}

@Composable
private fun SearchBody(state: MusicV3SearchState, onSubmit: (String) -> Unit, actions: MusicV3Actions) {
  when (val outcome = state.outcome) {
    MusicV3SearchOutcome.Offline -> MusicV3Empty("wifi", MusicV3Message("This Portal is offline",
        "Search and playback come back as soon as it reconnects."))
    MusicV3SearchOutcome.Failed -> MusicV3Empty("info", MusicV3Message("YouTube didn't answer",
        "Try again in a minute."))
    is MusicV3SearchOutcome.NoResults -> MusicV3Empty("search", MusicV3Message("No songs found for “${outcome.query}”",
        "Try fewer words, or just the artist's name."))
    is MusicV3SearchOutcome.Searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator(color = ApprovedHomePalette.Accent, modifier = Modifier.size(56.dp))
    }
    is MusicV3SearchOutcome.Results -> Column {
      if (state.resting) MusicV3RecentChips(state.recent, onSubmit)
      MusicV3SectionLabel(state.resultsLabel(outcome.query))
      TrackRows(outcome.tracks, actions, "Search · ${outcome.query}") { actions.playSearchResult(it, outcome.query) }
    }
    MusicV3SearchOutcome.Idle -> MusicV3RecentChips(state.recent, onSubmit)
  }
}

/**
 * The search field (the mock's `.sf`): ember ring while focused. Focus is reported through
 * [onFocus] so the stage can shrink the strip above it; [autoFocus] focuses it on arrival.
 */
@Composable
private fun SearchField(
    state: MusicV3SearchState,
    onQuery: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    autoFocus: Boolean,
) {
  val ink = LocalMusicStageInk.current
  val dims = LocalMusicStageDims.current
  val shape = RoundedCornerShape(28.dp)
  val enabled = !state.resting
  val submit = { if (MusicV3Rules.canSubmit(state.query, state.remaining, state.recent)) onSubmit(state.query) }
  val focus = remember { FocusRequester() }
  var focused by remember { mutableStateOf(false) }
  LaunchedEffect(autoFocus, enabled) { if (autoFocus && enabled) runCatching { focus.requestFocus() } }
  DisposableEffect(Unit) { onDispose { onFocus(false) } }
  val textSize = (dims.body * 1.2f).sp
  Row(Modifier.fillMaxWidth().padding(top = 10.dp).height(dims.searchField.dp).clip(shape).background(ink.fill2)
      .border(if (focused) 2.dp else 1.dp, if (focused) HearthpaneMark.Ember else ink.line, shape)
      .alpha(if (enabled) 1f else 0.55f).padding(start = 30.dp, end = 12.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    ApprovedIcon("search", Modifier.size(40.dp), ink.muted)
    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
      if (state.query.isEmpty()) Text("Search for a song or artist", color = ink.faint, fontSize = textSize)
      BasicTextField(state.query, onQuery, enabled = enabled, singleLine = true,
          textStyle = TextStyle(color = ink.ink, fontSize = textSize, fontWeight = FontWeight.Medium),
          cursorBrush = SolidColor(HearthpaneMark.Ember),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { submit() }),
          modifier = Modifier.fillMaxWidth().focusRequester(focus)
              .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; onFocus(it.isFocused) } }
              .semantics { contentDescription = "Search" }.onPreviewKeyEvent {
            val enter = it.key == Key.Enter || it.key == Key.NumPadEnter
            if (enter && it.type == KeyEventType.KeyUp) submit()
            enter
          })
    }
    if (state.query.isNotEmpty() && enabled) {
      MusicV3RoundButton("Clear search", size = dims.target.dp, onClick = { onQuery("") }) {
        ApprovedIcon("close", Modifier.size(34.dp), ink.muted)
      }
    }
  }
}

@Composable
private fun Hint(text: String) {
  Row(Modifier.padding(start = 6.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(ApprovedHomePalette.Accent))
    Text(text, color = ApprovedHomePalette.Secondary, fontSize = 22.sp)
  }
}

@Composable
private fun RestingNotice() {
  Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp).clip(RoundedCornerShape(24.dp))
      .background(ApprovedHomePalette.Chrome).padding(horizontal = 30.dp, vertical = 26.dp),
      horizontalArrangement = Arrangement.spacedBy(22.dp)) {
    ApprovedIcon("sleep", Modifier.size(40.dp), ApprovedHomePalette.Secondary)
    Column {
      Text("Search is resting until tomorrow", color = ApprovedHomePalette.Text, fontSize = 28.sp,
          lineHeight = 36.sp, fontWeight = FontWeight.Medium)
      Text("This Portal has used today's searches. Your library and the searches below still work.",
          color = ApprovedHomePalette.Secondary, fontSize = 22.sp, lineHeight = 31.sp, modifier = Modifier.padding(top = 4.dp))
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MusicV3RecentChips(recent: List<String>, onRun: (String) -> Unit) {
  if (recent.isEmpty()) return
  Column {
    MusicV3SectionLabel("Recent searches", Modifier.padding(top = 8.dp))
    FlowRow(Modifier.padding(top = 8.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
      recent.forEach { q ->
        val shape = RoundedCornerShape(32.dp)
        Row(Modifier.heightIn(min = MusicV3Dims.Touch).clip(shape).background(ApprovedHomePalette.Chrome)
            .tvFocusable(shape, ringColor = ApprovedHomePalette.Accent) { onRun(q) }
            .semantics { contentDescription = "Search again for $q" }.padding(start = 18.dp, end = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          ApprovedIcon("clock", Modifier.size(24.dp), ApprovedHomePalette.Secondary)
          Text(q, color = ApprovedHomePalette.Text, fontSize = 23.sp)
        }
      }
    }
  }
}
