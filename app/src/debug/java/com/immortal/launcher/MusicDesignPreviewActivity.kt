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
  internal var seekCommandCountForChecks = 0
    private set
  internal var volumeCommandCountForChecks = 0
    private set
  private var nightClock: QuietNightClockView? = null
  private var gallery: SlideshowStage? = null
  private var galleryControls: SlideshowViewerControls? = null
  private var lyricsFixture by mutableStateOf<MusicPlayerSnapshot?>(null)
  private var lyricsFixtureActive by mutableStateOf(true)
  private var viewportFixture by mutableStateOf<MusicPlayerSnapshot?>(null)
  private var viewportLayout by mutableStateOf(MusicPlayerLayout.FULL)
  private var seekBindingFixture by mutableStateOf<MusicPlayerSnapshot?>(null)
  private val seekBindingReceipts = org.json.JSONArray()
  private var volumeBindingFixture by mutableStateOf<MusicPlayerSnapshot?>(null)
  private val volumeBindingReceipts = org.json.JSONArray()
  private val generatedLyrics = (0 until 80).map {
    MusicLyricLine(it * 1_000L, "Generated lyric line $it")
  }

  fun updateMusicSeekBinding(player: String, queue: String, revision: Long, title: String,
      duration: Long = 240_000) {
    require(intent.getStringExtra("screen") == "seekbinding")
    require(player.startsWith("ma:generated-") && queue.startsWith("generated-"))
    seekBindingFixture = MusicPlayerSnapshot(
        connection = MusicConnection.READY, sourceId = "ma:generated-seek",
        track = MusicTrack("generated-seek-track", "ma:generated-seek", title,
            durationMs = duration), playback = PlaybackState.PAUSED,
        positionMs = 30_000, positionAtElapsedMs = SystemClock.elapsedRealtime(),
        capabilities = MusicCapabilities(seek = true, queue = true),
        queueId = queue, controlPlayerId = player, presentationRevision = revision)
  }

  fun musicSeekBindingReceipts(): String = seekBindingReceipts.toString()

  /** Generated room observations; no source controller, audio or retained-photo host. */
  fun updateMusicVolumeBinding(source: String, room: String, revision: Long, title: String,
      volume: Int) {
    require(intent.getStringExtra("screen") == "volumebinding")
    require(source.startsWith("ma:generated-") && room.startsWith("ma:generated-"))
    require(volume in 0..100)
    volumeBindingFixture = MusicPlayerSnapshot(
        connection = MusicConnection.READY, sourceId = source,
        track = MusicTrack("generated-volume-track", source, title, durationMs = 240_000),
        playback = PlaybackState.PAUSED, capabilities = MusicCapabilities(rooms = true, volume = true),
        controlPlayerId = "ma:generated-control", presentationRevision = revision,
        rooms = listOf(MusicRoom(room, "Generated speaker", true, volume,
            volumeControllable = true)))
  }

  fun musicVolumeBindingReceipts(): String = volumeBindingReceipts.toString()

  /** Generated silent lists; updates retain the same production player composition. */
  fun updateMusicViewportBinding(source: String, queue: String, revision: Long) {
    require(intent.getStringExtra("screen") == "viewport")
    require(source.startsWith("ma:generated-") && queue.startsWith("generated-"))
    viewportFixture = MusicPlayerSnapshot(
        connection = MusicConnection.READY, sourceId = source,
        track = MusicTrack("generated-track", source, "Generated paused track",
            durationMs = 240_000), playback = PlaybackState.PAUSED,
        capabilities = MusicCapabilities(queue = true, rooms = true),
        queue = (0 until 80).map { MusicQueueItem("generated-$it", "Generated queue row $it") },
        rooms = (0 until 80).map {
          MusicRoom("ma:generated-speaker-$it", "Generated player row $it", available = true)
        },
        queueId = queue, presentationRevision = revision,
    )
  }

  fun updateMusicViewportLayout(layout: String) {
    require(intent.getStringExtra("screen") == "viewport")
    viewportLayout = MusicPlayerLayout.valueOf(layout)
  }

  fun updateMusicViewportMetadata() {
    require(intent.getStringExtra("screen") == "viewport")
    viewportFixture = viewportFixture?.let {
      it.copy(track = it.track?.copy(title = "Updated generated paused track"), positionMs = 12_000)
    }
  }

  /** Public debug fixture update; preserves the production player's existing composition. */
  fun updateMusicLyricsFixture(index: Int, trackId: String, sourceId: String) {
    require(intent.getStringExtra("screen") == "lyrics")
    require(index in 0..79 && trackId.startsWith("generated-") && sourceId.startsWith("fixture:"))
    lyricsFixture = MusicPlayerSnapshot(
        connection = MusicConnection.READY, sourceId = sourceId,
        track = MusicTrack(trackId, sourceId, "Generated paused lyrics track",
            "Generated artist", durationMs = 80_000L),
        playback = PlaybackState.PAUSED, positionMs = index * 1_000L,
        positionAtElapsedMs = SystemClock.elapsedRealtime(),
        capabilities = MusicCapabilities(queue = true, lyrics = true),
        queue = listOf(MusicQueueItem(trackId, "Generated paused lyrics track")),
        queueId = "generated-lyrics-queue",
        lyrics = generatedLyrics,
    )
  }

  /** Reuses the launcher's real visibility local without replacing the player composition. */
  fun updateMusicLyricsVisibility(active: Boolean) {
    require(intent.getStringExtra("screen") == "lyrics")
    lyricsFixtureActive = active
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    if (intent.getStringExtra("screen") == "volumebinding") {
      val token = intent.getStringExtra("owned_probe")
      if (token != null && token == cancelledSeekProbe) { finish(); return }
      val deadline = intent.getLongExtra("owned_deadline_elapsed_ms", 0)
      if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) { finish(); return }
      updateMusicVolumeBinding("ma:generated-source-a", "ma:generated-room-a", 1,
          "Generated paused track", 35)
      setContent {
        val palette = if (intent.getStringExtra("theme") == "light")
          ApprovedLightPalette else ApprovedDarkPalette
        CompositionLocalProvider(LocalApprovedPalette provides palette) {
          MaterialTheme(colorScheme = approvedColorScheme(palette === ApprovedDarkPalette)) {
            volumeBindingFixture?.let { rendered ->
              MusicPlayerContent(rendered, MusicPlayerLayout.FULL, Modifier.fillMaxSize(),
                  onDismiss = { finish() }, onLayoutChange = {}, onAction = { action ->
                    if (action is MusicPlayerUiAction.SetRoomVolume) {
                      val current = checkNotNull(volumeBindingFixture)
                      volumeBindingReceipts.put(org.json.JSONObject()
                          .put("percent", action.percent).put("room", action.roomId)
                          .put("renderedRevision", rendered.presentationRevision)
                          .put("currentRevision", current.presentationRevision)
                          .put("accepted", musicRenderedActionIsCurrent(
                              rendered, current, current.presentationRevision, action)))
                    }
                  })
            }
          }
        }
      }
      return
    }
    if (intent.getStringExtra("screen") == "seekbinding") {
      val token = intent.getStringExtra("owned_probe")
      if (token != null && token == cancelledSeekProbe) { finish(); return }
      val deadline = intent.getLongExtra("owned_deadline_elapsed_ms", 0)
      if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) { finish(); return }
      updateMusicSeekBinding("ma:generated-player-a", "generated-queue-a", 1, "Generated paused track")
      setContent {
        val palette = if (intent.getStringExtra("theme") == "light")
          ApprovedLightPalette else ApprovedDarkPalette
        CompositionLocalProvider(LocalApprovedPalette provides palette) {
          MaterialTheme(colorScheme = approvedColorScheme(palette === ApprovedDarkPalette)) {
            seekBindingFixture?.let { rendered ->
              MusicPlayerContent(rendered, MusicPlayerLayout.FULL, Modifier.fillMaxSize(),
                  onDismiss = { finish() }, onLayoutChange = {}, onAction = { action ->
                    if (action is MusicPlayerUiAction.Seek) {
                      val current = checkNotNull(seekBindingFixture)
                      seekBindingReceipts.put(org.json.JSONObject()
                          .put("positionMs", action.positionMs)
                          .put("renderedPlayer", rendered.controlPlayerId)
                          .put("currentPlayer", current.controlPlayerId)
                          .put("renderedQueue", rendered.queueId)
                          .put("currentQueue", current.queueId)
                          .put("accepted", musicRenderedActionIsCurrent(
                              rendered, current, current.presentationRevision, action)))
                    }
                  })
            }
          }
        }
      }
      return
    }
    if (intent.getStringExtra("screen") == "viewport") {
      val deadline = intent.getLongExtra("owned_deadline_elapsed_ms", 0)
      if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) {
        finish()
        return
      }
      updateMusicViewportBinding("ma:generated-viewport", "generated-queue-a", 1)
      setContent {
        val light = intent.getStringExtra("theme") == "light"
        val palette = if (light) ApprovedLightPalette else ApprovedDarkPalette
        CompositionLocalProvider(LocalApprovedPalette provides palette) {
          MaterialTheme(colorScheme = approvedColorScheme(!light)) {
            viewportFixture?.let { snapshot ->
              MusicPlayerContent(snapshot, viewportLayout, Modifier.fillMaxSize(),
                  onDismiss = { finish() }, onLayoutChange = { viewportLayout = it },
                  onAction = {}, photoContent = {
                    Box(it.background(palette.surface)) {
                      Text("Generated photo placeholder", color = palette.secondary)
                    }
                  })
            }
          }
        }
      }
      return
    }
    if (intent.getStringExtra("screen") == "lyrics") {
      val ownedDeadline = intent.getLongExtra("owned_deadline_elapsed_ms", 0)
      if (ownedDeadline > 0 && SystemClock.elapsedRealtime() >= ownedDeadline) {
        finish()
        return
      }
      updateMusicLyricsFixture(45, "generated-lyrics-track-a", "fixture:generated-lyrics-a")
      setContent {
        val light = intent.getStringExtra("theme") == "light"
        val palette = if (light) ApprovedLightPalette else ApprovedDarkPalette
        CompositionLocalProvider(LocalApprovedPalette provides palette,
            LocalApprovedLauncherPageActive provides lyricsFixtureActive) {
          MaterialTheme(colorScheme = approvedColorScheme(!light)) {
            Column(Modifier.fillMaxSize().background(palette.background)) {
              Text("Generated lyrics · paused · ${if (light) "light" else "dark"}",
                  color = palette.secondary,
                  modifier = Modifier.padding(8.dp))
              lyricsFixture?.let { snapshot ->
                MusicPlayerContent(snapshot, MusicPlayerLayout.FULL,
                    Modifier.weight(1f).fillMaxWidth(), onDismiss = { finish() },
                    onLayoutChange = {}, onAction = {})
              }
            }
          }
        }
      }
      return
    }
    if (intent.getStringExtra("screen") == "repeated-items") {
      repeatedItemsContent(intent.getStringExtra("list") ?: "tracks")
      return
    }
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
                  val actionAt = SystemClock.elapsedRealtime()
                  // Preserve the advancing timeline before changing its anchor. Seek
                  // and track changes below intentionally replace this position.
                  position = snapshot.visiblePositionMs(actionAt) ?: position
                  when (action) {
                    MusicPlayerUiAction.TogglePlayback -> {
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
                    is MusicPlayerUiAction.Seek -> {
                      seekCommandCountForChecks++
                      position = action.positionMs
                    }
                    is MusicPlayerUiAction.SetRoomVolume -> {
                      volumeCommandCountForChecks++
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
                  changedAt = actionAt
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

  /** Generated RAM metadata only. No artwork URIs, controller, commands or running timeline. */
  private fun repeatedItemsContent(list: String) {
    require(list in setOf("tracks", "collections", "queue", "rooms"))
    fun title(index: Int) = when (index) {
      0 -> "Repeat one"
      1 -> "Repeat two"
      else -> "Generated row ${index + 1}"
    }
    fun id(index: Int) = if (index < 2) "generated-repeat" else "generated-$index"
    val tracks = (0 until 66).map {
      MusicBrowseTrack(id(it), title(it), "Generated artist", 180_000L)
    }
    val collections = (0 until 66).map {
      MusicBrowseCollection(id(it), title(it), "Generated collection")
    }
    val queue = (0 until 66).map {
      MusicQueueItem(id(it), title(it), "Generated artist", 180_000L)
    }
    val rooms = (0 until 66).map { MusicRoom(id(it), title(it), available = true) }
    val source = "fixture:generated-repeated-items"
    val snapshot = MusicPlayerSnapshot(
        connection = MusicConnection.READY, sourceId = source,
        track = MusicTrack("generated-current", source, "Generated paused track",
            "Generated artist", durationMs = 180_000L),
        playback = PlaybackState.PAUSED,
        capabilities = MusicCapabilities(queue = true, rooms = true),
        queue = if (list == "queue") queue else listOf(
            MusicQueueItem("generated-single", "Generated paused track")),
        queueId = "generated-queue",
        rooms = if (list == "rooms") rooms else emptyList(),
    )
    setContent {
      CompositionLocalProvider(LocalApprovedPalette provides ApprovedDarkPalette) {
        MaterialTheme(colorScheme = approvedColorScheme(true)) {
          Column(Modifier.fillMaxSize().background(ApprovedDarkPalette.background)) {
            Text("Generated repeated items · $list · paused",
                color = ApprovedDarkPalette.secondary, modifier = Modifier.padding(8.dp))
            when (list) {
              "tracks" -> MusicCollectionDetail(
                  MusicBrowseCollection("generated-detail", "Generated tracks",
                      tracks = tracks, kind = MusicBrowseCollectionKind.PLAYLIST),
                  onBack = {}, onTrack = {}, onShowPlayer = {}, modifier = Modifier.weight(1f))
              "collections" -> MusicLibraryPage(collections,
                  onBack = {}, onCollection = {}, onShowPlayer = {}, modifier = Modifier.weight(1f))
              "queue" -> MusicQueue(snapshot, Modifier.weight(1f).fillMaxWidth(), onAction = {})
              "rooms" -> MusicRooms(snapshot, Modifier.weight(1f).fillMaxWidth(), onAction = {})
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

  companion object {
    private var cancelledSeekProbe: String? = null
    @JvmStatic fun cancelOwnedSeekProbe(token: String) { cancelledSeekProbe = token }
  }
}
