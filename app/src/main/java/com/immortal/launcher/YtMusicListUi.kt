/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

internal object ListStyle {
  val page = Color(0xFF0F0F0F)
  val rowHi = Color(0xFF272727)
  val field = Color(0xFF272727)
  val ink = Color(0xFFF3EEE4)
  val muted = Color(0xFF9A9A9A)
  val accent = Color(0xFFD9B26A)
  val artBg = Color(0xFF1E1C19)
  val rowHeight = 88.dp
  val artSize = 60.dp
  const val UNPLAYABLE_ALPHA = 0.5f
  const val ROW_ART_EDGE = 160
}

/**
 * The opaque native surface that covers the (still attached, still laid out) YouTube Music page.
 * It is a pointer hit target so taps never reach the hidden page, but it consumes nothing, so the
 * music screen's tap-to-close-keyboard still works.
 */
@Composable
internal fun YtMusicNativeLists(
    pane: YtMusicPaneState,
    view: YtMusicListView,
    pageReady: Boolean,
    onSearch: (String) -> Unit,
    onPlay: (YtMusicList, YtMusicListItem) -> Unit,
    onShowPage: () -> Unit,
) {
  Column(
      Modifier
          .fillMaxSize()
          .background(ListStyle.page)
          .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
          .padding(horizontal = 16.dp, vertical = 18.dp)) {
    if (pane.tab == YtMusicTab.SEARCH) {
      SearchField(pane.submittedQuery.orEmpty(), onSearch)
      Spacer(Modifier.height(14.dp))
    }
    when (view) {
      YtMusicListView.Loading -> CenteredStatus(if (pageReady) "Loading…" else "Opening YouTube Music…", spinner = true)
      YtMusicListView.SearchPrompt -> CenteredStatus("Search songs, albums and artists")
      YtMusicListView.SignedOut -> CenteredStatus("Sign in to see your library", action = "Show YouTube page", onAction = onShowPage)
      is YtMusicListView.Empty -> CenteredStatus(view.message)
      is YtMusicListView.Items -> ItemList(view, onPlay)
    }
  }
}

@Composable
private fun SearchField(initial: String, onSearch: (String) -> Unit) {
  var text by remember(initial) { mutableStateOf(initial) }
  val keyboard = LocalSoftwareKeyboardController.current
  val focus = LocalFocusManager.current
  val submit = {
    // Close the keyboard on submit; the window keeps ADJUST_NOTHING (set by the music screen).
    keyboard?.hide()
    focus.clearFocus()
    if (YtMusicSearch.normalize(text) != null) onSearch(text)
  }
  BasicTextField(
      value = text,
      onValueChange = { text = it.take(YtMusicSearch.MAX_QUERY_CHARS) },
      singleLine = true,
      textStyle = TextStyle(color = ListStyle.ink, fontSize = 22.sp),
      cursorBrush = SolidColor(ListStyle.accent),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }),
      modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search YouTube Music" },
      decorationBox = { inner ->
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp))
                .background(ListStyle.field).padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically) {
          SearchGlyph(Modifier.size(24.dp))
          Spacer(Modifier.width(16.dp))
          Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
              Text("Search songs, albums, artists", color = ListStyle.muted, fontSize = 22.sp, maxLines = 1)
            }
            inner()
          }
        }
      })
}

@Composable
private fun ItemList(view: YtMusicListView.Items, onPlay: (YtMusicList, YtMusicListItem) -> Unit) {
  val keyboard = LocalSoftwareKeyboardController.current
  val focus = LocalFocusManager.current
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp)) {
    view.header?.let { header ->
      item(key = "header") {
        Text(header, color = ListStyle.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 12.dp))
      }
    }
    items(view.list.items, key = { it.index }) { item ->
      ListRow(item) {
        keyboard?.hide()
        focus.clearFocus()
        onPlay(view.list, item)
      }
    }
  }
}

@Composable
private fun ListRow(item: YtMusicListItem, onClick: () -> Unit) {
  val label = buildString {
    append(if (item.playable) "Play " else "")
    append(item.title)
    if (item.subtitle.isNotEmpty()) append(", ").append(item.subtitle)
    if (item.playing) append(", now playing")
  }
  Row(
      Modifier
          .fillMaxWidth()
          .height(ListStyle.rowHeight)
          .alpha(if (item.playable) 1f else ListStyle.UNPLAYABLE_ALPHA)
          .clip(RoundedCornerShape(14.dp))
          .background(if (item.playing) ListStyle.rowHi else Color.Transparent)
          .clickable(enabled = item.playable, role = Role.Button, onClick = onClick)
          .semantics(mergeDescendants = true) { contentDescription = label }
          .padding(horizontal = 14.dp),
      verticalAlignment = Alignment.CenterVertically) {
    RowArt(item.artUrl)
    Spacer(Modifier.width(18.dp))
    Column(Modifier.weight(1f)) {
      Text(item.title, color = ListStyle.ink, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (item.subtitle.isNotEmpty()) {
        Text(item.subtitle, color = ListStyle.muted, fontSize = 19.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
      }
    }
    if (item.playing) {
      Spacer(Modifier.width(14.dp))
      EqualizerGlyph(Modifier.size(24.dp))
    }
    if (item.duration.isNotEmpty()) {
      Spacer(Modifier.width(16.dp))
      Text(item.duration, color = ListStyle.muted, fontSize = 19.sp, maxLines = 1)
    }
  }
}

@Composable
private fun RowArt(url: String) {
  var art by remember(url) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(url) {
    if (url.isNotBlank()) {
      art = runInterruptible(Dispatchers.IO) { MultiRoomArt.fetch(url, ListStyle.ROW_ART_EDGE) }
    }
  }
  Box(Modifier.size(ListStyle.artSize).clip(RoundedCornerShape(8.dp)).background(ListStyle.artBg)) {
    art?.takeIf { !it.isRecycled }?.let {
      Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize())
    }
  }
}

@Composable
private fun CenteredStatus(text: String, spinner: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
  Column(Modifier.fillMaxSize().padding(horizontal = 32.dp),
      verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    if (spinner) {
      CircularProgressIndicator(color = ListStyle.ink, modifier = Modifier.size(40.dp))
      Spacer(Modifier.height(18.dp))
    }
    Text(text, color = ListStyle.muted, fontSize = 22.sp, lineHeight = 30.sp,
        modifier = Modifier.widthIn(max = 560.dp))
    if (action != null) {
      Spacer(Modifier.height(24.dp))
      PageLink(action, onAction)
    }
  }
}

/** Small pill that reveals (or re-hides) the real YouTube Music page. */
@Composable
internal fun PageLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  Box(
      modifier
          .clip(RoundedCornerShape(50))
          .background(ListStyle.field)
          .clickable(role = Role.Button, onClick = onClick)
          .semantics { contentDescription = label }
          .padding(horizontal = 20.dp, vertical = 12.dp)) {
    Text(label, color = ListStyle.ink, fontSize = 18.sp, maxLines = 1)
  }
}

/** Three static bars in the accent colour: the "this row is playing" mark. */
@Composable
private fun EqualizerGlyph(modifier: Modifier) {
  Canvas(modifier) {
    val bar = size.width / 5f
    val heights = floatArrayOf(0.55f, 1f, 0.75f)
    heights.forEachIndexed { n, h ->
      val barHeight = size.height * h
      drawRoundRect(ListStyle.accent, topLeft = Offset(bar * (n * 2), size.height - barHeight),
          size = Size(bar, barHeight), cornerRadius = CornerRadius(bar / 2f))
    }
  }
}

@Composable
private fun SearchGlyph(modifier: Modifier) {
  Canvas(modifier) {
    val u = size.minDimension / 24f
    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * u)
    drawCircle(ListStyle.muted, radius = 7f * u, center = Offset(10.5f * u, 10.5f * u), style = stroke)
    drawLine(ListStyle.muted, Offset(15.8f * u, 15.8f * u), Offset(21f * u, 21f * u), strokeWidth = 2.2f * u)
  }
}
