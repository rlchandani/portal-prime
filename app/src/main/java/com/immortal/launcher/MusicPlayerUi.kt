/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Presentation only: switching layout never changes the playback session. */
enum class MusicPlayerLayout { FULL, COMPACT, PHOTOS }

/** The host/controller owns navigation and all playback commands. */
sealed interface MusicPlayerUiAction {
  data object TogglePlayback : MusicPlayerUiAction
  data object Next : MusicPlayerUiAction
  data object Previous : MusicPlayerUiAction
  data class Seek(val positionMs: Long) : MusicPlayerUiAction
  data class SelectQueueItem(val id: String) : MusicPlayerUiAction
  data class SetRoomVolume(val roomId: String, val percent: Int) : MusicPlayerUiAction
  data class ToggleRoom(val roomId: String) : MusicPlayerUiAction
  data class SelectPlayer(val roomId: String) : MusicPlayerUiAction
  data object BrowseLibrary : MusicPlayerUiAction
  data object OpenSettings : MusicPlayerUiAction
}

/**
 * Native content inside Home's existing chrome. The host provides photos and owns layout,
 * dismissal, settings and library navigation. Remove this content when hidden; the Home
 * page's existing visibility/lifecycle gate also suspends the isolated progress ticker.
 */
@Composable
fun MusicPlayerContent(
    snapshot: MusicPlayerSnapshot,
    layout: MusicPlayerLayout,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onLayoutChange: (MusicPlayerLayout) -> Unit,
    onAction: (MusicPlayerUiAction) -> Unit,
    photoContent: @Composable (Modifier) -> Unit = {},
) {
  val palette = LocalApprovedPalette.current
  CompositionLocalProvider(
      LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = FontFamily.Default),
  ) {
    when (layout) {
      MusicPlayerLayout.COMPACT -> MusicCompact(
          snapshot, modifier, onDismiss, onLayoutChange, onAction)
      MusicPlayerLayout.FULL -> MusicPositionScope(snapshot) {
        MusicWorkspace(snapshot, modifier.background(palette.background),
            onDismiss, onLayoutChange, onAction)
      }
      MusicPlayerLayout.PHOTOS -> BoxWithConstraints(
          modifier.fillMaxSize().background(palette.background).padding(16.dp)) {
        if (maxWidth >= 720.dp) {
          MusicPositionScope(snapshot) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
              Box(Modifier.weight(1.7f).fillMaxHeight().clip(RoundedCornerShape(18.dp))) {
                photoContent(Modifier.fillMaxSize())
              }
              MusicPhotoControls(snapshot, Modifier.weight(1f).fillMaxHeight(),
                  onDismiss, onLayoutChange, onAction)
            }
          }
        } else {
          Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(18.dp))) {
              photoContent(Modifier.fillMaxSize())
            }
            MusicCompact(snapshot, Modifier.fillMaxWidth(), onDismiss, onLayoutChange, onAction)
          }
        }
      }
    }
  }
}

private enum class MusicPanel(val label: String) { QUEUE("Queue"), LYRICS("Lyrics"), ROOMS("Players") }
private enum class MusicGlyph {
  MUSIC, PLAY, PAUSE, NEXT, PREVIOUS, CLOSE, EXPAND, MINIMIZE, PHOTOS, VOLUME, SETTINGS,
}

@Composable
private fun MusicWorkspace(
    snapshot: MusicPlayerSnapshot,
    modifier: Modifier,
    onDismiss: () -> Unit,
    onLayoutChange: (MusicPlayerLayout) -> Unit,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  BoxWithConstraints(modifier.fillMaxSize().padding(24.dp)) {
    val wide = maxWidth >= 760.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      MusicHeading(snapshot, MusicPlayerLayout.FULL, onDismiss, onLayoutChange, onAction)
      MusicNotice(snapshot)
      if (wide) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
          MusicCurrent(snapshot, Modifier.weight(1f).fillMaxHeight(), false, onAction)
          MusicSidePanel(snapshot, Modifier.weight(1f).fillMaxHeight(), onAction)
        }
      } else {
        // Each pane has a finite height: no LazyColumn inside an unbounded scroller.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          MusicCurrent(snapshot, Modifier.weight(1.1f).fillMaxWidth(), true, onAction)
          MusicSidePanel(snapshot, Modifier.weight(1f).fillMaxWidth(), onAction)
        }
      }
    }
  }
}

@Composable
private fun MusicPhotoControls(
    snapshot: MusicPlayerSnapshot,
    modifier: Modifier,
    onDismiss: () -> Unit,
    onLayoutChange: (MusicPlayerLayout) -> Unit,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    MusicHeading(snapshot, MusicPlayerLayout.PHOTOS, onDismiss, onLayoutChange, onAction)
    MusicNotice(snapshot)
    MusicCurrent(snapshot, Modifier.weight(1f).fillMaxWidth(), true, onAction)
  }
}

@Composable
private fun MusicHeading(
    snapshot: MusicPlayerSnapshot,
    layout: MusicPlayerLayout,
    onDismiss: () -> Unit,
    onLayoutChange: (MusicPlayerLayout) -> Unit,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        MusicText(if (layout == MusicPlayerLayout.PHOTOS) "Now playing" else "Music",
            size = 30, bold = true)
        MusicText(musicSourceLabel(snapshot), muted = true, size = 16)
      }
      MusicButton("Music settings", glyph = MusicGlyph.SETTINGS, iconOnly = true) {
        onAction(MusicPlayerUiAction.OpenSettings)
      }
      MusicButton("Close player", glyph = MusicGlyph.CLOSE, iconOnly = true, onClick = onDismiss)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MusicButton(if (layout == MusicPlayerLayout.FULL) "Minimize" else "Full player",
          modifier = Modifier.weight(1f), glyph = if (layout == MusicPlayerLayout.FULL)
            MusicGlyph.MINIMIZE else MusicGlyph.EXPAND) {
        onLayoutChange(if (layout == MusicPlayerLayout.FULL) MusicPlayerLayout.COMPACT
            else MusicPlayerLayout.FULL)
      }
      MusicButton(if (layout == MusicPlayerLayout.PHOTOS) "Hide player" else "Photos",
          modifier = Modifier.weight(1f), glyph = if (layout == MusicPlayerLayout.PHOTOS)
            MusicGlyph.CLOSE else MusicGlyph.PHOTOS) {
        if (layout == MusicPlayerLayout.PHOTOS) onDismiss()
        else onLayoutChange(MusicPlayerLayout.PHOTOS)
      }
    }
  }
}

@Composable
private fun MusicCompact(
    snapshot: MusicPlayerSnapshot,
    modifier: Modifier,
    onDismiss: () -> Unit,
    onLayoutChange: (MusicPlayerLayout) -> Unit,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  val palette = LocalApprovedPalette.current
  val track = musicCoherentTrack(snapshot)
  val status = musicNoticeText(snapshot)?.first ?: musicPlaybackLabel(snapshot)
  BoxWithConstraints(modifier.clip(RoundedCornerShape(20.dp))
      .background(palette.surface).border(1.dp, palette.divider, RoundedCornerShape(20.dp))
      .padding(12.dp)) {
    val narrow = maxWidth < 520.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MusicCover(snapshot.sourceId, track?.id.orEmpty(), track?.artUrl.orEmpty(),
            Modifier.size(64.dp), borrowedArt = snapshot.artBitmap.takeIf { track != null },
            maxEdge = 384)
        Column(Modifier.weight(1f)) {
          MusicText(track?.title?.ifBlank { "Untitled track" } ?: status, size = 19, bold = true)
          MusicText(if (track == null) musicSourceLabel(snapshot) else
            listOf(track.artist, status).filter { it.isNotBlank() }.joinToString(" · "),
              muted = true, size = 15, maxLines = 2)
        }
        if (!narrow) MusicPlayButton(snapshot, onAction)
        MusicButton("Hide player", glyph = MusicGlyph.CLOSE, iconOnly = true, onClick = onDismiss)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically) {
        if (narrow) MusicPlayButton(snapshot, onAction)
        MusicButton("Open player", Modifier.weight(1f), glyph = MusicGlyph.EXPAND) {
          onLayoutChange(MusicPlayerLayout.FULL)
        }
        MusicButton("Photos", Modifier.weight(1f), glyph = MusicGlyph.PHOTOS) {
          onLayoutChange(MusicPlayerLayout.PHOTOS)
        }
      }
    }
  }
}

@Composable
private fun MusicCurrent(
    snapshot: MusicPlayerSnapshot,
    modifier: Modifier,
    small: Boolean,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  val track = musicCoherentTrack(snapshot)
  Column(modifier.verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (track == null) {
      Spacer(Modifier.height(12.dp))
      MusicIcon(MusicGlyph.MUSIC, LocalApprovedPalette.current.secondary, Modifier.size(72.dp))
      MusicText(musicEmptyTitle(snapshot), size = 28, bold = true, maxLines = 3,
          textAlign = TextAlign.Center)
      MusicText(musicEmptyDetail(snapshot), muted = true, size = 18, maxLines = 6,
          textAlign = TextAlign.Center)
      if (musicCanBrowse(snapshot)) MusicButton("Browse music") {
        onAction(MusicPlayerUiAction.BrowseLibrary)
      }
      MusicButton(if (snapshot.connection == MusicConnection.NOT_CONFIGURED)
        "Set up music" else "Music settings") { onAction(MusicPlayerUiAction.OpenSettings) }
    } else {
      MusicCover(snapshot.sourceId, track.id, track.artUrl,
          Modifier.widthIn(max = if (small) 180.dp else 420.dp).fillMaxWidth().aspectRatio(1f),
          borrowedArt = snapshot.artBitmap, maxEdge = if (small) 384 else 1024)
      MusicText(track.title.ifBlank { "Untitled track" }, size = if (small) 26 else 38,
          bold = true, maxLines = 3, textAlign = TextAlign.Center)
      if (track.artist.isNotBlank()) MusicText(track.artist, muted = true, size = if (small) 20 else 24,
          maxLines = 2, textAlign = TextAlign.Center)
      if (track.album.isNotBlank()) MusicText(track.album, muted = true, size = if (small) 16 else 20,
          maxLines = 2, textAlign = TextAlign.Center)
      MusicText(musicPlaybackLabel(snapshot), muted = true, size = 15)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically) {
        MusicButton("Previous track", glyph = MusicGlyph.PREVIOUS, iconOnly = true,
            enabled = musicCanSend(snapshot, MusicCommand.PREVIOUS)) {
          onAction(MusicPlayerUiAction.Previous)
        }
        Spacer(Modifier.width(16.dp))
        MusicPlayButton(snapshot, onAction)
        Spacer(Modifier.width(16.dp))
        MusicButton("Next track", glyph = MusicGlyph.NEXT, iconOnly = true,
            enabled = musicCanSend(snapshot, MusicCommand.NEXT)) {
          onAction(MusicPlayerUiAction.Next)
        }
      }
      MusicProgress(snapshot, Modifier.fillMaxWidth(), onAction)
      if (!snapshot.can(MusicCommand.NEXT) && !snapshot.can(MusicCommand.PREVIOUS) &&
          !track.isLive && snapshot.connection == MusicConnection.READY) {
        MusicText("Track skipping is unavailable from this player.", muted = true, size = 14,
            maxLines = 2, textAlign = TextAlign.Center)
      }
    }
    Spacer(Modifier.height(8.dp))
  }
}

@Composable
private fun MusicPlayButton(
    snapshot: MusicPlayerSnapshot,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  val playing = snapshot.playback == PlaybackState.PLAYING
  MusicButton(if (playing) "Pause" else "Play",
      glyph = if (playing) MusicGlyph.PAUSE else MusicGlyph.PLAY, emphasized = true,
      enabled = musicCanSend(snapshot, if (playing) MusicCommand.PAUSE else MusicCommand.PLAY)) {
    onAction(MusicPlayerUiAction.TogglePlayback)
  }
}

private val LocalMusicVisiblePosition = staticCompositionLocalOf<State<Long?>> {
  error("Visible music progress requires MusicPositionScope")
}

/** The scope shares one clock without reading it; only progress and visible lyrics subscribe. */
@Composable
private fun MusicPositionScope(snapshot: MusicPlayerSnapshot, content: @Composable () -> Unit) {
  val position = rememberMusicVisiblePosition(snapshot)
  CompositionLocalProvider(LocalMusicVisiblePosition provides position, content = content)
}

/** Monotonic, capability-neutral display progress; no timers in compact or hidden pages. */
@Composable
private fun rememberMusicVisiblePosition(snapshot: MusicPlayerSnapshot): State<Long?> {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val pageActive = LocalApprovedLauncherPageActive.current
  val latest = rememberUpdatedState(snapshot)
  val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
  val position = remember { derivedStateOf { latest.value.visiblePositionMs(now.longValue) } }
  val track = musicCoherentTrack(snapshot)
  val duration = track?.durationMs?.takeIf { it > 0 }
  val ticking = pageActive && snapshot.connection == MusicConnection.READY &&
      snapshot.playback == PlaybackState.PLAYING && !snapshot.audioOwnedByOther &&
      track != null && !track.isLive && duration != null
  LaunchedEffect(lifecycle, ticking, snapshot.sourceId, track?.id, duration,
      snapshot.positionMs, snapshot.positionAtElapsedMs) {
    if (ticking) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (isActive) {
        now.longValue = SystemClock.elapsedRealtime()
        if (latest.value.visiblePositionMs(now.longValue) == duration) break
        delay(500)
      }
    }
  }
  return position
}

/** This leaf reads progress; its ticks do not recompose the player shell, artwork or Home. */
@Composable
private fun MusicProgress(
    snapshot: MusicPlayerSnapshot,
    modifier: Modifier,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  val track = musicCoherentTrack(snapshot) ?: return
  if (track.isLive) {
    MusicText("Live radio · seeking is unavailable", muted = true, size = 15, maxLines = 2)
    return
  }
  val duration = track.durationMs?.takeIf { it > 0 }
  if (duration == null) {
    MusicText("Track duration is unavailable", muted = true, size = 15)
    return
  }
  var draft by remember(snapshot.sourceId, track.id) { mutableStateOf<Float?>(null) }
  val enabled = musicCanSend(snapshot, MusicCommand.SEEK)
  LaunchedEffect(enabled) { if (!enabled) draft = null }
  val position = LocalMusicVisiblePosition.current.value ?: return
  val fraction = (position.toDouble() / duration).toFloat().coerceIn(0f, 1f)
  Column(modifier) {
    Slider(
        value = draft ?: fraction,
        onValueChange = { if (enabled) draft = it },
        onValueChangeFinished = {
          val value = draft
          draft = null
          if (value != null && musicCanSend(snapshot, MusicCommand.SEEK)) {
            onAction(MusicPlayerUiAction.Seek((value.toDouble() * duration).toLong()
                .coerceIn(0, duration)))
          }
        },
        enabled = enabled,
        colors = musicSliderColors(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { contentDescription = "Track position" },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      MusicText(musicTime(draft?.let { (it.toDouble() * duration).toLong() } ?: position),
          muted = true, size = 14)
      MusicText(musicTime(duration), muted = true, size = 14)
    }
    if (!snapshot.can(MusicCommand.SEEK)) MusicText("Seeking is unavailable",
        muted = true, size = 14)
  }
}

@Composable
private fun MusicSidePanel(
    snapshot: MusicPlayerSnapshot,
    modifier: Modifier,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  var panel by rememberMusicPanel(snapshot.sourceId)
  Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      MusicPanel.entries.forEach { candidate ->
        // Queue and lyrics cannot be opened when the selected source does not supply them.
        // Players remains an informative view of real availability during setup/offline.
        val enabled = when (candidate) {
          MusicPanel.QUEUE -> snapshot.can(MusicCommand.OPEN_QUEUE)
          MusicPanel.LYRICS -> snapshot.can(MusicCommand.OPEN_LYRICS)
          MusicPanel.ROOMS -> snapshot.rooms.isNotEmpty()
        }
        MusicButton(candidate.label, Modifier.weight(1f), enabled = enabled,
            chosen = candidate == panel) { panel = candidate }
      }
    }
    when (panel) {
      MusicPanel.QUEUE -> MusicQueue(snapshot, Modifier.weight(1f).fillMaxWidth(), onAction)
      MusicPanel.LYRICS -> MusicLyrics(snapshot, Modifier.weight(1f).fillMaxWidth())
      MusicPanel.ROOMS -> MusicRooms(snapshot, Modifier.weight(1f).fillMaxWidth(), onAction)
    }
    if (musicCanBrowse(snapshot)) {
      MusicButton("Browse music", Modifier.fillMaxWidth()) {
        onAction(MusicPlayerUiAction.BrowseLibrary)
      }
    }
  }
}

@Composable
private fun rememberMusicPanel(sourceId: String): MutableState<MusicPanel> =
    remember(sourceId) { mutableStateOf(MusicPanel.QUEUE) }

@Composable
private fun MusicQueue(
    snapshot: MusicPlayerSnapshot,
    modifier: Modifier,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  if (!snapshot.can(MusicCommand.OPEN_QUEUE)) {
    MusicPanelMessage(if (snapshot.track?.isLive == true) "Live station" else "Queue unavailable",
        if (snapshot.track?.isLive == true) "This station does not supply a song queue."
        else "The selected player is not supplying an available queue.", modifier)
    return
  }
  if (snapshot.queue.isEmpty()) {
    MusicPanelMessage("Queue is empty", "Browse music or choose something in your music app.", modifier)
    return
  }
  val palette = LocalApprovedPalette.current
  LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp),
      contentPadding = PaddingValues(bottom = 12.dp)) {
    // Stable source IDs, with no eager artwork decode for the entire queue.
    itemsIndexed(snapshot.queue, key = { index, item -> item.id.ifBlank { "unavailable:$index" } }) {
        _, item ->
      val current = musicCoherentTrack(snapshot)?.id == item.id && item.id.isNotBlank()
      Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
          .background(if (current) palette.surface else Color.Transparent)
          .clickable(enabled = item.id.isNotBlank() &&
              musicCanSend(snapshot, MusicCommand.SELECT_QUEUE_ITEM), role = Role.Button) {
            onAction(MusicPlayerUiAction.SelectQueueItem(item.id))
          }.semantics { selected = current }.heightIn(min = 72.dp).padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        MusicIcon(if (current && snapshot.playback == PlaybackState.PLAYING)
          MusicGlyph.PLAY else MusicGlyph.MUSIC, palette.secondary, Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
          MusicText(item.title.ifBlank { "Untitled track" }, size = 20, bold = true, maxLines = 2)
          MusicText(if (current) musicPlaybackLabel(snapshot) else item.artist,
              muted = true, size = 16)
        }
        item.durationMs?.takeIf { it > 0 }?.let {
          MusicText(musicTime(it), muted = true, size = 15)
        }
      }
    }
  }
}

@Composable
private fun MusicLyrics(snapshot: MusicPlayerSnapshot, modifier: Modifier) {
  if (!snapshot.can(MusicCommand.OPEN_LYRICS) || snapshot.lyrics.isEmpty()) {
    MusicPanelMessage("No timed lyrics for this track",
        "Playback continues. Lyrics appear when the selected player supplies them.", modifier)
    return
  }
  val position = LocalMusicVisiblePosition.current
  val lines = rememberUpdatedState(snapshot.lyrics)
  val reached by remember(position) {
    derivedStateOf {
      position.value?.let { musicLyricIndex(lines.value, it) } ?: -1
    }
  }
  // Subscribe to line changes only; the shared clock never invalidates the whole player.
  LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(24.dp),
      contentPadding = PaddingValues(vertical = 16.dp)) {
    itemsIndexed(snapshot.lyrics, key = { index, line -> "${line.timeMs}:$index" }) { index, line ->
      MusicText(line.text, size = 28, bold = true, maxLines = 6, muted = index != reached,
          modifier = Modifier.fillMaxWidth().semantics { selected = index == reached })
    }
  }
}

@Composable
private fun MusicRooms(
    snapshot: MusicPlayerSnapshot,
    modifier: Modifier,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  if (snapshot.rooms.isEmpty()) {
    MusicPanelMessage("No players available", "Choose or connect a player in Music settings.", modifier)
    return
  }
  // MA sources include the raw player ID. Never infer a leader from list order or names.
  val playerId = musicMaPlayerId(snapshot)
  val anchor = snapshot.rooms.firstOrNull { it.id == playerId }
  LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp),
      contentPadding = PaddingValues(bottom = 12.dp)) {
    item {
      MusicText("Choose a player. Group controls appear only for compatible rooms.",
          muted = true, size = 16, maxLines = 3)
    }
    itemsIndexed(snapshot.rooms, key = { index, room -> room.id.ifBlank { "room:$index" } }) { _, room ->
      val sameGroup = anchor?.groupId?.takeIf { it.isNotBlank() }?.let {
        it == room.groupId
      } == true
      val compatible = anchor != null && room.id != anchor.id &&
          room.id in anchor.canGroupWith
      val canChange = room.available && room.id.isNotBlank() &&
          musicCanSend(snapshot, MusicCommand.CHANGE_ROOMS)
      Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
          .background(LocalApprovedPalette.current.surface).padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MusicText(room.name.ifBlank { "Unnamed player" }, size = 20, bold = true, maxLines = 2)
        MusicText(when {
          !room.available -> "Offline"
          sameGroup -> "In the current group"
          !room.groupId.isNullOrBlank() -> "In a group"
          else -> "Available"
        }, muted = true, size = 15)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          MusicButton("Use player", Modifier.weight(1f), enabled = canChange) {
            onAction(MusicPlayerUiAction.SelectPlayer(room.id))
          }
          MusicButton(if (sameGroup) "Remove" else "Join group", Modifier.weight(1f),
              enabled = canChange && anchor != null && room.id != anchor.id &&
                  (sameGroup || compatible)) {
            onAction(MusicPlayerUiAction.ToggleRoom(room.id))
          }
        }
        val volume = room.volume?.takeIf { it in 0..100 }
        if (volume != null) MusicRoomVolume(snapshot, room, volume, onAction)
        else MusicText("Volume unavailable", muted = true, size = 14)
      }
    }
  }
}

@Composable
private fun MusicRoomVolume(
    snapshot: MusicPlayerSnapshot,
    room: MusicRoom,
    volume: Int,
    onAction: (MusicPlayerUiAction) -> Unit,
) {
  var draft by remember(snapshot.sourceId, room.id) { mutableStateOf<Float?>(null) }
  val enabled = room.available && room.volumeControllable && room.id.isNotBlank() &&
      musicCanSend(snapshot, MusicCommand.SET_VOLUME)
  LaunchedEffect(enabled) { if (!enabled) draft = null }
  Row(verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    MusicIcon(MusicGlyph.VOLUME, LocalApprovedPalette.current.secondary, Modifier.size(24.dp))
    Slider(value = draft ?: volume.toFloat(), valueRange = 0f..100f,
        onValueChange = { if (enabled) draft = it },
        onValueChangeFinished = {
          val value = draft
          draft = null
          if (value != null && enabled) onAction(
              MusicPlayerUiAction.SetRoomVolume(room.id, value.toInt().coerceIn(0, 100)))
        },
        enabled = enabled, colors = musicSliderColors(),
        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
            .semantics { contentDescription = "${room.name} volume" })
    // Keep the displayed value server-confirmed; dragging never declares success.
    MusicText(if (room.muted == true) "Muted" else "$volume%", muted = true, size = 14)
  }
}

@Composable
private fun MusicNotice(snapshot: MusicPlayerSnapshot) {
  val notice = musicNoticeText(snapshot)
  val pending = snapshot.pending
  if (notice == null && pending == null && snapshot.problem.isNullOrBlank()) return
  val palette = LocalApprovedPalette.current
  Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(palette.surface)
      .padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (notice != null) {
      MusicText(notice.first, bold = true, size = 18)
      MusicText(notice.second, muted = true, size = 16, maxLines = 4)
    }
    if (pending != null) MusicText("Change pending · waiting for player confirmation",
        muted = true, size = 16, maxLines = 2)
    snapshot.problem?.takeIf { it.isNotBlank() }?.let {
      MusicText(it, muted = true, size = 16, maxLines = 3)
    }
  }
}

private fun musicNoticeText(snapshot: MusicPlayerSnapshot): Pair<String, String>? = when {
  snapshot.audioOwnedByOther -> "Audio is in use" to
      "A call or another app owns the speaker. Music controls will return when it releases audio."
  snapshot.connection == MusicConnection.NOT_CONFIGURED -> "Music is not configured" to
      "Connect Music Assistant or start music in a supported media app."
  snapshot.connection == MusicConnection.CONNECTING -> "Connecting to your player" to
      "Controls will be available when the player is ready."
  snapshot.connection == MusicConnection.OFFLINE -> "Player is offline" to
      "This is the last known state. Controls are unavailable until the player reconnects."
  snapshot.connection == MusicConnection.AUTH_REQUIRED -> "Music authorization required" to
      "Open Music settings to authorize the connection."
  snapshot.connection == MusicConnection.INCOMPATIBLE -> "Music server is incompatible" to
      "Check the supported server version in Music settings."
  snapshot.track != null && musicCoherentTrack(snapshot) == null -> "Waiting for track details" to
      "The player has not supplied matching track information."
  else -> null
}

private fun musicEmptyTitle(snapshot: MusicPlayerSnapshot): String =
    musicNoticeText(snapshot)?.first ?: if (snapshot.rooms.isNotEmpty() &&
      snapshot.rooms.none { it.available }) "No player available" else "Nothing playing yet"

private fun musicEmptyDetail(snapshot: MusicPlayerSnapshot): String = when {
  snapshot.connection == MusicConnection.NOT_CONFIGURED ->
    "Music Assistant brings your library and speakers together. Install its server on your NAS " +
        "or Home Assistant host, then connect this Portal in Music settings."
  musicNoticeText(snapshot) != null -> musicNoticeText(snapshot)!!.second
  snapshot.rooms.isNotEmpty() && snapshot.rooms.none { it.available } ->
    "Turn on a connected player or choose another one in Music settings."
  else -> "Start music from your connected player. Your photos stay available while it plays."
}

private fun musicCoherentTrack(snapshot: MusicPlayerSnapshot): MusicTrack? =
    snapshot.track?.takeIf { it.matches(snapshot.sourceId, it.id) }

private fun musicCanSend(snapshot: MusicPlayerSnapshot, command: MusicCommand): Boolean =
    snapshot.can(command) && snapshot.pending == null

private fun musicCanBrowse(snapshot: MusicPlayerSnapshot): Boolean =
    musicMaPlayerId(snapshot) != null && snapshot.connection == MusicConnection.READY &&
        !snapshot.audioOwnedByOther

private fun musicMaPlayerId(snapshot: MusicPlayerSnapshot): String? =
    snapshot.sourceId.takeIf { it.startsWith("ma:") }?.removePrefix("ma:")?.takeIf { it.isNotBlank() }

private fun musicSourceLabel(snapshot: MusicPlayerSnapshot): String = when {
  snapshot.connection == MusicConnection.OFFLINE -> "Last known state"
  musicMaPlayerId(snapshot) != null -> "Music Assistant"
  snapshot.sourceId.startsWith("local:") -> "Local media app"
  snapshot.sourceId.isBlank() -> "Choose a music source"
  else -> "Media app"
}

private fun musicPlaybackLabel(snapshot: MusicPlayerSnapshot): String = when {
  snapshot.audioOwnedByOther -> "Audio in use"
  snapshot.connection == MusicConnection.OFFLINE -> "Last known track"
  snapshot.pending != null -> "Change pending"
  else -> when (snapshot.playback) {
    PlaybackState.PLAYING -> "Playing"
    PlaybackState.PAUSED -> "Paused"
    PlaybackState.STOPPED -> "Stopped"
    PlaybackState.IDLE -> "Ready"
  }
}

private fun musicTime(positionMs: Long): String {
  val seconds = positionMs.coerceAtLeast(0) / 1000
  return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun MusicPanelMessage(title: String, detail: String, modifier: Modifier) {
  Column(modifier.verticalScroll(rememberScrollState()).padding(top = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)) {
    MusicText(title, bold = true, size = 24, maxLines = 3)
    MusicText(detail, muted = true, size = 18, maxLines = 6)
  }
}

@Composable
private fun musicSliderColors() = LocalApprovedPalette.current.let { palette ->
  SliderDefaults.colors(thumbColor = palette.text, activeTrackColor = palette.text,
      inactiveTrackColor = palette.divider, disabledThumbColor = palette.secondary,
      disabledActiveTrackColor = palette.secondary, disabledInactiveTrackColor = palette.divider)
}

/**
 * Prefer the read-only media-session bitmap, without recycling it. Otherwise consume the
 * shared network loader or bounded local provider reader. Key the whole state by source,
 * track, URI, decode size and borrowed bitmap so a prior cover never flashes during a request.
 */
@Composable
private fun MusicCover(
    sourceId: String,
    trackId: String,
    artUrl: String,
    modifier: Modifier,
    borrowedArt: Bitmap? = null,
    maxEdge: Int = 384,
) {
  val resolver = LocalContext.current.contentResolver
  key(sourceId, trackId, artUrl, borrowedArt, maxEdge, resolver) {
    val supplied = remember(borrowedArt) {
      borrowedArt?.takeUnless { it.isRecycled }?.asImageBitmap()
    }
    val bitmap by produceState<ImageBitmap?>(null) {
      if (supplied == null && sourceId.isNotBlank() && trackId.isNotBlank() && artUrl.isNotBlank()) {
        value = withContext(Dispatchers.IO) {
          when (MusicLocalArtwork.routeFor(artUrl)) {
            MusicArtworkRoute.NETWORK -> MultiRoomArt.fetch(artUrl, maxEdge)
            MusicArtworkRoute.LOCAL -> MusicLocalArtwork.fetch(resolver, artUrl, maxEdge)
            MusicArtworkRoute.UNSUPPORTED -> null
          }?.asImageBitmap()
        }
      }
    }
    val palette = LocalApprovedPalette.current
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(palette.surface2),
        contentAlignment = Alignment.Center) {
      val cover = supplied ?: bitmap
      if (cover != null) Image(cover, contentDescription = "Album artwork",
          modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
      else MusicIcon(MusicGlyph.MUSIC, palette.secondary,
          Modifier.fillMaxSize(0.34f).semantics { contentDescription = "Artwork unavailable" })
    }
  }
}

@Composable
private fun MusicText(
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
      fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
      maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = textAlign)
}

@Composable
private fun MusicButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glyph: MusicGlyph? = null,
    iconOnly: Boolean = false,
    emphasized: Boolean = false,
    chosen: Boolean = false,
    onClick: () -> Unit,
) {
  val palette = LocalApprovedPalette.current
  val foreground = if (emphasized) palette.background else palette.text
  val background = when {
    emphasized -> palette.text
    chosen -> palette.selected
    else -> palette.surface2
  }
  Row(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
      .background(if (enabled) background else palette.surface)
      .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
      .semantics { contentDescription = label; selected = chosen }
      .padding(horizontal = if (iconOnly) 12.dp else 14.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.Center,
      verticalAlignment = Alignment.CenterVertically) {
    val color = if (enabled) foreground else palette.secondary
    if (glyph != null) MusicIcon(glyph, color, Modifier.size(24.dp))
    if (!iconOnly) {
      if (glyph != null) Spacer(Modifier.width(8.dp))
      Text(label, color = color, fontFamily = FontFamily.Default, fontSize = 16.sp,
          fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.Center)
    }
  }
}

/** Small native vector drawings, independent of icon-font assets or extended icon packages. */
@Composable
private fun MusicIcon(glyph: MusicGlyph, color: Color, modifier: Modifier = Modifier) {
  Canvas(modifier) {
    val unit = size.minDimension / 24f
    val origin = Offset((size.width - 24 * unit) / 2, (size.height - 24 * unit) / 2)
    fun point(x: Float, y: Float) = origin + Offset(x * unit, y * unit)
    fun line(x: Float, y: Float, x2: Float, y2: Float) =
        drawLine(color, point(x, y), point(x2, y2), 2 * unit, StrokeCap.Round)
    fun rect(x: Float, y: Float, width: Float, height: Float) =
        drawRect(color, point(x, y), Size(width * unit, height * unit))
    fun triangle(x: Float, y: Float, right: Boolean = true) {
      drawPath(Path().apply {
        moveTo(point(x, y).x, point(x, y).y)
        val edge = point(x + if (right) 10f else -10f, y + 7f)
        lineTo(edge.x, edge.y)
        lineTo(point(x, y + 14f).x, point(x, y + 14f).y)
        close()
      }, color)
    }
    when (glyph) {
      MusicGlyph.PLAY -> triangle(7f, 5f)
      MusicGlyph.PAUSE -> { rect(6f, 5f, 4f, 14f); rect(14f, 5f, 4f, 14f) }
      MusicGlyph.NEXT -> { triangle(5f, 5f); rect(17f, 5f, 2f, 14f) }
      MusicGlyph.PREVIOUS -> { triangle(19f, 5f, false); rect(5f, 5f, 2f, 14f) }
      MusicGlyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
      MusicGlyph.MUSIC -> {
        line(9f, 17f, 9f, 5f); line(9f, 5f, 19f, 3f); line(19f, 3f, 19f, 15f)
        drawOval(color, point(3f, 15f), Size(6 * unit, 5 * unit))
        drawOval(color, point(13f, 13f), Size(6 * unit, 5 * unit))
      }
      MusicGlyph.EXPAND -> {
        line(4f, 9f, 4f, 4f); line(4f, 4f, 9f, 4f)
        line(15f, 20f, 20f, 20f); line(20f, 20f, 20f, 15f)
        line(4f, 4f, 10f, 10f); line(14f, 14f, 20f, 20f)
      }
      MusicGlyph.MINIMIZE -> {
        line(4f, 4f, 10f, 10f); line(10f, 10f, 10f, 5f); line(10f, 10f, 5f, 10f)
        line(20f, 20f, 14f, 14f); line(14f, 14f, 14f, 19f); line(14f, 14f, 19f, 14f)
      }
      MusicGlyph.PHOTOS -> {
        drawRect(color, point(3f, 4f), Size(18 * unit, 16 * unit), style = Stroke(2 * unit))
        drawCircle(color, 2 * unit, point(8f, 9f))
        line(4f, 18f, 11f, 12f); line(11f, 12f, 15f, 16f); line(15f, 16f, 20f, 11f)
      }
      MusicGlyph.VOLUME -> {
        drawPath(Path().apply {
          moveTo(point(3f, 9f).x, point(3f, 9f).y)
          for ((x, y) in listOf(7f to 9f, 12f to 5f, 12f to 19f, 7f to 15f, 3f to 15f)) {
            lineTo(point(x, y).x, point(x, y).y)
          }
          close()
        }, color)
        drawArc(color, -50f, 100f, false, point(9f, 5f), Size(12 * unit, 14 * unit),
            style = Stroke(2 * unit, cap = StrokeCap.Round))
      }
      MusicGlyph.SETTINGS -> {
        drawCircle(color, 6 * unit, point(12f, 12f), style = Stroke(2 * unit))
        drawCircle(color, 2 * unit, point(12f, 12f), style = Stroke(2 * unit))
        line(12f, 2f, 12f, 5f); line(12f, 19f, 12f, 22f)
        line(2f, 12f, 5f, 12f); line(19f, 12f, 22f, 12f)
        line(5f, 5f, 7f, 7f); line(17f, 17f, 19f, 19f)
        line(5f, 19f, 7f, 17f); line(17f, 7f, 19f, 5f)
      }
    }
  }
}
