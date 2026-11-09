/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.youtube.YtTrack as YtDataTrack

private val SUGGESTION_ROW_MIN = 80.dp

/**
 * Local suggestions under the field while typing (no quota). A song plays now; a search or an
 * artist runs the real YouTube search ([onSearch]).
 */
@Composable
internal fun MusicV3SuggestionList(
    suggestions: List<MusicSuggestion>,
    onSearch: (String) -> Unit,
    actions: MusicV3Actions,
) {
  if (suggestions.isEmpty()) return
  LazyColumn(Modifier.fillMaxSize()) {
    items(suggestions, key = { "${it.kind}:${it.videoId ?: it.text}" }) { s -> SuggestionRow(s, onSearch, actions) }
    item { Spacer(Modifier.height(40.dp)) }
  }
}

@Composable
private fun SuggestionRow(s: MusicSuggestion, onSearch: (String) -> Unit, actions: MusicV3Actions) {
  val song = s.kind == MusicSuggestionKind.SONG && s.videoId != null
  val tap = {
    if (song) actions.playOne(YtDataTrack(s.videoId!!, s.text, s.artist.orEmpty(), s.thumb), "Search · ${s.text}")
    else onSearch(s.text)
  }
  val label = if (song) "Play ${s.text}" else "Search for ${s.text}"
  Row(Modifier.fillMaxWidth().heightIn(min = SUGGESTION_ROW_MIN).clip(RoundedCornerShape(20.dp))
      .tvFocusableRow(focusFill = ApprovedHomePalette.Selected, onClick = tap)
      .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = label }.padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    when (s.kind) {
      MusicSuggestionKind.SONG -> MusicV3Thumb(s.thumb, Modifier.size(56.dp))
      MusicSuggestionKind.ARTIST -> SuggestionIcon("music")
      MusicSuggestionKind.SEARCH -> SuggestionIcon("clock")
    }
    Column(Modifier.weight(1f)) {
      Text(s.text, color = ApprovedHomePalette.Text, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1,
          overflow = TextOverflow.Ellipsis)
      val sub = when (s.kind) {
        MusicSuggestionKind.SONG -> s.artist
        MusicSuggestionKind.ARTIST -> "Artist"
        MusicSuggestionKind.SEARCH -> null
      }
      sub?.let { Text(it, color = ApprovedHomePalette.Secondary, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    if (!song) ApprovedIcon("search", Modifier.size(28.dp), ApprovedHomePalette.Secondary)
  }
}

@Composable
private fun SuggestionIcon(name: String) {
  Box(Modifier.size(56.dp).clip(CircleShape).background(ApprovedHomePalette.Chrome), contentAlignment = Alignment.Center) {
    ApprovedIcon(name, Modifier.size(28.dp), ApprovedHomePalette.Secondary)
  }
}

/** Empty field: Recent searches (chips) and the household's last few plays. */
@Composable
internal fun MusicV3EmptyField(recent: List<String>, onSearch: (String) -> Unit, actions: MusicV3Actions) {
  val plays by MusicHistoryFeature.plays.collectAsState()
  val top = remember(plays) { MusicHistory.listing(plays).take(MusicHistory.EMPTY_FIELD_ROWS) }
  val now = rememberAgoClock()
  LazyColumn(Modifier.fillMaxSize()) {
    item { MusicV3RecentChips(recent, onSearch) }
    if (top.isNotEmpty()) {
      item { MusicV3SectionLabel("Recently played") }
      items(top, key = { "played:${it.videoId}" }) { play -> MusicV3RecentRow(play, now, actions, withRemove = false) }
    }
    item { Spacer(Modifier.height(40.dp)) }
  }
}
