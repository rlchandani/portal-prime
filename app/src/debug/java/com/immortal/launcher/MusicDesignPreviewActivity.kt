/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.view.Gravity
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.immortal.launcher.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

/** Debug-only visual acceptance host. It never creates a player controller or audio output. */
class MusicDesignPreviewActivity : ComponentActivity() {
  internal var realPhotoControllerIdentityForChecks = 0
    private set
  private var nightClock: QuietNightClockView? = null
  private var gallery: SlideshowStage? = null
  private var galleryControls: SlideshowViewerControls? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (intent.getStringExtra("screen") == "night") {
      window.attributes = window.attributes.apply { screenBrightness = 0.03f }
      nightClock = QuietNightClockView(this, intent.getStringExtra("tone") ?: "warm")
      setContentView(nightClock)
      return
    }
    if (intent.getStringExtra("screen") in listOf("gallery", "spread")) {
      val spread = intent.getStringExtra("screen") == "spread"
      val stage = SlideshowStage(this)
      gallery = stage
      stage.onPresented = { galleryControls?.photoChanged() }
      val root = FrameLayout(this)
      root.addView(stage, FrameLayout.LayoutParams(-1, -1))
      setContentView(root)
      lifecycleScope.launch {
        val photos = withContext(Dispatchers.IO) {
          val names = if (!spread) listOf("window") else
              if (intent.getStringExtra("scenario") == "landscape") listOf("spread-landscape")
              else listOf("spread-dunes", "spread-tidal")
          names.mapNotNull { name ->
            assets.open("music-design/$name.png").use {
              android.graphics.BitmapFactory.decodeStream(it)
            }?.let { SlideshowPhoto("silent-$name", it) }
          }
        }
        if (photos.isNotEmpty()) {
          gallery?.show(photos, fill = false, motion = false, dwell = 30_000,
              galleryPrint = !spread, gallerySpread = spread)
          if (intent.getBooleanExtra("controls", false)) {
            galleryControls = SlideshowViewerControls(this@MusicDesignPreviewActivity, stage,
                onPrevious = { stage.show(photos.reversed(), false, false, 30_000,
                    galleryPrint = !spread, gallerySpread = spread) },
                onNext = { stage.show(photos, false, false, 30_000,
                    galleryPrint = !spread, gallerySpread = spread) },
                onPause = { paused -> if (paused) stage.pause() else stage.resume() },
                onExit = { finish() }, onSave = { _, _, reply -> reply(true) },
                currentMode = {
                  if (spread) ScreensaverConfig.MODE_GALLERY_SPREAD else ScreensaverConfig.MODE_GALLERY_PRINT
                }).also { controls ->
              root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
              controls.photoChanged()
            }
          }
          if (spread) {
            when (intent.getStringExtra("scenario")) {
              "cancel", "save" -> {
                gallery?.adjust(SlideshowAdjustment(2f, .2f, .8f))
                gallery?.finishAdjustment(intent.getStringExtra("scenario") == "save")
              }
              "overlays" -> {
                gallery?.show(photos.reversed(), fill = false, motion = false,
                    dwell = 30_000, gallerySpread = true)
                gallery?.refreshGalleryOverlays(listOf(android.graphics.Rect(0, 0, 2160, 500)))
              }
            }
          }
        }
      }
      return
    }
    setContent {
      val light = intent.getStringExtra("theme") == "light"
      val palette = if (light) ApprovedLightPalette else ApprovedDarkPalette
      val density = LocalDensity.current
      val fontScale = intent.getFloatExtra("font_scale", 1f).coerceIn(1f, 2f)
      val tracks by produceState<List<MusicQueueItem>>(emptyList()) {
        value = withContext(Dispatchers.IO) {
          val names = listOf("dunes", "tidal", "window", "orbit", "field", "rose")
          val titles = listOf("After the sun", "Blue distance", "An open window",
              "Small hours", "The long way home", "Still, here")
          val artists = listOf("Sora Fields", "North Atlantic", "Eli & the City",
              "Low Orbit", "June Assembly", "Sora Fields")
          names.mapIndexed { index, name ->
            val file = File(cacheDir, "music-design/$name.png")
            file.parentFile?.mkdirs()
            assets.open("music-design/$name.png").use { input ->
              file.outputStream().use { input.copyTo(it) }
            }
            MusicQueueItem("sample-$index", titles[index], artists[index],
                240_000L + index * 17_000L, Uri.fromFile(file).toString())
          }
        }
      }
      var selected by remember { mutableIntStateOf(0) }
      var previewVolumes by remember { mutableStateOf(emptyMap<String, Int>()) }
      var playing by remember { mutableStateOf(false) }
      var position by remember { mutableLongStateOf(83_000L) }
      var changedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
      var layout by remember { mutableStateOf(MusicPlayerLayout.entries.firstOrNull {
        it.name.equals(intent.getStringExtra("layout"), ignoreCase = true)
      } ?: MusicPlayerLayout.FULL) }
      var browse by remember { mutableStateOf(intent.getStringExtra("screen")
          ?.takeIf { it == "library" || it == "playlists" }) }
      val libraryBrowseState = rememberMusicBrowseState()
      val playlistsBrowseState = rememberMusicBrowseState()
      var collection by remember { mutableStateOf<MusicBrowseCollection?>(null) }
      var message by remember { mutableStateOf("Silent design preview · sample music · no audio") }
      val connection = when (intent.getStringExtra("scenario")) {
        "offline" -> MusicConnection.OFFLINE
        "setup" -> MusicConnection.NOT_CONFIGURED
        else -> MusicConnection.READY
      }
      var source by remember { mutableStateOf("ma:design-preview") }
      val groupPreview = intent.getStringExtra("scenario") == "speaker-groups"
      val item = tracks.getOrNull(selected)
      val browseTracks = remember(tracks) { tracks.map {
        MusicBrowseTrack(it.id, it.title, it.artist, it.durationMs, it.artUrl ?: "")
      } }
      val albums = remember(browseTracks) {
        val titles = listOf("The quiet hours", "North shore", "Open windows",
            "Small hours", "Open road", "Quiet bloom")
        browseTracks.mapIndexed { index, track ->
          MusicBrowseCollection("album-$index", titles[index], track.artist,
              track.artworkUri, listOf(track))
        }
      }
      val playlists = remember(browseTracks) {
        listOf("Slow mornings" to listOf(0, 1, 4),
            "After dark" to listOf(3, 5, 2), "At home" to listOf(0, 1, 2, 3, 4, 5))
            .mapIndexed { index, (title, order) ->
              val songs = order.mapNotNull { browseTracks.getOrNull(it) }
              MusicBrowseCollection("playlist-$index", title,
                  listOf("Ease into the day", "A quieter soundtrack", "Room to unwind")[index],
                  songs.firstOrNull()?.artworkUri ?: "", songs,
                  MusicBrowseCollectionKind.PLAYLIST)
            }
      }
      val currentAlbum = item?.let { track ->
        albums.firstOrNull { album -> album.tracks.any { it.id == track.id } }
      }
      // Opt in to the production retained photo host; ordinary visual fixtures stay synthetic.
      val photos = if (intent.getBooleanExtra("real_photos", false))
        rememberMusicPhotos(browse == null && collection == null && layout == MusicPlayerLayout.PHOTOS)
      else null
      val snapshot = MusicPlayerSnapshot(connection = connection, sourceId = source,
          track = item?.let { MusicTrack(it.id, source, it.title, it.artist,
              currentAlbum?.title ?: "", it.artUrl, it.durationMs) },
          playback = if (playing) PlaybackState.PLAYING else PlaybackState.PAUSED,
          positionMs = position, positionAtElapsedMs = changedAt,
          capabilities = MusicCapabilities(play = true, pause = true, previous = true,
              next = true, seek = true, queue = true, rooms = true, volume = true),
          queue = tracks, queueId = "sample-queue",
          controlPlayerId = source,
          rooms = (if (groupPreview) listOf(
              MusicRoom("ma:design-preview", "Family Area", true, 35,
                  volumeControllable = true, canGroupWith = setOf("ma:kitchen")),
              MusicRoom("ma:kitchen", "Kitchen", true, 42,
                  volumeControllable = true, canGroupWith = setOf("ma:design-preview")),
              MusicRoom("ma:bedroom", "Bedroom", true, 20, volumeControllable = true),
              MusicRoom("ma:office", "Office", false),
          ) else listOf(MusicRoom(source, "Family Area", true, 35,
              volumeControllable = true))).map { room ->
            room.copy(volume = previewVolumes[room.id] ?: room.volume)
          })
      CompositionLocalProvider(LocalApprovedPalette provides palette,
          LocalDensity provides Density(density.density, fontScale)) {
        MaterialTheme(colorScheme = approvedColorScheme(!light)) {
          Column(Modifier.fillMaxSize().background(palette.background).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
              Text(message, color = palette.secondary,
                  modifier = Modifier.weight(1f).padding(8.dp), maxLines = 1)
              TextButton(onClick = { browse = "library"; collection = null }) { Text("Library") }
              TextButton(onClick = { browse = "playlists"; collection = null }) { Text("Playlists") }
            }
            val showPlayer = { browse = null; collection = null; layout = MusicPlayerLayout.FULL }
            val browseStatus = if (connection == MusicConnection.OFFLINE)
                MusicBrowseStatus.UNAVAILABLE else MusicBrowseStatus.READY
            val browseMessage = if (connection == MusicConnection.OFFLINE)
                "Design preview · provider unavailable · no audio" else null
            val selectTrack: (MusicBrowseTrack) -> Unit = { track ->
              selected = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
              position = 0; playing = false; changedAt = SystemClock.elapsedRealtime()
              showPlayer()
            }
            val detail = collection
            if (detail != null) {
              MusicCollectionDetail(detail,
                  onBack = { collection = null }, onTrack = selectTrack,
                  onShowPlayer = showPlayer, modifier = Modifier.weight(1f),
                  status = browseStatus, statusMessage = browseMessage,
                  currentTrackId = item?.id,
                  onPlayCollection = {
                    it.tracks.firstOrNull()?.let(selectTrack)
                    playing = true
                  })
            } else if (browse == "library") {
              MusicLibraryPage(if (connection == MusicConnection.NOT_CONFIGURED) emptyList() else albums,
                  onBack = showPlayer, onCollection = { collection = it },
                  onShowPlayer = showPlayer, modifier = Modifier.weight(1f),
                  status = browseStatus, statusMessage = browseMessage,
                  browseState = libraryBrowseState)
            } else if (browse == "playlists") {
              MusicPlaylistsPage(if (connection == MusicConnection.NOT_CONFIGURED) emptyList() else playlists,
                  onBack = showPlayer, onCollection = { collection = it },
                  onShowPlayer = showPlayer, modifier = Modifier.weight(1f),
                  status = browseStatus, statusMessage = browseMessage,
                  browseState = playlistsBrowseState)
            } else {
            val playerModifier = if (layout == MusicPlayerLayout.COMPACT) Modifier.fillMaxWidth()
                else Modifier.weight(1f).fillMaxWidth()
            MusicPlayerContent(snapshot, layout, playerModifier,
                onDismiss = { finish() }, onLayoutChange = { layout = it },
                onAction = { action ->
                  message = "Silent design preview · sample music · no audio"
                  when (action) {
                    MusicPlayerUiAction.TogglePlayback -> {
                      position = snapshot.visiblePositionMs(SystemClock.elapsedRealtime()) ?: position
                      playing = !playing
                    }
                    MusicPlayerUiAction.Next -> {
                      if (tracks.isNotEmpty()) selected = (selected + 1) % tracks.size
                      position = 0
                    }
                    MusicPlayerUiAction.Previous -> {
                      if (tracks.isNotEmpty()) selected = (selected + tracks.size - 1) % tracks.size
                      position = 0
                    }
                    is MusicPlayerUiAction.SelectQueueItem -> {
                      selected = tracks.indexOfFirst { it.id == action.id }.coerceAtLeast(0)
                      position = 0
                      playing = false
                    }
                    is MusicPlayerUiAction.Seek -> position = action.positionMs
                    is MusicPlayerUiAction.SetRoomVolume -> {
                      previewVolumes = previewVolumes +
                          (action.roomId to action.percent.coerceIn(0, 100))
                    }
                    is MusicPlayerUiAction.SelectPlayer -> {
                      if (groupPreview) {
                        source = action.roomId
                        message = "Silent design preview · sample player selected · no audio"
                      } else message = "Design preview · provider controls are deferred · no audio"
                    }
                    MusicPlayerUiAction.BrowseLibrary -> { browse = "library"; collection = null }
                    else -> message = "Design preview · provider controls are deferred · no audio"
                  }
                  changedAt = SystemClock.elapsedRealtime()
                },
                photoContent = { modifier ->
                  if (photos != null) {
                    MusicPhotoHost(photos, modifier)
                    SideEffect {
                      realPhotoControllerIdentityForChecks = System.identityHashCode(photos.frame())
                    }
                  }
                  else Box(modifier.background(palette.surface)) {
                    Text("Photo layout preview", color = palette.text,
                        modifier = Modifier.padding(24.dp))
                  }
                })
            }
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    nightClock?.setActive(true)
    gallery?.setHostActive(true)
  }

  override fun onPause() {
    nightClock?.setActive(false)
    gallery?.setHostActive(false)
    super.onPause()
  }

  override fun onDestroy() {
    realPhotoControllerIdentityForChecks = 0
    galleryControls?.stop()
    nightClock?.setActive(false)
    gallery?.stop()
    super.onDestroy()
  }
}
