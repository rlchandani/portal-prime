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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MusicBrowseCollectionKind(val label: String) { ALBUM("Album"), PLAYLIST("Playlist") }
enum class MusicBrowseStatus { READY, LOADING, UNAVAILABLE }

/** Host-supplied metadata. A missing duration or cover stays missing in the presentation. */
@Immutable
data class MusicBrowseTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long? = null,
    val artworkUri: String = "",
    val available: Boolean = true,
)

/**
 * Replace snapshots instead of mutating [tracks]. IDs must be unique within each page/list.
 * Tracks describe this collection, not a playback queue; the host owns every playback command.
 */
data class MusicBrowseCollection(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val artworkUri: String = "",
    val tracks: List<MusicBrowseTrack> = emptyList(),
    val kind: MusicBrowseCollectionKind = MusicBrowseCollectionKind.ALBUM,
    val available: Boolean = true,
)

/** Filters supplied metadata only; a result retains the complete original collection. */
internal fun musicBrowseSearchCollections(
    collections: List<MusicBrowseCollection>,
    query: String,
): List<MusicBrowseCollection> {
  val term = query.trim()
  if (term.isEmpty()) return collections
  return collections.filter { collection ->
    collection.title.contains(term, ignoreCase = true) ||
        collection.subtitle.contains(term, ignoreCase = true) ||
        collection.tracks.any { track ->
          track.title.contains(term, ignoreCase = true) ||
              track.artist.contains(term, ignoreCase = true)
        }
  }
}

/**
 * Hoist above conditional detail navigation to retain the query and grid position on Back.
 * Keep one state per library/playlist page; this holds only UI drafts, never provider data.
 */
@Stable
class MusicBrowseState internal constructor(
    initialQuery: String = "",
    initialIndex: Int = 0,
    initialOffset: Int = 0,
) {
  var query by mutableStateOf(initialQuery)
  internal val gridState = LazyGridState(initialIndex, initialOffset)
}

private val MusicBrowseStateSaver = listSaver<MusicBrowseState, Any>(
    save = { listOf(it.query, it.gridState.firstVisibleItemIndex,
        it.gridState.firstVisibleItemScrollOffset) },
    restore = { MusicBrowseState(it[0] as String, it[1] as Int, it[2] as Int) },
)

/** Call in the enclosing player, before its grid/detail branch, to preserve detail -> Back. */
@Composable
fun rememberMusicBrowseState(): MusicBrowseState =
    rememberSaveable(saver = MusicBrowseStateSaver) { MusicBrowseState() }

/**
 * Album-style collection grid. Supply real collections (or an empty list), never placeholders.
 * Navigation belongs to the enclosing player; this component creates no controllers or routes.
 */
@Composable
fun MusicLibraryPage(
    collections: List<MusicBrowseCollection>,
    onBack: () -> Unit,
    onCollection: (MusicBrowseCollection) -> Unit,
    onShowPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    status: MusicBrowseStatus = MusicBrowseStatus.READY,
    statusMessage: String? = null,
    browseState: MusicBrowseState = rememberMusicBrowseState(),
) {
  MusicBrowseCollections("Library", collections, false, browseState, modifier, status, statusMessage,
      onBack, onCollection, onShowPlayer)
}

/** Wider playlist cards keep supplied playlist names and descriptions readable at a distance. */
@Composable
fun MusicPlaylistsPage(
    playlists: List<MusicBrowseCollection>,
    onBack: () -> Unit,
    onCollection: (MusicBrowseCollection) -> Unit,
    onShowPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    status: MusicBrowseStatus = MusicBrowseStatus.READY,
    statusMessage: String? = null,
    browseState: MusicBrowseState = rememberMusicBrowseState(),
) {
  MusicBrowseCollections("Playlists", playlists, true, browseState, modifier, status, statusMessage,
      onBack, onCollection, onShowPlayer)
}

/**
 * One bounded lazy scroller includes the hero and tracks, including on short/large-font screens.
 * [onPlayCollection] is optional: the Play action exists only when the host implements it.
 * [currentTrackId] highlights selection without claiming that the track is currently playing.
 */
@Composable
fun MusicCollectionDetail(
    collection: MusicBrowseCollection,
    onBack: () -> Unit,
    onTrack: (MusicBrowseTrack) -> Unit,
    onShowPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    status: MusicBrowseStatus = MusicBrowseStatus.READY,
    statusMessage: String? = null,
    currentTrackId: String? = null,
    onPlayCollection: ((MusicBrowseCollection) -> Unit)? = null,
) {
  val palette = LocalApprovedPalette.current
  val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
  BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
    val padding = if (maxWidth < 600.dp) 16.dp else 28.dp
    val horizontalHero = maxWidth >= (720 * fontScale).dp
    val compactRows = maxWidth < (560 * fontScale).dp
    Column(Modifier.fillMaxSize().padding(horizontal = padding)) {
      MusicBrowseNavigation(onBack, onShowPlayer)
      key(collection.id) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          item(key = "hero", contentType = "hero") {
            MusicBrowseHero(collection, horizontalHero,
                onPlayCollection.takeIf { status == MusicBrowseStatus.READY })
          }
          item(key = "track-heading", contentType = "heading") {
            Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
              MusicBrowseText("Tracks", Modifier.weight(1f).semantics { heading() },
                  size = 24, bold = true)
              if (status == MusicBrowseStatus.READY && collection.tracks.isNotEmpty()) {
                MusicBrowseText(musicBrowseTrackCount(collection.tracks.size),
                    muted = true, size = 16)
              }
            }
          }
          if (status != MusicBrowseStatus.READY || collection.tracks.isEmpty()) {
            item(key = "message", contentType = "message") {
              MusicBrowseMessage(status,
                  emptyTitle = "No tracks in this ${collection.kind.label.lowercase()}",
                  emptyDetail = "Tracks appear here when they are supplied.",
                  unavailableTitle = "Tracks unavailable",
                  statusMessage = statusMessage)
            }
          } else {
            itemsIndexed(collection.tracks,
                key = { index, track -> track.id.ifBlank { "missing-track:$index" } },
                contentType = { _, _ -> "track" }) { index, track ->
              MusicBrowseTrackRow(track, index + 1, compactRows,
                  current = track.id.isNotBlank() && track.id == currentTrackId,
                  enabled = collection.available && collection.id.isNotBlank() &&
                      track.available && track.id.isNotBlank(),
                  onClick = { onTrack(track) })
            }
          }
        }
      }
    }
  }
}

@Composable
private fun MusicBrowseCollections(
    title: String,
    collections: List<MusicBrowseCollection>,
    playlists: Boolean,
    browseState: MusicBrowseState,
    modifier: Modifier,
    status: MusicBrowseStatus,
    statusMessage: String?,
    onBack: () -> Unit,
    onCollection: (MusicBrowseCollection) -> Unit,
    onShowPlayer: () -> Unit,
) {
  val palette = LocalApprovedPalette.current
  val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
  val query = browseState.query
  val term = query.trim()
  val gridState = browseState.gridState
  val matching = remember(collections, term, status) {
    if (status == MusicBrowseStatus.READY) musicBrowseSearchCollections(collections, term)
    else emptyList()
  }
  val focusManager = LocalFocusManager.current
  val openCollection: (MusicBrowseCollection) -> Unit = {
    focusManager.clearFocus()
    onCollection(it)
  }
  BoxWithConstraints(modifier.fillMaxSize().background(palette.background)) {
    val padding = if (maxWidth < 600.dp) 16.dp else 28.dp
    // Fewer columns at larger text scales; card heights remain content-driven.
    val minimumCell = ((if (playlists) 320 else 208) * fontScale).dp
    Column(Modifier.fillMaxSize().padding(horizontal = padding)) {
      MusicBrowseNavigation(onBack, onShowPlayer)
      key(title) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minimumCell),
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
          item(key = "heading", span = { GridItemSpan(maxLineSpan) },
              contentType = "heading") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              MusicBrowseText(title, Modifier.semantics { heading() },
                  size = 36, bold = true, maxLines = 2)
              if (status != MusicBrowseStatus.READY) {
                MusicBrowseMessage(status, emptyTitle = "", emptyDetail = "",
                    unavailableTitle = if (playlists) "Playlists unavailable" else "Library unavailable",
                    statusMessage = statusMessage, compact = true)
              }
              MusicBrowseSearch(query, label = "Search ${title.lowercase()}",
                  onQuery = {
                    browseState.query = it
                    gridState.requestScrollToItem(0)
                  })
              if (status == MusicBrowseStatus.READY) {
                MusicBrowseText(
                    if (term.isNotEmpty()) {
                      "${matching.size} of " + if (playlists)
                        musicBrowseCount(collections.size, "playlist", "playlists")
                      else musicBrowseCount(collections.size, "collection", "collections")
                    } else if (playlists) musicBrowseCount(collections.size, "playlist", "playlists")
                    else musicBrowseCount(collections.size, "collection", "collections"),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    size = 17, muted = true)
              }
            }
          }
          if (status != MusicBrowseStatus.READY || collections.isEmpty()) {
            if (status == MusicBrowseStatus.READY) {
              item(key = "message", span = { GridItemSpan(maxLineSpan) },
                  contentType = "message") {
                MusicBrowseMessage(status,
                    emptyTitle = if (playlists) "No playlists yet" else "Your library is empty",
                    emptyDetail = if (playlists) "Playlists appear here when they are supplied."
                        else "Albums and collections appear here when they are supplied.",
                    unavailableTitle = if (playlists) "Playlists unavailable" else "Library unavailable",
                    statusMessage = statusMessage)
              }
            }
          } else if (matching.isEmpty()) {
            item(key = "no-results", span = { GridItemSpan(maxLineSpan) },
                contentType = "message") {
              MusicBrowseMessage(MusicBrowseStatus.READY,
                  emptyTitle = "No matching ${if (playlists) "playlists" else "collections"}",
                  emptyDetail = "Try a collection name, track title or artist, or clear your search.",
                  unavailableTitle = "", statusMessage = null)
            }
          } else {
            gridItemsIndexed(matching,
                key = { index, collection ->
                  collection.id.ifBlank { "missing-collection:$index" }
                }, contentType = { _, _ -> if (playlists) "playlist" else "album" }) {
                _, collection ->
              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playlists) MusicBrowsePlaylistCard(collection) { openCollection(collection) }
                else MusicBrowseAlbumCard(collection) { openCollection(collection) }
                MusicBrowseSearchHint(collection, term)
              }
            }
          }
        }
      }
    }
  }
}

/** Search remains inside the bounded grid, so the keyboard and large text can scroll it. */
@Composable
private fun MusicBrowseSearch(query: String, label: String, onQuery: (String) -> Unit) {
  val palette = LocalApprovedPalette.current
  val focusManager = LocalFocusManager.current
  var focused by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(24.dp)
  BasicTextField(query, onValueChange = onQuery, singleLine = true,
      textStyle = TextStyle(color = palette.text, fontFamily = FontFamily.Default,
          fontSize = 20.sp, lineHeight = 26.sp),
      cursorBrush = SolidColor(palette.accent),
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
      keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
      modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
          .onFocusChanged { focused = it.isFocused }
          .semantics { contentDescription = label }
          .clip(shape).background(palette.surface)
          .border(if (focused) 2.dp else 1.dp,
              if (focused) palette.accent else palette.divider, shape),
      decorationBox = { inner ->
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          MusicBrowseIcon(MusicBrowseGlyph.SEARCH, palette.secondary, Modifier.size(24.dp))
          Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (query.isEmpty()) MusicBrowseText(label, size = 20, muted = true)
            inner()
          }
          if (query.isNotEmpty()) {
            MusicBrowseButton("Clear search", glyph = MusicBrowseGlyph.CLEAR,
                iconOnly = true, onClick = { onQuery("") })
          }
        }
      })
}

/** Explain nested track matches without inventing a playback action or a partial collection. */
@Composable
private fun MusicBrowseSearchHint(collection: MusicBrowseCollection, term: String) {
  if (term.isEmpty() || collection.title.contains(term, ignoreCase = true) ||
      collection.subtitle.contains(term, ignoreCase = true)) return
  val track = collection.tracks.firstOrNull {
    it.title.contains(term, ignoreCase = true) || it.artist.contains(term, ignoreCase = true)
  } ?: return
  val caption = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" · ")
  MusicBrowseText("Includes $caption", Modifier.padding(horizontal = 4.dp),
      size = 16, muted = true, maxLines = 2)
}

@Composable
private fun MusicBrowseNavigation(onBack: () -> Unit, onShowPlayer: () -> Unit) {
  val fontScale = LocalDensity.current.fontScale
  BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
    val narrow = maxWidth < (400 * fontScale).dp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      MusicBrowseButton("Back", glyph = MusicBrowseGlyph.BACK,
          iconOnly = narrow, onClick = onBack)
      Spacer(Modifier.weight(1f))
      MusicBrowseButton("Now playing", glyph = MusicBrowseGlyph.PLAYER,
          iconOnly = narrow, onClick = onShowPlayer)
    }
  }
}

@Composable
private fun MusicBrowseAlbumCard(collection: MusicBrowseCollection, onClick: () -> Unit) {
  val enabled = collection.available && collection.id.isNotBlank()
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
    MusicBrowseArtwork(collection.id, collection.artworkUri,
        Modifier.fillMaxWidth().aspectRatio(1f), maxEdge = 256)
    Column(Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
      MusicBrowseText(collection.title.ifBlank { "Untitled collection" },
          size = 22, bold = true, maxLines = 2)
      if (collection.subtitle.isNotBlank()) {
        MusicBrowseText(collection.subtitle, size = 17, muted = true, maxLines = 2)
      }
      MusicBrowseText(if (enabled) collection.kind.label else "Unavailable",
          size = 15, muted = true)
    }
  }
}

@Composable
private fun MusicBrowsePlaylistCard(collection: MusicBrowseCollection, onClick: () -> Unit) {
  val palette = LocalApprovedPalette.current
  val enabled = collection.available && collection.id.isNotBlank()
  val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
  BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
      .background(palette.surface)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(16.dp)) {
    val stacked = maxWidth < (260 * fontScale).dp
    if (stacked) {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MusicBrowseArtwork(collection.id, collection.artworkUri,
            Modifier.fillMaxWidth().aspectRatio(1f), maxEdge = 256)
        MusicBrowsePlaylistCaption(collection, enabled)
      }
    } else {
      Row(verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        MusicBrowseArtwork(collection.id, collection.artworkUri,
            Modifier.size(104.dp), maxEdge = 256)
        MusicBrowsePlaylistCaption(collection, enabled, Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun MusicBrowsePlaylistCaption(
    collection: MusicBrowseCollection,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    MusicBrowseText(collection.title.ifBlank { "Untitled playlist" },
        size = 22, bold = true, maxLines = 2)
    if (collection.subtitle.isNotBlank()) {
      MusicBrowseText(collection.subtitle, size = 17, muted = true, maxLines = 2)
    }
    if (!enabled) MusicBrowseText("Unavailable", size = 15, muted = true)
    else if (collection.tracks.isNotEmpty()) {
      MusicBrowseText(musicBrowseTrackCount(collection.tracks.size), size = 15, muted = true)
    }
  }
}

@Composable
private fun MusicBrowseHero(
    collection: MusicBrowseCollection,
    horizontal: Boolean,
    onPlayCollection: ((MusicBrowseCollection) -> Unit)?,
) {
  if (horizontal) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically) {
      MusicBrowseArtwork(collection.id, collection.artworkUri,
          Modifier.size(256.dp), maxEdge = 256)
      MusicBrowseHeroCaption(collection, onPlayCollection, Modifier.weight(1f))
    }
  } else {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      MusicBrowseArtwork(collection.id, collection.artworkUri,
          Modifier.widthIn(max = 256.dp).fillMaxWidth().aspectRatio(1f), maxEdge = 256)
      MusicBrowseHeroCaption(collection, onPlayCollection)
    }
  }
}

@Composable
private fun MusicBrowseHeroCaption(
    collection: MusicBrowseCollection,
    onPlayCollection: ((MusicBrowseCollection) -> Unit)?,
    modifier: Modifier = Modifier,
) {
  val duration = musicBrowseCollectionDuration(collection.tracks)
  val canPlay = collection.available && collection.id.isNotBlank() &&
      collection.tracks.any { it.available && it.id.isNotBlank() }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    MusicBrowseText(collection.kind.label, size = 17, muted = true)
    MusicBrowseText(collection.title.ifBlank { "Untitled collection" },
        Modifier.semantics { heading() }, size = 36, bold = true, maxLines = 4)
    if (collection.subtitle.isNotBlank()) {
      MusicBrowseText(collection.subtitle, size = 21, muted = true, maxLines = 4)
    }
    if (!collection.available || collection.id.isBlank()) {
      MusicBrowseText("This collection is unavailable", size = 17, muted = true, maxLines = 2)
    } else if (collection.tracks.isNotEmpty()) {
      MusicBrowseText(listOfNotNull(musicBrowseTrackCount(collection.tracks.size), duration)
          .joinToString(" · "), size = 17, muted = true, maxLines = 2)
    }
    if (onPlayCollection != null && canPlay) {
      MusicBrowseButton("Play", glyph = MusicBrowseGlyph.PLAY, emphasized = true) {
        onPlayCollection(collection)
      }
    }
  }
}

@Composable
private fun MusicBrowseTrackRow(
    track: MusicBrowseTrack,
    number: Int,
    compact: Boolean,
    current: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
  val palette = LocalApprovedPalette.current
  Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
      .background(if (current) palette.selected else Color.Transparent)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .semantics { selected = current }
      .heightIn(min = 88.dp).padding(horizontal = 12.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 20.dp)) {
    if (!compact) {
      MusicBrowseText(number.toString(), Modifier.width(44.dp), size = 16,
          muted = true, textAlign = TextAlign.Center)
    }
    MusicBrowseArtwork(track.id, track.artworkUri, Modifier.size(56.dp), maxEdge = 128)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      MusicBrowseText(track.title.ifBlank { "Untitled track" },
          size = 21, bold = true, maxLines = 2)
      val subtitle = if (enabled) track.artist
          else listOf(track.artist, "Unavailable").filter { it.isNotBlank() }.joinToString(" · ")
      if (subtitle.isNotBlank()) MusicBrowseText(subtitle, size = 17, muted = true, maxLines = 2)
      if (compact) track.durationMs?.takeIf { it > 0 }?.let {
        MusicBrowseText(musicBrowseDuration(it), size = 15, muted = true)
      }
    }
    if (!compact) track.durationMs?.takeIf { it > 0 }?.let {
      MusicBrowseText(musicBrowseDuration(it), size = 16, muted = true)
    }
  }
}

@Composable
private fun MusicBrowseMessage(
    status: MusicBrowseStatus,
    emptyTitle: String,
    emptyDetail: String,
    unavailableTitle: String,
    statusMessage: String?,
    compact: Boolean = false,
) {
  val palette = LocalApprovedPalette.current
  Column(Modifier.fillMaxWidth().heightIn(min = if (compact) 0.dp else 260.dp)
      .clip(RoundedCornerShape(20.dp)).background(palette.surface)
      .padding(if (compact) 20.dp else 28.dp),
      verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
    if (status == MusicBrowseStatus.LOADING) {
      CircularProgressIndicator(Modifier.size(32.dp), color = palette.text, strokeWidth = 3.dp)
    } else {
      MusicBrowseIcon(MusicBrowseGlyph.MUSIC, palette.secondary, Modifier.size(40.dp))
    }
    MusicBrowseText(when (status) {
      MusicBrowseStatus.READY -> emptyTitle
      MusicBrowseStatus.LOADING -> "Loading music"
      MusicBrowseStatus.UNAVAILABLE -> unavailableTitle
    }, size = 26, bold = true, maxLines = 3)
    MusicBrowseText(statusMessage?.takeIf { it.isNotBlank() } ?: when (status) {
      MusicBrowseStatus.READY -> emptyDetail
      MusicBrowseStatus.LOADING -> "Waiting for the music collection."
      MusicBrowseStatus.UNAVAILABLE -> "Music cannot be shown right now."
    }, size = 18, muted = true, maxLines = 6)
  }
}

/**
 * Decode only visible cells, off-main, with the existing byte/pixel admission rules.
 * Only local URI schemes are admitted: this presentation never requests network artwork.
 * Re-keying discards the previous image immediately when a cell's identity or URI changes.
 */
@Composable
private fun MusicBrowseArtwork(
    id: String,
    uri: String,
    modifier: Modifier,
    maxEdge: Int,
) {
  val resolver = LocalContext.current.contentResolver
  key(id, uri, maxEdge, resolver) {
    val bitmap by produceState<ImageBitmap?>(null) {
      if (MusicLocalArtwork.routeFor(uri) == MusicArtworkRoute.LOCAL) {
        value = withContext(Dispatchers.IO) {
          MusicLocalArtwork.fetch(resolver, uri, maxEdge)?.asImageBitmap()
        }
      }
    }
    val palette = LocalApprovedPalette.current
    Box(modifier.clip(RoundedCornerShape(if (maxEdge <= 128) 12.dp else 18.dp))
        .background(palette.surface2)
        .border(1.dp, palette.divider, RoundedCornerShape(if (maxEdge <= 128) 12.dp else 18.dp)),
        contentAlignment = Alignment.Center) {
      val cover = bitmap
      if (cover != null) {
        Image(cover, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
      } else {
        MusicBrowseIcon(MusicBrowseGlyph.MUSIC, palette.secondary,
            Modifier.fillMaxSize(0.3f))
      }
    }
  }
}

@Composable
private fun MusicBrowseText(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 18,
    bold: Boolean = false,
    muted: Boolean = false,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Start,
) {
  val palette = LocalApprovedPalette.current
  Text(text, modifier, color = if (muted) palette.secondary else palette.text,
      fontFamily = FontFamily.Default, fontSize = size.sp, lineHeight = (size * 1.3f).sp,
      fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
      maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
}

private enum class MusicBrowseGlyph { BACK, PLAYER, PLAY, MUSIC, SEARCH, CLEAR }

@Composable
private fun MusicBrowseButton(
    label: String,
    modifier: Modifier = Modifier,
    glyph: MusicBrowseGlyph,
    iconOnly: Boolean = false,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
  val palette = LocalApprovedPalette.current
  val foreground = if (emphasized) palette.background else palette.text
  Row(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
      .clip(RoundedCornerShape(20.dp))
      .background(if (emphasized) palette.text else palette.surface2)
      .clickable(role = Role.Button, onClick = onClick)
      .semantics { if (iconOnly) contentDescription = label }
      .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.Center) {
    MusicBrowseIcon(glyph, foreground, Modifier.size(24.dp))
    if (!iconOnly) {
      Spacer(Modifier.width(10.dp))
      Text(label, color = foreground, fontFamily = FontFamily.Default, fontSize = 17.sp,
          fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center)
    }
  }
}

/** Native glyphs match the player's line weight and avoid adding an icon package. */
@Composable
private fun MusicBrowseIcon(glyph: MusicBrowseGlyph, color: Color, modifier: Modifier) {
  val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
  Canvas(modifier) {
    val unit = size.minDimension / 24f
    val origin = Offset((size.width - 24 * unit) / 2, (size.height - 24 * unit) / 2)
    fun point(x: Float, y: Float) = origin + Offset(x * unit, y * unit)
    fun line(x: Float, y: Float, x2: Float, y2: Float) =
        drawLine(color, point(x, y), point(x2, y2), 2 * unit, StrokeCap.Round)
    when (glyph) {
      MusicBrowseGlyph.BACK -> {
        if (rtl) {
          line(4f, 12f, 20f, 12f); line(14f, 6f, 20f, 12f); line(14f, 18f, 20f, 12f)
        } else {
          line(4f, 12f, 20f, 12f); line(10f, 6f, 4f, 12f); line(10f, 18f, 4f, 12f)
        }
      }
      MusicBrowseGlyph.PLAY -> {
        drawPath(Path().apply {
          moveTo(point(8f, 5f).x, point(8f, 5f).y)
          lineTo(point(19f, 12f).x, point(19f, 12f).y)
          lineTo(point(8f, 19f).x, point(8f, 19f).y)
          close()
        }, color)
      }
      MusicBrowseGlyph.PLAYER -> {
        drawRoundRect(color, point(3f, 4f), Size(18 * unit, 16 * unit),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3 * unit),
            style = Stroke(2 * unit))
        line(8f, 14f, 8f, 10f); line(12f, 16f, 12f, 8f); line(16f, 14f, 16f, 10f)
      }
      MusicBrowseGlyph.MUSIC -> {
        line(9f, 17f, 9f, 5f); line(9f, 5f, 19f, 3f); line(19f, 3f, 19f, 15f)
        drawOval(color, point(3f, 15f), Size(6 * unit, 5 * unit))
        drawOval(color, point(13f, 13f), Size(6 * unit, 5 * unit))
      }
      MusicBrowseGlyph.SEARCH -> {
        drawCircle(color, 7 * unit, point(10f, 10f), style = Stroke(2 * unit))
        line(15f, 15f, 21f, 21f)
      }
      MusicBrowseGlyph.CLEAR -> {
        line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f)
      }
    }
  }
}

private fun musicBrowseCount(count: Int, singular: String, plural: String) =
    "$count ${if (count == 1) singular else plural}"

private fun musicBrowseTrackCount(count: Int) = musicBrowseCount(count, "track", "tracks")

private fun musicBrowseDuration(durationMs: Long): String {
  val seconds = durationMs.coerceAtLeast(0) / 1000
  val minutes = seconds / 60
  return if (minutes < 60) "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
      else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:" +
          (seconds % 60).toString().padStart(2, '0')
}

/** Never present a partial sum as the full collection's length. */
private fun musicBrowseCollectionDuration(tracks: List<MusicBrowseTrack>): String? {
  if (tracks.isEmpty()) return null
  var total = 0L
  for (track in tracks) {
    val duration = track.durationMs?.takeIf { it > 0 } ?: return null
    if (duration > Long.MAX_VALUE - total) return null
    total += duration
  }
  val minutes = total / 60_000
  return when {
    minutes == 0L -> "Under a minute"
    minutes < 60 -> "$minutes min"
    else -> "${minutes / 60} hr ${minutes % 60} min"
  }
}
