/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.MusicNowPlayingPresentation.MusicScreenState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Sizes follow music-v2 (1080×720 CSS mock) scaled to the Portal's ~1920×1280 dp.
private val ScreenPadding = 64.dp
private val MinTouch = 56.dp
private val ArtMax = 320.dp
private val ArtRadius = 20.dp
private val PlayButton = 136.dp
private val SideButton = 88.dp
private val CtaHeight = 88.dp
private val PaneRadius = 32.dp
private const val LEFT_WEIGHT = 0.45f
private const val RIGHT_WEIGHT = 0.55f
private const val TICK_MS = 1_000L
private const val DISABLED_ALPHA = 0.38f

/**
 * The music screen. Reads [NowPlayingHub]; commands go only through [MusicTransports].
 *
 * Split (PLAYING/PAUSED): native Now Playing on the left ~45%, [rightPane] (the embedded
 * YouTube Music page) on the right, or a quiet placeholder with one "Open YouTube Music"
 * button when [rightPane] is null. FIRST_RUN / NOTHING_PLAYING are full-width.
 *
 * The Close button is drawn over the top-right corner of the screen (and so over the
 * right pane's top-right ~[MinTouch]+16dp square); the pane should keep that corner clear.
 */
@Composable
fun MusicPlayerScreen(
    roomName: String,
    rightPane: (@Composable () -> Unit)?,
    onOpenYouTubeMusic: () -> Unit,
    onClose: () -> Unit,
    hasEverSeenSession: Boolean,
    modifier: Modifier = Modifier,
    /** The embedded page is open: idle states show it beside a short hint instead of full-width. */
    paneOpen: Boolean = false,
) {
  val np = rememberNowPlaying()
  val screen = MusicNowPlayingPresentation.screenState(np, hasEverSeenSession)
  val hideKeyboard = rememberMusicKeyboardPolicy()
  Box(modifier.fillMaxSize().background(ApprovedHomePalette.Background)
      // Any tap outside the embedded page (which consumes its own touches) closes the keyboard.
      .pointerInput(Unit) { detectTapGestures { hideKeyboard() } }) {
    when (screen) {
      MusicScreenState.FIRST_RUN, MusicScreenState.NOTHING_PLAYING ->
        if (paneOpen && rightPane != null) MusicSplitIdle(rightPane)
        else MusicStartState(firstRun = screen == MusicScreenState.FIRST_RUN, onOpenYouTubeMusic)
      MusicScreenState.PLAYING, MusicScreenState.PAUSED -> if (np != null) {
        MusicSplit(np, roomName, rightPane, onOpenYouTubeMusic)
      }
    }
    NowPlayingIconButton("Close music", NowPlayingGlyph.CLOSE, onClose,
        Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp))
  }
}

/**
 * While the music screen is shown the soft keyboard overlays it (ADJUST_NOTHING) instead of
 * panning the whole launcher up, which hid the header, tabs, close button and the Home dock; the
 * window's previous mode is restored on close. Returns an action that closes the keyboard and drops
 * the embedded page's input focus (its search box would otherwise keep the keyboard up).
 */
@Composable
private fun rememberMusicKeyboardPolicy(): () -> Unit {
  val view = androidx.compose.ui.platform.LocalView.current
  val hide = remember(view) {
    {
      YtMusicWebHost.clearInputFocus()
      val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
          as? android.view.inputmethod.InputMethodManager
      imm?.hideSoftInputFromWindow(view.windowToken, 0)
      Unit
    }
  }
  DisposableEffect(view) {
    val window = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
        .filterIsInstance<android.app.Activity>().firstOrNull()?.window
    val previous = window?.attributes?.softInputMode
    window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    onDispose {
      hide()
      if (window != null && previous != null) window.setSoftInputMode(previous)
    }
  }
  return hide
}

/** Subscribes to [NowPlayingHub], marshalling every update to the main thread. */
@Composable
private fun rememberNowPlaying(): NowPlayingState? {
  var state by remember { mutableStateOf(NowPlayingHub.current) }
  DisposableEffect(Unit) {
    val main = Handler(Looper.getMainLooper())
    var disposed = false
    val listener = NowPlayingHub.Listener { next ->
      if (Looper.myLooper() == main.looper) state = next
      else main.post { if (!disposed) state = next }
    }
    NowPlayingHub.addListener(listener) // replays current immediately
    onDispose {
      disposed = true
      NowPlayingHub.removeListener(listener)
      main.removeCallbacksAndMessages(null)
    }
  }
  return state
}

/** True while the host lifecycle is at least RESUMED (screen visible and in front). */
@Composable
private fun rememberResumed(): Boolean {
  val owner = LocalLifecycleOwner.current
  var resumed by remember(owner) {
    mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
  }
  DisposableEffect(owner) {
    val observer = LifecycleEventObserver { source, _ ->
      resumed = source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    owner.lifecycle.addObserver(observer)
    onDispose { owner.lifecycle.removeObserver(observer) }
  }
  return resumed
}

/** Elapsed-realtime clock that ticks once per second only while [running]. */
@Composable
private fun rememberTickingClock(running: Boolean, key: Any?): Long {
  val now by produceState(SystemClock.elapsedRealtime(), running, key) {
    value = SystemClock.elapsedRealtime()
    while (running) {
      delay(TICK_MS)
      value = SystemClock.elapsedRealtime()
    }
  }
  return now
}

@Composable
private fun MusicSplit(
    np: NowPlayingState,
    roomName: String,
    rightPane: (@Composable () -> Unit)?,
    onOpenYouTubeMusic: () -> Unit,
) {
  Row(Modifier.fillMaxSize()) {
    NowPlayingColumn(np, roomName, Modifier.weight(LEFT_WEIGHT).fillMaxHeight())
    Box(Modifier.weight(RIGHT_WEIGHT).fillMaxHeight().padding(24.dp)) {
      val shape = RoundedCornerShape(PaneRadius)
      Box(Modifier.fillMaxSize().clip(shape).background(ApprovedHomePalette.Card)) {
        if (rightPane != null) rightPane() else RightPanePlaceholder(onOpenYouTubeMusic)
      }
    }
  }
}

/** Page open, nothing playing yet: the page on the right, one quiet instruction on the left. */
@Composable
private fun MusicSplitIdle(rightPane: @Composable () -> Unit) {
  Row(Modifier.fillMaxSize()) {
    Column(Modifier.weight(LEFT_WEIGHT).fillMaxHeight().padding(horizontal = 56.dp),
        verticalArrangement = Arrangement.Center) {
      Text("Choose something to play", color = ApprovedHomePalette.Text, fontSize = 40.sp,
          lineHeight = 48.sp, fontWeight = FontWeight.Medium)
      Text("Sign in on the right if YouTube Music asks. What plays shows up here and on the photo frame.",
          color = ApprovedHomePalette.Secondary, fontSize = 24.sp, lineHeight = 34.sp,
          modifier = Modifier.padding(top = 16.dp))
    }
    Box(Modifier.weight(RIGHT_WEIGHT).fillMaxHeight().padding(24.dp)) {
      Box(Modifier.fillMaxSize().clip(RoundedCornerShape(PaneRadius)).background(ApprovedHomePalette.Card)) {
        rightPane()
      }
    }
  }
}

@Composable
private fun RightPanePlaceholder(onOpenYouTubeMusic: () -> Unit) {
  Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally) {
    Text("Queue, library and search live in YouTube Music.", color = ApprovedHomePalette.Secondary,
        fontSize = 28.sp, lineHeight = 38.sp)
    Spacer(Modifier.height(32.dp))
    OpenYouTubeMusicButton(onOpenYouTubeMusic)
  }
}

@Composable
private fun NowPlayingColumn(np: NowPlayingState, roomName: String, modifier: Modifier) {
  val playing = np.state == PlaybackState.PLAYING
  val transport = MusicTransports.forState(np)
  BoxWithConstraints(modifier.padding(start = ScreenPadding, end = 24.dp, top = 40.dp,
      bottom = 40.dp)) {
    // Long two-line titles must still leave room for transport + volume on 1280 dp.
    val art = minOf(ArtMax, maxHeight * 0.26f, maxWidth)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
      StatusLine(playing, roomName)
      AlbumArt(np, art)
      TrackText(np)
      ProgressRail(np, playing, transport)
      TransportRow(playing, transport)
      VolumeRow()
    }
  }
}

@Composable
private fun StatusLine(playing: Boolean, roomName: String) {
  val text = MusicNowPlayingPresentation.statusLine(playing, roomName)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Text(text, color = ApprovedHomePalette.Secondary, fontSize = 28.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    Spacer(Modifier.width(16.dp))
    val dot = if (playing) ApprovedHomePalette.Accent else ApprovedHomePalette.Border
    Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
  }
}

@Composable
private fun AlbumArt(np: NowPlayingState, size: Dp) {
  val context = LocalContext.current
  var urlArt by remember(np.artUrl, np.artBitmap) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(np.artUrl, np.artBitmap) {
    if (np.artBitmap == null && np.artUrl.isNotBlank()) {
      MediaArt.deliverUri(context, np.artUrl) { urlArt = it }
    }
  }
  val cover = (np.artBitmap ?: urlArt)?.takeIf { !it.isRecycled }
  val shape = RoundedCornerShape(ArtRadius)
  Box(Modifier.size(size).clip(shape).background(ApprovedHomePalette.Chrome),
      contentAlignment = Alignment.Center) {
    if (cover != null) {
      Image(cover.asImageBitmap(), contentDescription = "Album art",
          contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
      NowPlayingGlyphCanvas(NowPlayingGlyph.NOTE, ApprovedHomePalette.Secondary.copy(alpha = 0.5f),
          Modifier.size(size * 0.32f))
    }
  }
}

@Composable
private fun TrackText(np: NowPlayingState) {
  Column {
    Text(MusicNowPlayingPresentation.displayTitle(np.title), color = ApprovedHomePalette.Text,
        fontSize = 64.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
        overflow = TextOverflow.Ellipsis)
    if (np.artist.isNotBlank()) {
      Spacer(Modifier.height(8.dp))
      Text(np.artist.trim(), color = ApprovedHomePalette.Secondary, fontSize = 40.sp,
          maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun ProgressRail(np: NowPlayingState, playing: Boolean, transport: MusicTransport?) {
  val p = MusicNowPlayingPresentation
  val now = rememberTickingClock(running = playing && rememberResumed(),
      key = np.positionUpdatedElapsedMs to np.positionMs)
  // A drag holds its own value; a released seek holds until the session reports again.
  var dragFraction by remember(np.mediaId, np.title) { mutableStateOf<Float?>(null) }
  var pendingSeekMs by remember(np.positionUpdatedElapsedMs, np.positionMs) {
    mutableStateOf<Long?>(null)
  }
  val live = pendingSeekMs ?: p.livePositionMs(np, now)
  val fraction = dragFraction ?: p.progressFraction(live, np.durationMs)
  val shownMs = dragFraction?.let { p.seekTargetMs(it, np.durationMs) } ?: live
  val seekable = transport != null && p.showSeekKnob(np.canSeek, np.durationMs)
  Column {
    if (seekable) {
      Slider(value = fraction, onValueChange = { dragFraction = it },
          onValueChangeFinished = {
            dragFraction?.let { f ->
              val target = p.seekTargetMs(f, np.durationMs)
              pendingSeekMs = target
              transport?.seekTo(target)
            }
            dragFraction = null
          },
          colors = nowPlayingSliderColors(),
          modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)
              .semantics { contentDescription = "Position" })
    } else {
      PlainRail(fraction)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(p.formatTime(shownMs), color = ApprovedHomePalette.Secondary, fontSize = 26.sp)
      if (np.durationMs > 0L) {
        Text(p.formatTime(np.durationMs), color = ApprovedHomePalette.Secondary, fontSize = 26.sp)
      }
    }
  }
}

/** Seek unavailable: a plain indicator with no knob, so nothing invites a drag. */
@Composable
private fun PlainRail(fraction: Float) {
  Box(Modifier.fillMaxWidth().height(MinTouch), contentAlignment = Alignment.CenterStart) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
        .background(ApprovedHomePalette.Border))
    Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(CircleShape)
        .background(ApprovedHomePalette.Accent))
  }
}

@Composable
private fun nowPlayingSliderColors() = SliderDefaults.colors(
    thumbColor = ApprovedHomePalette.Text,
    activeTrackColor = ApprovedHomePalette.Accent,
    inactiveTrackColor = ApprovedHomePalette.Border,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

@Composable
private fun TransportRow(playing: Boolean, transport: MusicTransport?) {
  val enabled = transport != null
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically) {
    NowPlayingIconButton("Previous", NowPlayingGlyph.PREVIOUS, { transport?.previous() },
        size = SideButton, enabled = enabled)
    PlayPauseButton(playing, enabled) {
      if (playing) transport?.pause() else transport?.play()
    }
    NowPlayingIconButton("Next", NowPlayingGlyph.NEXT, { transport?.next() },
        size = SideButton, enabled = enabled)
  }
}

@Composable
private fun PlayPauseButton(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
  val label = if (playing) "Pause" else "Play"
  val alpha = if (enabled) 1f else DISABLED_ALPHA
  Box(Modifier.size(PlayButton).clip(CircleShape)
      .background(ApprovedHomePalette.Text.copy(alpha = alpha))
      .tvFocusable(CircleShape, ringColor = ApprovedHomePalette.Accent, enabled = enabled,
          onClick = onClick)
      .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
    NowPlayingGlyphCanvas(if (playing) NowPlayingGlyph.PAUSE else NowPlayingGlyph.PLAY,
        ApprovedHomePalette.Background, Modifier.size(PlayButton * 0.36f))
  }
}

@Composable
private fun VolumeRow() {
  val context = LocalContext.current
  val volume = remember(context) { MusicVolume(context) }
  var level by remember(volume) { mutableStateOf(volume.read()) }
  DisposableEffect(volume) {
    val handle = volume.observe { level = it }
    level = volume.read()
    onDispose { handle.close() }
  }
  val max = level.maxStep
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    NowPlayingIconButton("Volume down", NowPlayingGlyph.SPEAKER_LOW,
        { level = volume.setStep(level.step - 1) }, enabled = max > 0)
    Slider(value = level.step.toFloat(), valueRange = 0f..max.coerceAtLeast(1).toFloat(),
        steps = (max - 1).coerceAtLeast(0), enabled = max > 0,
        onValueChange = { v ->
          val step = v.roundToInt()
          if (step != level.step) level = volume.setStep(step)
        },
        colors = nowPlayingSliderColors(),
        modifier = Modifier.weight(1f).heightIn(min = MinTouch).padding(horizontal = 8.dp)
            .semantics { contentDescription = "Volume" })
    NowPlayingIconButton("Volume up", NowPlayingGlyph.SPEAKER_HIGH,
        { level = volume.setStep(level.step + 1) }, enabled = max > 0)
  }
}

/** FIRST_RUN (two steps) or NOTHING_PLAYING (one line), each with one hand-off button. */
@Composable
private fun MusicStartState(firstRun: Boolean, onOpenYouTubeMusic: () -> Unit) {
  Row(Modifier.fillMaxSize().padding(horizontal = ScreenPadding),
      verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(LEFT_WEIGHT), contentAlignment = Alignment.Center) { ArtStack() }
    Column(Modifier.weight(RIGHT_WEIGHT).padding(start = 48.dp).widthIn(max = 900.dp)) {
      val title = if (firstRun) "Music plays from YouTube Music" else "Nothing is playing"
      val body = if (firstRun) {
        "It runs on this Portal. Whatever it plays shows up here and on the photo frame."
      } else "Pick something in YouTube Music. It will appear here as soon as it starts."
      Text(title, color = ApprovedHomePalette.Text, fontSize = 72.sp, lineHeight = 80.sp,
          fontWeight = FontWeight.SemiBold)
      Spacer(Modifier.height(28.dp))
      Text(body, color = ApprovedHomePalette.Secondary, fontSize = 32.sp, lineHeight = 46.sp)
      if (firstRun) {
        Spacer(Modifier.height(40.dp))
        StartStep(1, "Sign in with your Google account",
            "Once. Your password stays with Google.")
        Spacer(Modifier.height(28.dp))
        StartStep(2, "Choose something to play", "Then come back, or leave it. The frame shows it.")
      }
      Spacer(Modifier.height(48.dp))
      OpenYouTubeMusicButton(onOpenYouTubeMusic)
    }
  }
}

@Composable
private fun StartStep(number: Int, title: String, detail: String) {
  Row {
    Text("$number", color = ApprovedHomePalette.Accent, fontSize = 30.sp,
        modifier = Modifier.width(64.dp))
    Column {
      Text(title, color = ApprovedHomePalette.Text, fontSize = 34.sp)
      Spacer(Modifier.height(6.dp))
      Text(detail, color = ApprovedHomePalette.Secondary, fontSize = 26.sp, lineHeight = 36.sp)
    }
  }
}

/** Decorative stack of three quiet cards (no fake album art). */
@Composable
private fun ArtStack() {
  Box(Modifier.size(520.dp), contentAlignment = Alignment.Center) {
    ArtCard(Modifier.offset((-90).dp, 30.dp).rotate(-6f), ApprovedHomePalette.Chrome)
    ArtCard(Modifier.offset(70.dp, (-60).dp).rotate(4f), ApprovedHomePalette.Border)
    ArtCard(Modifier.offset(0.dp, 40.dp), ApprovedHomePalette.Card) {
      NowPlayingGlyphCanvas(NowPlayingGlyph.NOTE, ApprovedHomePalette.Secondary.copy(alpha = 0.5f),
          Modifier.size(96.dp))
    }
  }
}

@Composable
private fun ArtCard(modifier: Modifier, color: Color, content: @Composable () -> Unit = {}) {
  Box(modifier.size(300.dp).clip(RoundedCornerShape(28.dp)).background(color),
      contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun OpenYouTubeMusicButton(onClick: () -> Unit) {
  val shape = RoundedCornerShape(CtaHeight / 2)
  Row(Modifier.heightIn(min = CtaHeight).clip(shape).background(ApprovedHomePalette.Text)
      .tvFocusable(shape, ringColor = ApprovedHomePalette.Accent, onClick = onClick)
      .padding(horizontal = 44.dp), verticalAlignment = Alignment.CenterVertically) {
    NowPlayingGlyphCanvas(NowPlayingGlyph.EXTERNAL, ApprovedHomePalette.Background, Modifier.size(36.dp))
    Spacer(Modifier.width(20.dp))
    Text("Open YouTube Music", color = ApprovedHomePalette.Background, fontSize = 34.sp,
        fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun NowPlayingIconButton(
    label: String,
    glyph: NowPlayingGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = MinTouch + 16.dp,
    enabled: Boolean = true,
) {
  val tint = ApprovedHomePalette.Text.copy(alpha = if (enabled) 1f else DISABLED_ALPHA)
  Box(modifier.size(size).clip(CircleShape)
      .tvFocusable(CircleShape, ringColor = ApprovedHomePalette.Accent, enabled = enabled,
          onClick = onClick)
      .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
    NowPlayingGlyphCanvas(glyph, tint, Modifier.size(size * 0.45f))
  }
}

private enum class NowPlayingGlyph { CLOSE, PLAY, PAUSE, PREVIOUS, NEXT, SPEAKER_LOW, SPEAKER_HIGH,
  NOTE, EXTERNAL }

/** Line/fill glyphs on a 24-unit grid, matching the mock's SVGs (no icon dependency). */
@Composable
private fun NowPlayingGlyphCanvas(glyph: NowPlayingGlyph, color: Color, modifier: Modifier) {
  Canvas(modifier) {
    val u = size.minDimension / 24f
    when (glyph) {
      NowPlayingGlyph.CLOSE -> {
        line(color, u, 6f, 6f, 18f, 18f); line(color, u, 18f, 6f, 6f, 18f)
      }
      NowPlayingGlyph.PLAY -> fill(color, u, 7f to 4f, 20f to 12f, 7f to 20f)
      NowPlayingGlyph.PAUSE -> {
        fill(color, u, 6f to 4f, 10f to 4f, 10f to 20f, 6f to 20f)
        fill(color, u, 14f to 4f, 18f to 4f, 18f to 20f, 14f to 20f)
      }
      NowPlayingGlyph.PREVIOUS -> {
        fill(color, u, 7f to 5f, 9f to 5f, 9f to 19f, 7f to 19f)
        fill(color, u, 20f to 5f, 20f to 19f, 10f to 12f)
      }
      NowPlayingGlyph.NEXT -> {
        fill(color, u, 15f to 5f, 17f to 5f, 17f to 19f, 15f to 19f)
        fill(color, u, 4f to 5f, 14f to 12f, 4f to 19f)
      }
      NowPlayingGlyph.SPEAKER_LOW -> speaker(color, u)
      NowPlayingGlyph.SPEAKER_HIGH -> {
        speaker(color, u)
        drawArc(color, -50f, 100f, false, Offset(10f * u, 6f * u),
            androidx.compose.ui.geometry.Size(8f * u, 12f * u), style = stroke(u))
        drawArc(color, -55f, 110f, false, Offset(9f * u, 2f * u),
            androidx.compose.ui.geometry.Size(13f * u, 20f * u), style = stroke(u))
      }
      NowPlayingGlyph.NOTE -> {
        drawCircle(color, 3f * u, Offset(8f * u, 18f * u))
        line(color, u, 11f, 18f, 11f, 5f); line(color, u, 11f, 5f, 19f, 3f)
        line(color, u, 19f, 3f, 19f, 15f); drawCircle(color, 3f * u, Offset(16f * u, 15f * u))
      }
      NowPlayingGlyph.EXTERNAL -> {
        line(color, u, 14f, 4f, 20f, 4f); line(color, u, 20f, 4f, 20f, 10f)
        line(color, u, 20f, 4f, 11f, 13f)
        val p = Path().apply {
          moveTo(18f * u, 14f * u); lineTo(18f * u, 20f * u); lineTo(4f * u, 20f * u)
          lineTo(4f * u, 6f * u); lineTo(10f * u, 6f * u)
        }
        drawPath(p, color, style = stroke(u))
      }
    }
  }
}

private fun stroke(u: Float) = Stroke(width = 1.8f * u, cap = StrokeCap.Round)

private fun DrawScope.line(c: Color, u: Float, x1: Float, y1: Float, x2: Float, y2: Float) =
    drawLine(c, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), 1.8f * u, StrokeCap.Round)

private fun DrawScope.fill(c: Color, u: Float, vararg pts: Pair<Float, Float>) {
  val path = Path().apply {
    pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
    close()
  }
  drawPath(path, c)
}

private fun DrawScope.speaker(c: Color, u: Float) {
  val p = Path().apply {
    moveTo(4f * u, 9f * u); lineTo(8f * u, 9f * u); lineTo(13f * u, 5f * u)
    lineTo(13f * u, 19f * u); lineTo(8f * u, 15f * u); lineTo(4f * u, 15f * u); close()
  }
  drawPath(p, c, style = stroke(u))
}
