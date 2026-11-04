/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import org.json.JSONObject

private const val SHERPA_VOICE_PARAM = "sherpa_voice_name"

/** Layout-driven reservations, with no timer and no callback ownership after close. */
internal class GalleryOverlayLayoutObserver(
    private val root: View, private val refresh: () -> Unit,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
  private var active = false
  private var closed = false
  private var tree: ViewTreeObserver? = null

  init { root.addOnAttachStateChangeListener(this) }

  fun setActive(value: Boolean) {
    if (closed || active == value) return
    active = value
    if (value) attach() else detach()
  }

  private fun attach() {
    if (closed || !active || !root.isAttachedToWindow) return
    val next = root.viewTreeObserver
    if (!next.isAlive) return
    if (tree !== next) {
      detach()
      tree = next
      next.addOnGlobalLayoutListener(this)
    }
    // Bounds may have changed while the host was suspended without another layout on resume.
    onGlobalLayout()
  }

  private fun detach() {
    tree?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
    tree = null
  }

  override fun onGlobalLayout() {
    if (!closed && active && tree?.isAlive == true && root.isAttachedToWindow) refresh()
  }
  override fun onViewAttachedToWindow(v: View) { attach() }
  override fun onViewDetachedFromWindow(v: View) { detach() }

  fun close() {
    if (closed) return
    closed = true
    active = false
    detach()
    root.removeOnAttachStateChangeListener(this)
  }
}

/**
 * The photo-frame UI + update logic, decoupled from any host. Used by both
 * [PhotoDreamService] (the real screensaver) and [PhotoFramePreviewActivity]
 * (an in-app preview you can launch on demand). Reproduces the stock Portal
 * idle screen: full-screen rotating media with a clock / battery / date /
 * weather cluster bottom-left.
 *
 * Source is configurable via [ScreensaverConfig]:
 *  - **default** — the built-in keyless photo feed. This is also the fallback whenever
 *    any other source is unset, empty, or unreachable (e.g. a USB drive was unplugged,
 *    a shared album was unshared). It walks an ordered chain — Unsplash (if a key is
 *    supplied) → Lorem Picsum → Wikimedia Commons featured landscapes → CC0 photos
 *    bundled in the APK — showing the first image it can fetch, so the frame is never
 *    blank even with no network or a third-party outage. See [fetchWebPhoto].
 *  - **folder** — photos and videos from a folder the user picked (internal, SD, or
 *    a USB-C drive), read through the Storage Access Framework.
 *  - **url** — a public share link to an iCloud Shared Album or a Google Photos
 *    shared album. Fetched once on start; the screensaver rotates through the
 *    returned direct image URLs and silently falls back to the default feed if the
 *    album can't be resolved.
 *
 * Weather is Open-Meteo + IP geolocation (both keyless). All network is best-effort.
 */
class PhotoFrameController(
    private val context: Context,
    /** Show the welcome-back overlay when [start] is called (set true for presence-triggered starts). */
    val showWelcome: Boolean = false,
    private val unsplashKey: String = "",
    private val unsplashQuery: String = "nature,landscape,scenic",
    private val weatherRefreshMs: Long = 30 * 60_000L,
    private val calendarRefreshMs: Long = 30 * 60_000L,
    /** Deliberate Photos viewer; Dream/continuation retains tap-to-dismiss. */
    var interactivePreview: Boolean = false,
    /** In-page music photos: no clock/calendar/voice/soundscape or dream handoff. */
    private val photosOnly: Boolean = false,
    /** Scheduled night clock: don't start a photo, calendar, weather or audio pipeline at all. */
    private val nightClockOnly: Boolean = false,
) {
  private val io = Executors.newSingleThreadExecutor()
  private val sourceDiscoveryDelegate = lazy { SlideshowSourceDiscovery() }
  private val sourceDiscovery get() = sourceDiscoveryDelegate.value
  // Decode-once screen-sized copies of local photos for the engine (see [EngineScaledCache]).
  private val scaledCache = EngineScaledCache(java.io.File(context.cacheDir, "ss_scaled"))
  // A separate single-thread executor for caption work (EXIF read + the reverse-geocode
  // network call), so an 8s geocode lookup can never stall the image-decode pipeline on [io].
  private val metaIo = Executors.newSingleThreadExecutor()
  private val ui = PausableFrameCallbacks()

  private lateinit var photo: ImageView
  // Second photo layer for crossfade transitions. Sits behind [photo] in the z-order;
  // during a transition the incoming bitmap is loaded here, Ken Burns starts on it, and
  // the two layers swap visibility. After the crossfade completes the roles flip so the
  // next transition always writes the new image into whichever layer is currently behind.
  private lateinit var photoBack: ImageView
  // True when [photoBack] is currently the front (visible) layer and [photo] is the back.
  private var photoBackIsFront = false
  private lateinit var nativeStage: SlideshowStage
  private var galleryOverlayObserver: GalleryOverlayLayoutObserver? = null
  @Volatile private var legacyPhotoLayoutBounds = "{}"
  private val pixelBudget = SlideshowPixelBudget()
  private val photoPause = SlideshowPhotoPause<List<SlideshowPhoto>>(
      onHeldChanged = { photos ->
        pixelBudget.setOwner("userPause", photos.orEmpty().map {
          SlideshowPixelBudget.Entry(it.bitmap, it.bitmap.allocationByteCount.toLong())
        })
      },
      onDiscard = { photos -> photos.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() } })
  private val nativeLoaderDelegate = lazy {
    SlideshowLoader(context, pixelBudget, onCancelTransports = { cancelPhotoTransports() }, ui = ui)
  }
  private val nativeLoader get() = nativeLoaderDelegate.value
  private var nativeFilmstrip: FilmstripEngine? = null
  private var hybridFilmstrip: HybridFilmstripHost? = null
  private data class NativeFilmstripInput(val urls: List<String>, val dims: Map<String, Pair<Int, Int>>,
      val headers: Map<String, String>, val warmLocal: Boolean)
  private var nativeFilmstripInput: NativeFilmstripInput? = null
  private var legacyFaceStore: FaceFocusStore? = null
  private var legacyWarmup: LegacySlideshowWarmupGate? = null
  // Main owns both a pending store and the admitted WebView; preparation owns no Android view.
  private val legacyPreparationDelegate = lazy {
    LegacySlideshowPreparation(
        submitWorker = { task -> io.execute { task() } },
        postMain = { task -> ui.post { task() } })
  }
  private val legacyPreparation get() = legacyPreparationDelegate.value
  @Volatile private var legacyEngineRevision = 0L // Main writes; request threads check ownership.
  private var engineLayoutRoot: View? = null
  private var engineLayoutListener: View.OnLayoutChangeListener? = null
  @Volatile private var stopped = false
  private val nativeNavigation = SlideshowNavigation()
  private var nativeStarting = false
  private var afterNativeStartup: (() -> Unit)? = null
  private var videoReady = false
  private var paused = false // User choice; covering the host must never overwrite it.
  @Volatile private var hostActive = true
  private var hostVisible = true
  private var ambientNight = false
  private var quietClock: QuietNightClockView? = null
  private var darkRoomObserver: DarkRoomObserver? = null
  private var nightSettingsReceiver: BroadcastReceiver? = null
  private val revealClockRefresh = ClockRevealRefresh { wallClockMs ->
    if (!photosOnly && faceRendererDelegate.isInitialized())
      faceRenderer.refreshRetainedTime(Date(wallClockMs))
  }
  private val nightTransitionHandler = android.os.Handler(android.os.Looper.getMainLooper())
  private val nightTransition = NightClockTransition(
      nowMs = android.os.SystemClock::uptimeMillis,
      postDelayed = { task, delay -> nightTransitionHandler.postDelayed(task, delay) },
      removeCallbacks = { task -> nightTransitionHandler.removeCallbacks(task) },
      onReveal = { level ->
        // Photos remain suspended throughout the dissolve, but their clock is already visible.
        if (!ambientNight && hostVisible && !stopped && level < 1f)
          revealClockRefresh.update(System.currentTimeMillis())
        quietClock?.setReveal(level)
        if (level == 0f) quietClock?.visibility = View.GONE
      },
      onSettled = { night ->
        revealClockRefresh.reset()
        quietClock?.setActive(night && hostVisible && !stopped)
        applyHostActive(hostVisible && !night && !stopped)
      })
  /** Hosts apply window-scoped dimming; restoring it never writes the device's global brightness. */
  var onNightClockChanged: ((Boolean) -> Unit)? = null
  val isNightClockShowing: Boolean get() = nightClockOnly || ambientNight
  private var hostSuspendedAt = 0L
  private var retainedVideoPosition = 0
  private var photoFade: AnimatorSet? = null
  private var holdDeadline = 0L
  private var remainingHold = 0L
  private var nativeFailures = 0
  private lateinit var viewerControls: SlideshowViewerControls
  private lateinit var videoView: SlideshowVideoView

  // tvOS-style slow zoom/pan ("Ken Burns") on the photo layer. Runs over the dwell time and is
  // cancelled/restarted on each advance; only in fill mode (fit/letterbox is "show the whole
  // image", which zooming would crop into). [kenBurnsStyle] cycles the motion so consecutive
  // photos don't all move the same way.
  private var kenBurns: AnimatorSet? = null
  private var kenBurnsBack: AnimatorSet? = null
  private var kenBurnsStyle = 0

  // The "place · date" caption is now a FaceRenderer grid element (so it stacks with the
  // now-playing card instead of overlapping it). This controller still reads the EXIF here
  // (own photos only — local folder / SMB) and pushes it via [FaceRenderer.setCaption].

  // The overlay (clock / date / weather / battery / now-playing) is built and driven by the
  // FaceRenderer from a Face descriptor; this controller owns only the photo/video layer.
  private val faceRendererDelegate = lazy { FaceRenderer(context, weatherRefreshMs) }
  private val faceRenderer get() = faceRendererDelegate.value

  // Calendar widget (top-right): a clean upcoming-events panel fed by a public ICS
  // feed (Google secret-iCal / Apple iCloud public-calendar). Hidden when no link.
  private lateinit var calendarPanel: LinearLayout
  private lateinit var calendarHeader: TextView
  private lateinit var calendarRows: LinearLayout
  private var calendarEvents: List<CalendarFeed.Event> = emptyList()
  // Text/padding scale for the current calendar size (0/1/2 → small/medium/large),
  // applied as the panel is (re)rendered so a size change takes effect live.
  private var calScale: Float = 1f

  // Ambient dashboard: full-screen glanceable info card shown periodically (fork feature).
  private var dashboardPanel: View? = null
  private lateinit var dashClock: TextView
  private lateinit var dashDate: TextView
  private lateinit var dashWeather: TextView
  private lateinit var dashEvent: TextView
  private lateinit var dashAlmanac: TextView
  private var dashboardVisible = false
  // Weather string for the dashboard. The Face overlay fetches its own weather; this
  // small keyless fetch feeds the ambient-dashboard card so it stays self-contained.
  private var weatherText: String = ""

  // Welcome-back overlay + TTS greeting (fork feature; off unless WelcomeConfig enables it).
  private lateinit var welcomeOverlay: View
  private var welcomeVisible = false
  private val dismissWelcomeRunnable = Runnable { dismissWelcomeAnimated() }
  private var tts: TextToSpeech? = null
  @Volatile private var ttsReady = false
  @Volatile private var pendingSpeech: String? = null

  // Ambient soundscape (synthesized) played while the frame is up (fork feature).
  private val soundscape = SoundscapePlayer()
  // AudioTrack release may wait for its render thread. Keep all start/stop calls serialized off main.
  private val soundscapeIoDelegate = lazy { Executors.newSingleThreadExecutor() }
  private val soundscapeIo get() = soundscapeIoDelegate.value

  // Optional contact-free "wave to advance" (Camera2 motion; opt-in, off by default).
  private var gestureCamera: GestureCamera? = null

  private var settings = ScreensaverConfig.Settings()

  // Local-folder playback state.
  private var localMode = false
  private var playlist: List<MediaItem> = emptyList()
  private var localDeckKeys: List<String> = emptyList()
  private var localIndex = -1
  private var localImageFailStreak = 0
  // Bumped on every advance so a slow image-decode or an old video's completion
  // callback can tell it's been superseded and bow out.
  @Volatile private var gen = 0

  // Remote playback state (iCloud / Google Photos shared albums, or an Immich server).
  private var remoteMode = false
  private var remoteUrls: List<String> = emptyList()
  private var remoteIndex = -1
  private var remoteFailStreak = 0
  // Shared-album signed image URLs (iCloud/Google) expire over time. When a whole loop of
  // downloads fails we re-mint fresh URLs and keep playing instead of dropping to the built-in
  // feed; [remoteReresolveStreak] caps consecutive re-resolves so a genuine outage still settles
  // on the fallback rather than re-fetching forever. [remoteReresolving] guards against
  // overlapping re-fetches.
  private var remoteReresolveStreak = 0
  private val remoteRecovery = SlideshowRecoveryIntent()
  private val remoteReresolving get() = remoteRecovery.resolving
  // Auth headers sent with each remote fetch (image download or video stream) — empty for
  // public shares, the x-api-key for Immich. Applied in [advanceRemote]/[downloadBitmap]/
  // [showRemoteVideo].
  private var remoteHeaders: Map<String, String> = emptyMap()
  // The subset of [remoteUrls] that are videos (Immich with "Play videos" on); these stream
  // through the VideoView instead of the bitmap download. See [showRemoteVideo].
  private var remoteVideos: Set<String> = emptySet()
  // Optional custom fetcher for the remote path: when set (SMB), each "url" is fetched through
  // this instead of an HTTP download, so SMB reuses all the remote advance/tick/fallback logic.
  private var remoteFetch: ((String, Int) -> Bitmap?)? = null
  private var smbSource: SmbSource? = null
  // On-device media cache for stable-URL HTTP sources (Immich, WebDAV): images are stored as
  // fetched, videos as 1200x800 derivatives, so each asset is pulled from the server once and
  // then replayed from local storage on every loop. Null unless such a source is active. A
  // background worker on [transcodeIo] warms the video cache ahead of playback. See
  // [enableMediaCache]/[fetchRemoteImage]/[schedulePrefetch].
  private var mediaCache: MediaCache? = null
  private var transcoder: VideoTranscoder? = null
  private val transcodeIo = Executors.newSingleThreadExecutor()
  @Volatile private var prefetchRunning = false
  @Volatile private var videoPrefetchConnection: HttpURLConnection? = null
  // Set when a prefetch pass finds the cache out of room, so later misses don't spawn a worker
  // (and log) just to rediscover that every ~30s. Cleared when a corrupt entry is deleted (space
  // freed); resets with the controller each dream session.
  @Volatile private var prefetchFull = false
  // Web-page source: a fullscreen WebView that owns the whole frame (the page brings its own
  // clock/widgets, so the photo layer and Immortal overlay are skipped).
  @Volatile private var webView: android.webkit.WebView? = null
  private var legacyRemoteImages: EngineRemoteImageCache? = null // Main owns retirement.
  private var legacyRemoteTransports: EnginePhotoTransports? = null

  // Web-feed history so swipes can go back as well as forward.
  private val history = ArrayList<Bitmap>() // Classic retains its original history policy.
  private val nativeHistory = ArrayList<SlideshowPhoto>()
  private val photoIdentities = java.util.WeakHashMap<Bitmap, String>()
  private var index = -1
  private val photoTransportGeneration = java.util.concurrent.atomic.AtomicLong()
  private val photoTransportLock = Any()
  private val photoConnections = HashSet<HttpURLConnection>()
  private val photoStreams = HashSet<java.io.InputStream>()
  private val photoCancelIo = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "slideshow-close-transports").apply { isDaemon = true }
  }

  // Default-feed source-chain state (see [fetchWebPhoto]).
  // Wikimedia Commons featured-landscape image list: fetched once per session, then cycled.
  private var wikimediaUrls: List<String> = emptyList()
  private var wikimediaIdx = 0
  // Bundled offline photos (assets/[FALLBACK_DIR]): shuffled once, then cycled.
  private var bundledNames: List<String> = emptyList()
  private var bundledIdx = 0
  // Per-source failure backoff so a dead remote (e.g. a Picsum outage) isn't retried
  // every tick; the entry expires after [SOURCE_COOLDOWN_MS] so recovery self-heals.
  private val sourceCooldownUntil = HashMap<String, Long>()

  /** Host (dream / preview activity) sets this to dismiss the frame on tap. */
  var onExit: (() -> Unit)? = null

  /** Debug/preview override: render this face instead of the built-in classic one. */
  var faceOverride: Face? = null

  // Deterministic gesture from raw down/up deltas (robust to synthetic input
  // that omits MOVE events): clear horizontal swipe = prev/next, clear tap = exit.
  private var downX = 0f
  private var downY = 0f

  /** Hosts forward their touch events here. */
  fun onTouch(ev: MotionEvent) {
    // Meta can stop the Dream before ACTION_UP arrives. An in-progress touch is user
    // interaction, so recovery must not reopen photos between finger-down and dismissal.
    if (!photosOnly && ev.actionMasked == MotionEvent.ACTION_DOWN) DreamPolicy.userExitAt = System.currentTimeMillis()
    if (isNightClockShowing) {
      if (ev.actionMasked == MotionEvent.ACTION_UP &&
          abs(ev.x - downX) < 48 && abs(ev.y - downY) < 48) onExit?.invoke()
      if (ev.actionMasked == MotionEvent.ACTION_DOWN) { downX = ev.x; downY = ev.y }
      return
    }
    // While the timer alarm is showing, the slide-to-stop owns the touch stream.
    if (faceRenderer.handleAlarmTouch(ev)) return
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = ev.x
        downY = ev.y
      }
      MotionEvent.ACTION_UP -> {
        val dx = ev.x - downX
        val dy = ev.y - downY
        // A tap while the welcome overlay is showing dismisses it early
        // rather than exiting the screensaver.
        if (welcomeVisible && abs(dx) < 48 && abs(dy) < 48) {
          dismissWelcome()
          return
        }
        if (abs(dx) > 120 && abs(dx) > abs(dy) * 1.5f) {
          if (dx < 0) next() else prev()
        } else if (abs(dx) < 48 && abs(dy) < 48) {
          if (interactivePreview && nativePhotoMode)
            viewerControls.toggle()
          else onExit?.invoke()
        }
      }
    }
  }

  val view: View by lazy {
    val construction = FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.VIEW_BUILD)
    try { buildUi() } finally { construction?.finish() }
  }
  private var nativePhotoMode = false

  fun start() {
    val startup = FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.CONTROLLER_START)
    try { startFrame() } finally { startup?.finish() }
  }

  private fun startFrame() {
    stopped = false
    settings = ScreensaverConfig.load(context).let {
      if (photosOnly) musicPhotoSettings(it) else it
    }
    val root = view as FrameLayout
    observeNightSettings()
    if (nightClockOnly) {
      quietClock?.setActive(hostVisible)
      onNightClockChanged?.invoke(true)
      return
    }
    if (!photosOnly && settings.darkRoomClock) {
      darkRoomObserver = DarkRoomObserver(context, settings.darkRoomLux.toFloat(),
          onNightChanged = ::setAmbientNight)
      darkRoomObserver?.setActive(hostVisible)
    }
    val source = PhotoFrameSource.from(settings, allowWebPage = !photosOnly && faceOverride == null)
    nativePhotoMode = ScreensaverConfig.isNativeStage(settings.mode) && source !is PhotoFrameSource.WebPage
    if (nativePhotoMode) {
      nativeStarting = true
      nativeStage.visibility = View.VISIBLE
      root.setBackgroundColor(SlideshowStage.MATTE)
      // A local bundled image is immediately available while source discovery is underway.
      // It never waits behind calendar/weather requests or a failing network provider.
      nativeLoader.load(
          SlideshowAsset("feed", { bundledBitmap() }, contentIdentity = true,
              identity = { bitmap -> synchronized(photoIdentities) { photoIdentities.remove(bitmap) } }),
          emptyList(), null, false, false,
          { photos, _ ->
            if (!stopped) {
              if (!localMode && !remoteMode) rememberNativePhoto(photos.first())
              presentNative(photos)
            }
          }, { finishNativeStartup() })
    }
    // Caching off? Reclaim any space a previous "on" session left behind (best-effort, off-thread).
    if (!settings.cacheEnabled) Thread {
      if (!stopped && !ScreensaverConfig.load(context).cacheEnabled) MediaCache.purge(context)
    }.apply { name = "slideshow-cache-housekeeping"; start() }
    applyFit()
    if (!photosOnly) {
      refreshCalendar.run()
      calendarTick.run()
    }
    // Build + drive the overlay from the user's selected face ([FaceCatalog]); faceOverride lets
    // the debug preview harness (and the overnight night clock) render a specific face instead.
    if (!photosOnly) faceRenderer.start(faceOverride ?: FaceCatalog.active(context))
    if (!photosOnly) {
      galleryOverlayObserver?.close()
      galleryOverlayObserver = GalleryOverlayLayoutObserver(root, ::refreshGalleryOverlays).also {
        it.setActive(hostActive)
      }
    }

    // --- fork features layered on top of the Face overlay ---
    // Only spin up TTS when the welcome overlay will actually speak. The greeting uses the
    // Android TTS service selected on the device, keeping any neural engine out-of-process.
    val welcomeCfg = WelcomeConfig.load(context)
    val ttsEnabled = !photosOnly && showWelcome && settings.welcomeEnabled && welcomeCfg.enableTts
    if (ttsEnabled) {
      tts = TextToSpeech(context) { status ->
        if (status == TextToSpeech.SUCCESS && !ttsReady) {
          tts?.language = Locale.US
          tts?.setPitch(1.0f)
          tts?.setSpeechRate(0.9f)
          // Apply the user's chosen voice; if none chosen, auto-pick the highest-quality
          // voice the device has so the greeting sounds as good as possible by default.
          runCatching {
            val voices = tts?.voices
            val chosen =
                if (welcomeCfg.ttsVoice.isNotBlank()) voices?.firstOrNull { it.name == welcomeCfg.ttsVoice }
                else voices
                    ?.filter { it.locale.language == Locale.US.language && !it.isNetworkConnectionRequired }
                    ?.maxByOrNull { it.quality }
            chosen?.let {
              tts?.language = it.locale
              tts?.voice = it
            }
          }
          ttsReady = true
          pendingSpeech?.let { text ->
            speakWelcome(text, welcomeCfg)
            pendingSpeech = null
          }
        }
      }
    }

    // Start the ambient soundscape (no-op when set to Off).
    if (!photosOnly && settings.soundscape != ScreensaverConfig.SOUND_OFF) updateSoundscape()

    // Optional "wave to advance" — wholly guarded; any failure just disables it so the
    // always-on dream process is never put at risk.
    if (!photosOnly && settings.gestureWave) resumeGestureCamera()

    // Feed the ambient dashboard's weather, and schedule its cycle (first card after ~45s).
    // The ambient dashboard is a Classic-renderer feature: it pauses the photo and shows a big
    // clock/date/weather card periodically. Under the engine that's just an interruption (the engine
    // already shows a clock overlay, and the dashboard would render behind the opaque WebView), so
    // it's disabled whenever an engine mode is active — the user never asked for that overlay.
    val engineMode = ScreensaverConfig.usesEngine(settings.mode)
    if (!photosOnly) refreshWeather.run()
    if (!photosOnly && settings.ambientDashboard && !engineMode) ui.postDelayed(dashboardCycle, 45_000L)

    if (!photosOnly && showWelcome && settings.welcomeEnabled && !engineMode) showWelcomeOverlay()
    when (source) {
      is PhotoFrameSource.WebPage -> {
        // Web-page source takes over the whole frame — no photo feed, no Immortal overlay.
        startWebPage(source.url)
      }
      is PhotoFrameSource.Folder -> {
        io.execute {
          val list =
              if (LocalMedia.isAccessible(source.path)) LocalMedia.enumerate(source.path, source.includeVideo)
              else emptyList()
          val ordered = if (source.shuffle) list.shuffled() else list
          val deckKeys = if (ScreensaverConfig.isNativeStage(settings.mode)) {
            ordered.map { localKey(it.path) }
          } else emptyList()
          // Engine mode: precompute each photo's ORIENTED w/h (header-only decode) so the
          // orientation-aware mode scheduler in modes.js can match photos to modes, and drop
          // sub-[MIN_PHOTO_EDGE] thumbnails/junk that cheapen the wall. Bounded to
          // [ENGINE_MAX_PHOTOS] to keep this pass (and the deck) small. Images only — the HTML
          // engine can't play local videos.
          val useEngine = ScreensaverConfig.usesEngine(settings.mode)
          val engineDims = HashMap<String, Pair<Int, Int>>()
          val engineUrls =
              if (useEngine) {
                val urls = ArrayList<String>()
                var measured = 0
                for (item in ordered) {
                  if (item.isVideo) continue
                  if (urls.size >= ENGINE_MAX_PHOTOS) break
                  val u = "file://" + item.path
                  // Measure oriented w/h for a bounded SEED only (this pre-pass gates first paint).
                  // Tiny junk is dropped among the measured (a sub-[MIN_PHOTO_EDGE] header is the
                  // reliable junk signal); unmeasured photos still show, defaulting to a safe bucket.
                  if (measured < DIMS_SEED) {
                    measured++
                    val d = orientedImageDims(item.path)
                    if (d != null) {
                      if (minOf(d.first, d.second) < MIN_PHOTO_EDGE) continue
                      engineDims[u] = d
                    }
                  }
                  urls.add(u)
                }
                urls
              } else emptyList()
          ui.post {
            if (stopped) return@post
            if (useEngine && engineUrls.isNotEmpty()) {
              startEngine(engineUrls, dims = engineDims, warmLocalCopies = true)
              return@post
            }
            if (ordered.isNotEmpty()) {
              playlist = ordered
              localDeckKeys = deckKeys
              localMode = true
              localIndex = -1
              localImageFailStreak = 0
              advanceLocal(+1)
            } else {
              // Folder empty / unreachable → never leave the frame blank.
              startWeb()
            }
          }
        }
      }
      is PhotoFrameSource.SharedAlbum -> {
        val m = context.resources.displayMetrics
        sourceDiscovery.load(
            block = { RemoteAlbum.fetch(source.url, m.widthPixels, m.heightPixels) },
            onResult = { album ->
          val urls = album?.photoUrls.orEmpty()
          ui.post {
            if (stopped) return@post
            if (urls.isNotEmpty()) {
              val ordered = if (source.shuffle) urls.shuffled() else urls
              // Shared-album URLs are direct https with no auth header, so the WebView engine can
              // load them straight into <img>. Hand off to the engine when a mode is selected.
              // The album keeps refreshing underneath (scheduleEngineAlbumRefresh): photos the
              // user adds in Google Photos / iCloud appear without touching the Portal.
              if (ScreensaverConfig.usesEngine(settings.mode)) {
                startEngine(ordered, authHeaders = album?.headers.orEmpty())
                scheduleEngineAlbumRefresh(source.url, ordered, source.shuffle, album?.headers.orEmpty())
                return@post
              }
              remoteUrls = ordered
              remoteHeaders = album?.headers.orEmpty()
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
              advanceRemote(+1)
              scheduleRemoteRefresh()
            } else {
              // Album unshared / unreachable → never leave the frame blank.
              startWeb()
            }
          }
        })
      }
      is PhotoFrameSource.Immich -> {
        sourceDiscovery.load(block = {
          val media =
              ImmichSource.listMedia(source.url, source.key, source.albumId, source.includeVideo)
                  .orEmpty()
          if (!stopped && media.isNotEmpty() && !ScreensaverConfig.usesEngine(settings.mode)) enableMediaCache()
          media
        }, onResult = { discovered ->
          val media = discovered.orEmpty()
          ui.post {
            if (stopped) return@post
            if (media.isNotEmpty()) {
              val ordered = if (source.shuffle) media.shuffled() else media
              // Engine mode: images only (WebView can't play the video URLs), auth via header inject.
              if (ScreensaverConfig.usesEngine(settings.mode)) {
                val urls = ordered.filterNot { it.isVideo }.map { it.url }
                if (urls.isNotEmpty()) {
                  startEngine(urls, authHeaders = ImmichSource.authHeaders(source.key)); return@post
                }
              }
              remoteUrls = ordered.map { it.url }
              remoteVideos = media.filter { it.isVideo }.mapTo(HashSet()) { it.url }
              remoteHeaders = ImmichSource.authHeaders(source.key)
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
               advanceRemote(+1)
              schedulePrefetch()
            } else {
              // Server unreachable / album empty → never leave the frame blank. Say so: the
              // swap to the built-in feed is otherwise invisible in a user report (issue #142).
              Log.w(TAG, "Immich gave no media (album=${source.albumId ?: "library"}); falling back to built-in feed")
              startWeb()
            }
          }
        })
      }
      is PhotoFrameSource.WebDav -> {
        sourceDiscovery.load(block = {
          val media =
              DavSource.listMedia(source.url, source.user, source.pass, source.includeVideo)
                  .orEmpty()
          if (!stopped && media.isNotEmpty() && !ScreensaverConfig.usesEngine(settings.mode)) enableMediaCache()
          media
        }, onResult = { discovered ->
          val media = discovered.orEmpty()
          ui.post {
            if (stopped) return@post
            if (media.isNotEmpty()) {
              val ordered = if (source.shuffle) media.shuffled() else media
              if (ScreensaverConfig.usesEngine(settings.mode)) {
                val urls = ordered.filterNot { it.isVideo }.map { it.url }
                if (urls.isNotEmpty()) {
                  startEngine(urls, authHeaders = DavSource.authHeaders(source.user, source.pass)); return@post
                }
              }
              remoteUrls = ordered.map { it.url }
              remoteVideos = ordered.filter { it.isVideo }.mapTo(HashSet()) { it.url }
              remoteHeaders = DavSource.authHeaders(source.user, source.pass)
              remoteMode = true
              remoteIndex = -1
              remoteFailStreak = 0
               advanceRemote(+1)
              schedulePrefetch()
            } else {
              startWeb()
            }
          }
        })
      }
      is PhotoFrameSource.Smb -> {
        val src =
            SmbSource(
                host = source.host,
                shareName = source.share,
                basePath = source.path,
                user = source.user,
                password = source.pass,
            )
        io.execute {
          val queued = try {
            prepareSlideshowSource(
                source = src,
                prepare = { connected ->
                  if (connected.connect()) connected.listImages().takeIf { it.isNotEmpty() }
                  else null
                },
                release = ::closeSmbSourceAsync,
                handoff = { connected, paths, discard ->
                  ui.postOwned(Runnable {
                    if (stopped) {
                      discard()
                      return@Runnable
                    }
                    smbSource = connected
                    remoteUrls = if (source.shuffle) paths.shuffled() else paths
                    remoteFetch = { p, edge -> connected.openStream(p)?.use { decodeBoundedStream(it, edge) } }
                    remoteMode = true
                    remoteIndex = -1
                    remoteFailStreak = 0
                    advanceRemote(+1)
                  }, onDiscard = discard)
                })
          } catch (error: Exception) {
            Log.w(TAG, "SMB startup failed (${error.javaClass.simpleName}); using built-in photos")
            false
          }
          if (!queued && !stopped) ui.post { if (!stopped) startWeb() }
        }
      }
      PhotoFrameSource.DefaultFeed -> {
        // Engine mode + built-in feed: give the engine a batch of keyless Picsum URLs with varied
        // aspect ratios (portraits/landscapes/squares/panos) so every mode has material to work with
        // out of the box, before the user configures their own photo source. Otherwise, classic feed.
        if (ScreensaverConfig.usesEngine(settings.mode)) {
          startEngine(defaultFeedUrls())
        } else startWeb()
      }
    }
  }

  /** Render an arbitrary web page fullscreen (Immich Kiosk, a dashboard, …). The page owns the
   *  whole frame; the host's touch handling still gives tap-to-exit. */
  @android.annotation.SuppressLint("SetJavaScriptEnabled")
  private fun startWebPage(url: String) {
    // Override onTouchEvent/performClick (not just setOnTouchListener, which WebView ignores in its
    // own onTouchEvent) so the page never eats touch — the host needs it for tap-to-exit / swipe.
    val wv =
        object : android.webkit.WebView(context) {
          override fun onTouchEvent(event: MotionEvent): Boolean = false
          override fun performClick(): Boolean = false
        }
    wv.setBackgroundColor(Color.BLACK)
    wv.isVerticalScrollBarEnabled = false
    wv.isHorizontalScrollBarEnabled = false
    wv.overScrollMode = View.OVER_SCROLL_NEVER
    wv.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      mediaPlaybackRequiresUserGesture = false
      loadWithOverviewMode = true
      useWideViewPort = true
    }
    wv.webViewClient = object : android.webkit.WebViewClient() {
      override fun onPageFinished(v: android.webkit.WebView, url: String) {
        if (!stopped && webView === v && !hostActive) v.onPause()
      }
    } // Keep navigation inside this retained document.

    (view as FrameLayout).addView(wv, FrameLayout.LayoutParams(MATCH, MATCH))
    // pauseTimers() is process-wide; resume any suspension left by an older frame lifecycle.
    wv.resumeTimers()
    wv.onResume()
    webView = wv
    wv.loadUrl(url)
    if (!hostActive) wv.onPause()
  }

  /**
   * Render the photos with the bundled HTML/JS screensaver engine (assets/screensaver) instead of
   * the native crossfade — the premium multi-style modes. [photoUrls] are file:// paths (local
   * folder) or http(s) URLs (Immich/DAV/album/feed). When a source needs auth headers (Immich
   * x-api-key, WebDAV Basic) the WebView's plain <img> loads can't send them, so [authHeaders] are
   * injected by intercepting each request and re-fetching with the headers (see the WebViewClient
   * below). The engine reads the photo list + mode via the injected JS bridge.
   *
   * The engine draws the photos; the Face overlay (clock/weather) stays on top as usual, and
   * tap-to-exit still works because we suppress the WebView's own touch handling.
   */
  @androidx.annotation.MainThread
  @android.annotation.SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
  private fun startEngine(
      photoUrls: List<String>,
      dims: Map<String, Pair<Int, Int>> = emptyMap(),
      authHeaders: Map<String, String> = emptyMap(),
      warmLocalCopies: Boolean = false,
      forceLegacy: Boolean = false,
  ) {
    if (stopped) return
    if (BuildConfig.PORTAL_NATIVE_FILMSTRIP && settings.mode == "filmstrip" && !forceLegacy) {
      startNativeFilmstrip(photoUrls, dims, authHeaders, warmLocalCopies)
      return
    }
    if (legacyPreparationDelegate.isInitialized()) legacyPreparation.cancel()
    val owner = ++legacyEngineRevision
    legacyWarmup?.close()
    legacyWarmup = null
    legacyFaceStore?.close()
    // Freeze source/bridge inputs without doing cache hydration or JSON work on Main.
    val urls = photoUrls.toList()
    val localPhotoRequests = EngineLocalPhotoRequests(urls, owner)
    val remotePhotoRequests = EngineRemotePhotoRequests(urls, owner)
    val dimensions = dims.toMap()
    val headers = authHeaders.toMap()
    val mode = settings.mode
    val intervalSec = settings.intervalSec
    val photoMotion = settings.photoMotion
    val use24 = ImmortalSettings.use24HourClock(context)
    val frozenFetch = remoteFetch
    val faceStore = FaceFocusStore(context.applicationContext).also {
      it.setActive(hostActive && !paused)
      legacyFaceStore = it
    }
    engineActive = true // Preparing is already legacy-owned; do not admit native playback.
    legacyPreparation.request(
        prepare = { current ->
          val preparation = FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.METADATA_PREPARATION)
          try {
            LegacySlideshowPreparation.photosJson(urls, dimensions,
                faceStore::focusFor, faceStore::weightFor, current)
          } finally { preparation?.finish() }
        },
        onReady = { photosJson ->
          if (!stopped && owner == legacyEngineRevision && legacyFaceStore === faceStore) {
            val admission = FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.WEB_VIEW_ADMISSION)
            try {
              // Keep the previous view owned while metadata prepares. No new WebView exists
              // before admission, so stop/reload cannot strand an unattached allocation.
              retireWebView()
              val construction = FramePerformanceCapture.beginSlideshowWork(SlideshowWorkStage.WEB_VIEW_CONSTRUCTION)
              val wv = try {
                object : android.webkit.WebView(context) {
                  override fun onTouchEvent(event: MotionEvent): Boolean = false
                  override fun performClick(): Boolean = false
                }
              } finally { construction?.finish() }
              // Register immediately so any later bridge/layout/load failure has Main-owned cleanup.
              webView = wv
              if (BuildConfig.DEBUG) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
              val remoteTransports = if (remotePhotoRequests.hasSources)
                EnginePhotoTransports { photoCancelIo.execute(it) }.also { legacyRemoteTransports = it } else null
              val remoteImages = if (remotePhotoRequests.hasSources) EngineRemoteImageCache(
                  java.io.File(context.cacheDir, "ss_remote/${java.util.UUID.randomUUID()}"),
                  download = { url, target, current ->
                    val generation = photoTransportGeneration.get()
                    runCatching {
                      EngineRemoteHttp.download(url, headers, USER_AGENT, target, remoteTransports!!) {
                        current() && photoRequestCurrent(generation)
                      }
                    }.getOrDefault(false)
                  },
                  prepare = { source, edge, current, cancelled ->
                    val format = source.inputStream().use(EnginePhotoContentType::inspect)
                    // Animated sources retain encoded playback. Their pixel residency remains
                    // outside the static-copy budget; never silently flatten a user's animation.
                    if (format.animation) source else scaledCache.prepareForLoad(source.path, edge, current, cancelled)
                  },
                  isActive = { !stopped && hostActive && owner == legacyEngineRevision && webView === wv },
                  onCancel = { remoteTransports?.close() },
              ).also { legacyRemoteImages = it } else null
              wv.setBackgroundColor(Color.BLACK)
              wv.isVerticalScrollBarEnabled = false
              wv.isHorizontalScrollBarEnabled = false
              wv.overScrollMode = View.OVER_SCROLL_NEVER
              wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                // Local engine + local photo files: allow file access (same as the flip-clock face WebView).
                allowFileAccess = true
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = true
              }
              // Local misses wait on the request thread for the shared bounded decoder, never Main.
              // Remote authentication and the engine's bundled assets retain their existing path.
              wv.webViewClient =
                  object : android.webkit.WebViewClient() {
                    override fun onPageFinished(v: android.webkit.WebView, url: String) {
                      if (!stopped && webView === v) {
                        FramePerformanceCapture.markSlideshowWork(SlideshowWorkStage.ENGINE_PAGE_FINISHED)
                        applyEnginePlayback(v)
                      }
                    }

                    override fun shouldInterceptRequest(
                        v: android.webkit.WebView,
                        req: android.webkit.WebResourceRequest,
                    ): android.webkit.WebResourceResponse? {
                      val u = req.url
                      if (localPhotoRequests.owns(u.toString()) || remotePhotoRequests.owns(u.toString())) {
                        fun unavailable() = android.webkit.WebResourceResponse("image/jpeg", null,
                            404, "Photo unavailable", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
                        if (stopped || webView !== v || owner != legacyEngineRevision ||
                            !req.method.equals("GET", ignoreCase = true)) return unavailable()
                        val remote = remotePhotoRequests.resolve(u.toString())
                        val local = localPhotoRequests.resolve(u.toString())
                        val file = when {
                          remote != null -> remoteImages?.prepareForLoad(remote.first, remote.second)
                          local != null -> scaledCache.prepareForLoad(local.first, local.second)
                          else -> null
                        } ?: return unavailable()
                        if (stopped || webView !== v || owner != legacyEngineRevision) return unavailable()
                        val stream = runCatching { file.inputStream().buffered() }.getOrNull() ?: return unavailable()
                        val format = runCatching {
                          stream.mark(32); EnginePhotoContentType.inspect(stream).also { stream.reset() }
                        }.getOrElse { stream.close(); return unavailable() }
                        return android.webkit.WebResourceResponse(format.mime, null, stream)
                      }
                      if (headers.isEmpty()) return null
                      val scheme = u.scheme?.lowercase()
                      if (scheme != "http" && scheme != "https") return null
                      if (!req.method.equals("GET", ignoreCase = true)) return null
                      // Auxiliary non-photo image requests keep their existing behavior, but no
                      // longer allocate an unbounded byte array or bypass transport cancellation.
                      val generation = photoTransportGeneration.get()
                      val bytes = runCatching { downloadBytes(u.toString(), headers, generation) }.getOrNull()
                          ?: return android.webkit.WebResourceResponse("image/jpeg", null,
                              404, "Photo unavailable", emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
                      val mime = when {
                        u.toString().endsWith(".png", true) -> "image/png"
                        u.toString().endsWith(".webp", true) -> "image/webp"
                        u.toString().endsWith(".gif", true) -> "image/gif"
                        else -> "image/jpeg"
                      }
                      return android.webkit.WebResourceResponse(mime, "utf-8", java.io.ByteArrayInputStream(bytes))
                    }
                  }
              // Forward the engine's JS console to logcat (tag "ImmortalEngine") — the WebView was a black box
              // before (no console reached a bug report), which made field diagnosis impossible.
              wv.webChromeClient =
                  object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                      Log.i("ImmortalEngine", "${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                      return true
                    }
                  }
              val hybrid = if (BuildConfig.PORTAL_NATIVE_FILMSTRIP && mode == "shuffle" && !forceLegacy) {
                HybridFilmstripHost(view as FrameLayout,wv,urls,intervalSec.toLong()*1000,
                    decode={ url,edge ->
                      decodeNativeFilmstrip(url, edge, headers, frozenFetch)
                    },
                    isCurrent={ !stopped && owner == legacyEngineRevision && webView === wv && legacyFaceStore === faceStore },
                    liftOverlays={
                      runCatching { faceRenderer.view.bringToFront() }
                      runCatching { calendarPanel.bringToFront() }
                      dashboardPanel?.let { runCatching { it.bringToFront() } }
                      runCatching { welcomeOverlay.bringToFront() }
                    }).also {
                  hybridFilmstrip = it
                  it.setHostActive(hostActive)
                  it.setPaused(paused)
                }
              } else null
              val warmup = LegacySlideshowWarmupGate(
                  postMain = { task -> ui.post { task() } },
                  isCurrent = { !stopped && owner == legacyEngineRevision &&
                      legacyFaceStore === faceStore && engineActive && webView === wv },
                  mayRun = { hostActive && !paused },
                  startWarm = { faceStore.startWarmUp(urls, headers) },
              ).also { legacyWarmup = it }
              val liveFocus = LegacyPhotoFocusBridge(urls,
                  isCurrent = { !stopped && owner == legacyEngineRevision &&
                      legacyFaceStore === faceStore && engineActive && webView === wv },
                  cachedMetadata = faceStore::cachedMetadata)
              val styleReporter = SlideshowStyleReporter(
                  host = when (context) {
                    is PhotoFramePreviewActivity -> SlideshowStyleHost.PREVIEW
                    is PhotoDreamService -> SlideshowStyleHost.DREAM
                    else -> SlideshowStyleHost.UNKNOWN
                  },
                  prepare = { host, style ->
                    FramePerformanceCapture.prepareSlideshowStyleReport(host, style) {
                      if (style == SlideshowStyle.FILMSTRIP && hybrid?.hasActiveNative == true) null
                      else SlideshowLayerObservation(wv.layerType, wv.isHardwareAccelerated,
                          wv.isAttachedToWindow, wv.width, wv.height)
                    }
                  },
                  postMain = { task -> ui.post { task() } },
                  isCurrent = { !stopped && owner == legacyEngineRevision && legacyFaceStore === faceStore &&
                      engineActive && webView === wv && hostActive && !paused })
              wv.addJavascriptInterface(
                  object {
                    @android.webkit.JavascriptInterface
                    fun reportPresentedStyle(modeId: String) {
                      styleReporter.receive(modeId)
                      // The scheduler reports only after its decoded scene is promoted.
                      // Warming is independent of optional diagnostics being enabled.
                      if (SlideshowStyle.fromWire(modeId) != null) warmup.presented()
                    }
                    @android.webkit.JavascriptInterface fun supportsNativeFilmstrip(): Boolean = hybrid != null
                    @android.webkit.JavascriptInterface fun prepareNativeFilmstrip(token: String, idsJson: String): Boolean =
                        hybrid?.prepare(token,idsJson) ?: false
                    @android.webkit.JavascriptInterface fun nativeFilmstripCommand(token: String, action: String, requestId: Int): Boolean =
                        hybrid?.command(token,action,requestId) ?: false
                    @android.webkit.JavascriptInterface fun promoteNativeFilmstrip(incoming: String, outgoing: String, handoffId: Int): Boolean =
                        hybrid?.promote(incoming,outgoing,handoffId) ?: false
                    @android.webkit.JavascriptInterface fun getPhotos(): String = photosJson
                    @android.webkit.JavascriptInterface
                    fun getPhotoFocus(index: Int): String = liveFocus.read(index)
                    @android.webkit.JavascriptInterface fun getMode(): String = mode
                    // Per-photo dwell (seconds). The engine holds each photo this long instead of its old
                    // hardcoded ~9s step, so the user's interval actually takes effect in engine modes.
                    @android.webkit.JavascriptInterface fun getIntervalSec(): Int = intervalSec
                    @android.webkit.JavascriptInterface fun getPhotoMotion(): Boolean = photoMotion
                    @android.webkit.JavascriptInterface fun getPhotoLayoutBounds(): String = legacyPhotoLayoutBounds
                    @android.webkit.JavascriptInterface fun paperSurfaceChanged(enabled: Boolean) {
                      if (photosOnly) return
                      ui.post {
                        if (!stopped && owner == legacyEngineRevision && webView === wv) {
                          faceRenderer.setPaperPhotoSurface(enabled)
                          refreshGalleryOverlays()
                        }
                      }
                    }
                    @android.webkit.JavascriptInterface fun use24Hour(): Boolean = use24
                    // Ready copies or opaque bounded requests; bridge calls never decode or wait.
                    @android.webkit.JavascriptInterface
                    fun scaledUrl(url: String): String = scaledUrlForSize(url, EngineScaledCache.TARGET_EDGE)
                    @android.webkit.JavascriptInterface
                    fun scaledUrlForSize(url: String, edge: Int): String {
                      remotePhotoRequests.urlFor(url, edge)?.let { pending ->
                        return remoteImages?.ready(url, edge)?.let { "file://" + it.absolutePath } ?: pending
                      }
                      val pendingUrl = localPhotoRequests.urlFor(url, edge) ?: return ""
                      val file = FramePerformanceCapture.measureImageResolution { measurement ->
                        scaledCache.resolveForBoundedLoad(url.removePrefix("file://"), edge, measurement)
                      } ?: return pendingUrl
                      return "file://" + file.absolutePath
                    }
                    // Live presence for the engine's people-bias: the deck leans toward photos of
                    // people while someone is actually in front of the frame. Reads the hub fresh
                    // per call (the engine polls at mode-beat cadence, a few times a minute).
                    @android.webkit.JavascriptInterface
                    fun isWatching(): Boolean = PresenceHub.current.presence == Presence.PRESENT
                  },
                  "Android")

              // The engine is the opaque PHOTO layer. Add it on top of the photo ImageViews, then lift the
              // native overlays (clock face, calendar, ambient dashboard, now-playing, welcome) ABOVE it by
              // re-adding them last — so every overlay setting keeps working across engine modes, exactly like
              // the classic renderer, while the WebView stays the opaque base (no home/photo bleed-through in
              // the gaps a mode leaves). The engine's own HTML clock is suppressed (?nativeClock=1) to avoid a
              // double clock — the Face overlay is the single clock everywhere.
              val root = view as FrameLayout
              // Pin the WebView to the root's REAL pixel size, not MATCH. When the engine attaches before
              // the host window's first layout (DreamService does this), a MATCH WebView can get measured
              // at its 256px unmeasured default and keep rendering the page in a narrow left strip with
              // the rest black. Explicit pixels now + a layout listener for any later size change.
              fun engineParams(): FrameLayout.LayoutParams =
                  if (root.width > 0 && root.height > 0) FrameLayout.LayoutParams(root.width, root.height)
                  else FrameLayout.LayoutParams(MATCH, MATCH)
              root.addView(wv, engineParams())
              refreshGalleryOverlays()
              val resize = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
                if (!stopped && webView === wv &&
                    ((r - l) != (or2 - ol) || (b - t) != (ob - ot))) {
                  if (root.width > 0 && root.height > 0) wv.layoutParams = engineParams()
                  refreshGalleryOverlays()
                }
              }
              engineLayoutRoot = root
              engineLayoutListener = resize
              root.addOnLayoutChangeListener(resize)
              // Re-stack the overlays above the engine (bringToFront preserves their relative order).
              runCatching { faceRenderer.view.bringToFront() }
              runCatching { calendarPanel.bringToFront() }
              dashboardPanel?.let { runCatching { it.bringToFront() } }
              runCatching { welcomeOverlay.bringToFront() }
              // Bust the WebView cache on app UPDATES by versioning the asset URL (VERSION_CODE bumps each
              // release) rather than clearCache(true) on every start — clearing every start wiped the disk
              // cache during the most latency-sensitive moment of cold start. Same URL within a version keeps
              // normal in-session caching.
              val assetVer =
                  runCatching {
                    @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).versionCode
                  }.getOrDefault(0)
              // Resume before loading: dimension measurement, image admission and promotion need JS timers.
              wv.resumeTimers()
              wv.onResume()
              val initiallyPaused = if (hostActive && !paused) 0 else 1
              wv.loadUrl("file:///android_asset/screensaver/index.html?nativeClock=1&v=$assetVer&hostPaused=$initiallyPaused")
              if (!hostActive) wv.onPause()
              // Cached face hints were read during metadata preparation. New analysis waits for
              // the first decoded scene, leaving page/image startup free of that extra work.
              if (warmLocalCopies) {
                val localPaths = urls.map { it.removePrefix("file://") }
                ui.postDelayed({
                  if (!stopped && owner == legacyEngineRevision && legacyFaceStore === faceStore && webView === wv)
                    scaledCache.warm(localPaths)
                }, ENGINE_WARM_DELAY_MS)
              }
            } finally { admission?.finish() }
          }
        },
        onFailure = { error -> failLegacyEngine(owner, faceStore, error) })
  }

  @androidx.annotation.MainThread
  private fun startNativeFilmstrip(photoUrls: List<String>, dims: Map<String, Pair<Int, Int>>,
      authHeaders: Map<String, String>, warmLocalCopies: Boolean) {
    val urls = photoUrls.toList()
    val headers = authHeaders.toMap()
    val fetch = remoteFetch
    val decoder: (String, Int) -> Bitmap? = { url, edge ->
      decodeNativeFilmstrip(url, edge, headers, fetch)
    }
    val input = NativeFilmstripInput(urls,dims.toMap(),headers,warmLocalCopies)
    nativeFilmstrip?.let {
      nativeFilmstripInput = input
      it.reload(urls,decoder,PresenceHub.current.presence == Presence.PRESENT)
      return
    }
    retireLegacyEngine()
    nativeFilmstripInput = input
    val owner = ++legacyEngineRevision
    val faceStore = FaceFocusStore(context.applicationContext).also {
      it.setActive(hostActive && !paused)
      legacyFaceStore = it
    }
    engineActive = true
    val root = view as FrameLayout
    val stage = FilmstripEngine(context, urls, intervalMs(),
        decode = decoder,
        weight = faceStore::weightFor,
        watching = PresenceHub.current.presence == Presence.PRESENT,
        onAdmitted = {
          if (!stopped && owner == legacyEngineRevision && nativeFilmstrip != null) {
            val host = when (context) {
              is PhotoFramePreviewActivity -> SlideshowStyleHost.PREVIEW
              is PhotoDreamService -> SlideshowStyleHost.DREAM
              else -> SlideshowStyleHost.UNKNOWN
            }
            FramePerformanceCapture.prepareSlideshowStyleReport(host, SlideshowStyle.FILMSTRIP)?.accept()
            nativeFilmstripInput?.let { faceStore.startWarmUp(it.urls, it.headers) }
          }
        },
        onFailure = {
          if (!stopped && owner == legacyEngineRevision && nativeFilmstrip != null) {
            val current = nativeFilmstripInput ?: input
            val refresh = engineAlbumPlan
            retireLegacyEngine()
            startEngine(current.urls, current.dims, current.headers, current.warmLocal, forceLegacy = true)
            refresh?.let { scheduleEngineAlbumRefresh(it.shareUrl,it.current,it.shuffle,it.headers) }
          }
        })
    nativeFilmstrip = stage // Main owns the stage before layout/start can fail.
    root.addView(stage, FrameLayout.LayoutParams(MATCH, MATCH))
    runCatching { faceRenderer.view.bringToFront() }
    runCatching { calendarPanel.bringToFront() }
    dashboardPanel?.let { runCatching { it.bringToFront() } }
    runCatching { welcomeOverlay.bringToFront() }
    stage.setHostActive(hostActive)
    stage.setPaused(paused)
    stage.start()
  }

  @androidx.annotation.MainThread
  private fun failLegacyEngine(owner: Long, faceStore: FaceFocusStore, error: Exception) {
    if (stopped || owner != legacyEngineRevision || legacyFaceStore !== faceStore) return
    Log.w("ImmortalSlideshow", "Legacy metadata/attachment failed: ${error.javaClass.simpleName}")
    retireLegacyEngine()
    // The existing default-photo provider chain is the same fallback used for an empty or
    // unreachable source. Clearing engine ownership makes it visible instead of leaving loading.
    startWeb()
  }

  @androidx.annotation.MainThread
  private fun retireLegacyEngine() {
    if (legacyPreparationDelegate.isInitialized()) legacyPreparation.cancel()
    legacyEngineRevision++
    nativeFilmstrip?.let { stage ->
      nativeFilmstrip = null
      nativeFilmstripInput = null
      if (engineActive) FramePerformanceCapture.markSlideshowWork(SlideshowWorkStage.ENGINE_RETIRED)
      stage.close()
      (stage.parent as? android.view.ViewGroup)?.removeView(stage)
    }
    legacyFaceStore?.close()
    legacyFaceStore = null
    engineAlbumRefresh?.let { ui.removeCallbacks(it) }
    engineAlbumRefresh = null
    engineAlbumPlan = null
    retireWebView()
    engineActive = false
  }

  @androidx.annotation.MainThread
  private fun retireWebView() {
    legacyRemoteImages?.close()
    legacyRemoteImages = null
    legacyRemoteTransports?.close()
    legacyRemoteTransports = null
    legacyWarmup?.close()
    legacyWarmup = null
    hybridFilmstrip?.close()
    hybridFilmstrip = null
    engineLayoutRoot?.let { root ->
      engineLayoutListener?.let { runCatching { root.removeOnLayoutChangeListener(it) } }
    }
    engineLayoutRoot = null
    engineLayoutListener = null
    val owned = webView
    webView = null
    legacyPhotoLayoutBounds = "{}"
    if (faceRendererDelegate.isInitialized()) faceRenderer.setPaperPhotoSurface(false)
    owned?.let { wv ->
      if (engineActive) FramePerformanceCapture.markSlideshowWork(SlideshowWorkStage.ENGINE_RETIRED)
      runCatching { wv.onPause() }
      runCatching { wv.stopLoading() }
      runCatching { (wv.parent as? android.view.ViewGroup)?.removeView(wv) }
      runCatching { wv.destroy() }
    }
  }

  /** True while the legacy engine is preparing or attached (native navigation stays out). */
  private var engineActive = false

  /** Suspend a retained host without destroying its photo, WebView, or user pause choice. */
  @androidx.annotation.MainThread
  fun setHostActive(value: Boolean) {
    if (hostVisible != value) revealClockRefresh.reset()
    hostVisible = value
    darkRoomObserver?.setActive(value && !stopped)
    quietClock?.setActive(value && (isNightClockShowing || nightTransition.coversPhotos) && !stopped)
    if (nightClockOnly) return
    nightTransition.setActive(value && !stopped)
    applyHostActive(value && !nightTransition.coversPhotos)
  }

  private fun observeNightSettings() {
    if (photosOnly || nightSettingsReceiver != null) return
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (!stopped && nightSettingsReceiver === this && intent?.action == NightSettingsChanges.ACTION)
          applyNightSettings(ScreensaverConfig.load(this@PhotoFrameController.context))
      }
    }
    nightSettingsReceiver = receiver
    androidx.core.content.ContextCompat.registerReceiver(context, receiver,
        IntentFilter(NightSettingsChanges.ACTION),
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
  }

  /** Update only clock controls; source discovery and the retained media session stay intact. */
  private fun applyNightSettings(fresh: ScreensaverConfig.Settings) {
    val sensorChanged = settings.darkRoomClock != fresh.darkRoomClock ||
        settings.darkRoomLux != fresh.darkRoomLux
    val brightnessChanged = settings.nightClockBrightness != fresh.nightClockBrightness
    val toneChanged = settings.nightClockTone != fresh.nightClockTone
    settings = settings.copy(darkRoomClock = fresh.darkRoomClock, darkRoomLux = fresh.darkRoomLux,
        nightClockBrightness = fresh.nightClockBrightness, nightClockTone = fresh.nightClockTone)
    if (toneChanged) quietClock?.updateTone(fresh.nightClockTone)
    if (sensorChanged && !nightClockOnly) {
      darkRoomObserver?.setActive(false)
      darkRoomObserver = null
      if (fresh.darkRoomClock) {
        darkRoomObserver = DarkRoomObserver(context, fresh.darkRoomLux.toFloat(),
            initialNight = ambientNight, onNightChanged = ::setAmbientNight)
        darkRoomObserver?.setActive(hostVisible)
      } else setAmbientNight(false)
    }
    if (brightnessChanged && isNightClockShowing) onNightClockChanged?.invoke(true)
  }

  private fun setAmbientNight(value: Boolean) {
    if (stopped || ambientNight == value) return
    revealClockRefresh.reset()
    ambientNight = value
    val root = view as FrameLayout
    if (value && quietClock == null) {
      quietClock = QuietNightClockView(context, settings.nightClockTone).also {
        it.setReveal(0f)
        root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
      }
    }
    if (value) {
      quietClock?.visibility = View.VISIBLE
      quietClock?.bringToFront()
      quietClock?.setActive(hostVisible)
      applyHostActive(false)
    }
    // Both directions retain the same photo until this finite cover has finished.
    nightTransition.setActive(hostVisible)
    nightTransition.setNight(value)
    onNightClockChanged?.invoke(value)
    Log.i("ImmortalNight", if (value) "dark room: fading to clock; photos suspended" else "light returned: fading to retained photo")
  }

  /** Effective renderer state combines external visibility with the ambient clock cover. */
  private fun applyHostActive(value: Boolean) {
    if (stopped || hostActive == value) return
    val now = android.os.SystemClock.uptimeMillis()
    if (!value) {
      hostSuspendedAt = now
      hostActive = false
      photoPause.setHostActive(false)
      legacyRemoteTransports?.cancelCurrent()
      legacyFaceStore?.setActive(false)
      ui.setActive(false)
      if (videoView.visibility == View.VISIBLE) {
        retainVideoPosition()
        runCatching { videoView.pause() }
      }
      kenBurns?.pause(); kenBurnsBack?.pause(); photoFade?.pause()
      nativeStage.setHostActive(false)
      nativeFilmstrip?.setHostActive(false)
      hybridFilmstrip?.setHostActive(false)
      faceRenderer.setHostActive(false)
      runCatching { gestureCamera?.stop() }
      gestureCamera = null
      cancelVideoPrefetchTransport()
      webView?.let { wv ->
        if (engineActive) applyEnginePlayback(wv)
        wv.onPause()
      }
    } else {
      // The callback queue freezes delays. Keep the viewer's pause deadline on that same clock.
      if (holdDeadline > 0L) holdDeadline += now - hostSuspendedAt
      hostActive = true
      webView?.let { wv ->
        wv.onResume()
        if (engineActive) applyEnginePlayback(wv)
      }
      nativeStage.setHostActive(true)
      nativeFilmstrip?.setHostActive(true)
      hybridFilmstrip?.setHostActive(true)
      kenBurns?.resume(); kenBurnsBack?.resume(); photoFade?.resume()
      faceRenderer.setHostActive(true)
      if (videoView.visibility == View.VISIBLE) {
        // A retained player resumes directly; SlideshowVideoView owns surface-loss recovery.
        retainedVideoPosition = 0
        if (!paused) runCatching { videoView.start() }
      }
      ui.setActive(true)
      photoPause.setHostActive(true)
      if (!photosOnly) calendarTick.run()
      if (!photosOnly && settings.gestureWave) resumeGestureCamera()
      if (remoteMode) schedulePrefetch()
      legacyFaceStore?.setActive(!paused)
    }
    galleryOverlayObserver?.setActive(value)
    updateSoundscape()
  }

  private fun updateSoundscape() {
    if (photosOnly || settings.soundscape == ScreensaverConfig.SOUND_OFF) return
    val shouldPlay = !stopped && hostActive
    soundscapeIo.execute {
      if (shouldPlay && !stopped && hostActive)
        runCatching { soundscape.start(settings.soundscape, settings.soundscapeVolume) }
      else runCatching { soundscape.stop() }
    }
  }

  private fun resumeGestureCamera() {
    if (!hostActive || stopped || gestureCamera != null) return
    runCatching {
      gestureCamera = GestureCamera(context) {
        if (hostActive && !stopped) ui.post { if (hostActive && !stopped) runCatching { next() } }
      }.also { it.start() }
    }
  }

  private fun cancelVideoPrefetchTransport() {
    videoPrefetchConnection?.let { connection ->
      photoCancelIo.execute { runCatching { connection.disconnect() } }
    }
  }

  /** Capture the cached playback position before Android tears down the surface. */
  fun retainVideoPosition() {
    if (stopped || !this::videoView.isInitialized || videoView.visibility != View.VISIBLE) return
    val position = runCatching { videoView.currentPosition }.getOrDefault(0)
    if (position > 0) retainedVideoPosition = position
  }

  private fun playbackUptime(): Long =
      if (hostActive) android.os.SystemClock.uptimeMillis() else hostSuspendedAt

  private fun applyEnginePlayback(wv: android.webkit.WebView) {
    val action = if (hostActive && !paused) "resume" else "pause"
    wv.evaluateJavascript("window.immortalSlideshow?.$action()", null)
    if (hostActive && !paused) legacyWarmup?.resume()
  }

  @androidx.annotation.MainThread
  fun stop() {
    stopped = true
    if (sourceDiscoveryDelegate.isInitialized()) sourceDiscovery.close()
    revealClockRefresh.reset()
    nightTransition.close()
    val receiver = nightSettingsReceiver
    nightSettingsReceiver = null
    receiver?.let { runCatching { context.unregisterReceiver(it) } }
    galleryOverlayObserver?.close()
    galleryOverlayObserver = null
    darkRoomObserver?.setActive(false)
    darkRoomObserver = null
    quietClock?.setActive(false)
    // The host window is departing. Restoring brightness here would briefly brighten a frozen
    // night surface; Android restores the underlying window's own brightness when ours disappears.
    onNightClockChanged = null
    if (legacyPreparationDelegate.isInitialized()) legacyPreparation.close()
    retireLegacyEngine()
    gen++
    nativeStarting = false
    afterNativeStartup = null
    cancelPhotoTransports()
    cancelVideoPrefetchTransport()
    if (nativeLoaderDelegate.isInitialized()) nativeLoader.close()
    photoPause.close()
    if (this::nativeStage.isInitialized) nativeStage.stop()
    clearNativeHistory()
    if (this::viewerControls.isInitialized) viewerControls.stop()
    scaledCache.cancel() // don't keep burning CPU on copies while Home redraws
    ui.removeCallbacks(dashboardCycle)
    ui.close()
    runCatching { gestureCamera?.stop() }
    gestureCamera = null
    // TTS stop/shutdown is an IPC round-trip to the system TTS service, and soundscape.stop() can
    // block briefly — move both off the main thread so neither delays the exit.
    val ttsRef = tts
    tts = null
    ttsReady = false
    pendingSpeech = null
    Thread {
      runCatching { ttsRef?.stop() }
      runCatching { ttsRef?.shutdown() }
    }.start()
    if (soundscapeIoDelegate.isInitialized()) {
      soundscapeIo.execute { runCatching { soundscape.stop() } }
      soundscapeIo.shutdown()
    }
    photoFade?.removeAllListeners()
    photoFade?.cancel()
    photoFade = null
    cancelKenBurns()
    // A departed Classic host must release its drawable/history references too.
    // Rendered pixels may still belong to Android's display list; never recycle them here.
    if (this::photo.isInitialized) photo.setImageDrawable(null)
    if (this::photoBack.isInitialized) photoBack.setImageDrawable(null)
    history.clear()
    if (faceRendererDelegate.isInitialized()) faceRenderer.stop()
    if (this::videoView.isInitialized) videoView.close()
    // Close the SMB connection off-thread (network I/O) before the io executor is killed.
    smbSource?.let(::closeSmbSourceAsync)
    smbSource = null
    io.shutdownNow()
    metaIo.shutdownNow()
    transcodeIo.shutdownNow()
    photoCancelIo.shutdown() // Drain owned closes; never wait for a network read on the UI thread.
  }

  // --- UI ---------------------------------------------------------------------
  // The photo/video layer lives here; the overlay (clock / widgets / now-playing) is the
  // FaceRenderer's [view], stacked on top from a Face descriptor.
  private fun buildUi(): View {
    val root = FrameLayout(context)
    root.setBackgroundColor(Color.BLACK)
    if (nightClockOnly) {
      quietClock = QuietNightClockView(context, ScreensaverConfig.load(context).nightClockTone)
      root.addView(quietClock, FrameLayout.LayoutParams(MATCH, MATCH))
      return root
    }

    // Back photo layer — sits below [photo]; receives the incoming bitmap during a crossfade.
    photoBack = ImageView(context)
    photoBack.scaleType = ImageView.ScaleType.CENTER_CROP
    photoBack.alpha = 0f
    root.addView(photoBack, FrameLayout.LayoutParams(MATCH, MATCH))

    photo = ImageView(context)
    photo.scaleType = ImageView.ScaleType.CENTER_CROP
    root.addView(photo, FrameLayout.LayoutParams(MATCH, MATCH))

    videoView = SlideshowVideoView(context)
    videoView.visibility = View.GONE
    root.addView(videoView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

    // Draw the ready photograph above the SurfaceView's transparent hole until video renders.
    nativeStage = SlideshowStage(context, pixelBudget).apply {
      visibility = View.GONE
      onPresented = {
        finishNativeStartup()
        // Finite native scene admission, only while a bounded capture owns a listener.
        val style = when (settings.mode) {
          ScreensaverConfig.MODE_GALLERY_PRINT -> SlideshowStyle.GALLERY_PRINT
          ScreensaverConfig.MODE_GALLERY_SPREAD -> SlideshowStyle.GALLERY_SPREAD
          else -> null
        }
        if (style != null && !stopped && hostActive && !paused) {
          val host = when (context) {
            is PhotoFramePreviewActivity -> SlideshowStyleHost.PREVIEW
            is PhotoDreamService -> SlideshowStyleHost.DREAM
            else -> SlideshowStyleHost.UNKNOWN
          }
          FramePerformanceCapture.prepareSlideshowStyleReport(host, style)?.accept()
        }
      }
    }
    root.addView(nativeStage, FrameLayout.LayoutParams(MATCH, MATCH))

    if (!photosOnly) {
      root.addView(faceRenderer.view)
      buildCalendar(root)
    }

    // Ambient dashboard panel — full-screen glanceable card, hidden until cycled in.
    dashboardPanel = buildDashboardPanel().also {
      it.visibility = View.GONE
      root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // Welcome-back overlay — added last so it renders above photos and the Face overlay.
    welcomeOverlay = buildWelcomeOverlay()
    welcomeOverlay.visibility = View.GONE
    root.addView(welcomeOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
    viewerControls = SlideshowViewerControls(
        context, nativeStage,
        onPrevious = { prev() }, onNext = { next() }, onPause = { setPaused(it) },
        onExit = { onExit?.invoke() },
        onSave = { key, adjustment, callback ->
          nativeLoader.savePosition(key, adjustment) { saved ->
            if (saved) {
              for (at in nativeHistory.indices) {
                if (nativeHistory[at].key == key)
                  nativeHistory[at] = nativeHistory[at].copy(adjustment = adjustment)
              }
            }
            callback(saved)
          }
        },
        onEditingStart = { cancelNativeRequest() },
        currentMode = { settings.mode })
    root.addView(viewerControls, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    viewerControls.visibility = View.GONE
    return root
  }

  /** Read EXIF date/GPS for a local file off [metaIo], reverse-geocode the place, then publish
   *  the caption — guarded by [gen] so a slow lookup for a superseded photo is dropped. */
  private fun loadCaptionForLocal(path: String, g: Int) {
    if (photosOnly) return
    metaIo.execute {
      val meta = runCatching { PhotoCaption.read(ExifInterface(path)) }.getOrNull()
      publishCaption(meta, g)
    }
  }

  /** Same as [loadCaptionForLocal] but for an SMB file: a fresh read stream feeds EXIF. */
  private fun loadCaptionForSmb(path: String, g: Int) {
    if (photosOnly) return
    val src = smbSource ?: return
    metaIo.execute {
      val meta =
          runCatching { src.openStream(path)?.use { PhotoCaption.read(ExifInterface(it)) } }
              .getOrNull()
      publishCaption(meta, g)
    }
  }

  private fun publishCaption(meta: PhotoCaption.Meta?, g: Int) {
    if (meta == null || meta.isEmpty) {
      ui.post { if (g == gen) faceRenderer.setCaption(null, null) }
      return
    }
    // Resolve the place name on this background thread (network) before touching the UI.
    val place = if (meta.hasLocation) PhotoCaption.placeName(meta.lat!!, meta.lng!!) else null
    val date = PhotoCaption.formatDate(meta.dateMillis)
    // "On this day": an amber badge when the photo was taken on today's month+day in a prior year.
    val memory = PhotoCaption.memoryYears(meta.dateMillis)?.let { PhotoCaption.memoryLabel(it) }
    ui.post { if (g == gen) faceRenderer.setCaption(place, date, memory) }
  }

  /** A clean upcoming-events panel top-right, over the photo. Its own translucent
   *  rounded backing keeps it legible without a full-screen scrim. Hidden until a
   *  calendar link is set and there's something to show. */
  private fun buildCalendar(root: FrameLayout) {
    calendarPanel = LinearLayout(context)
    calendarPanel.orientation = LinearLayout.VERTICAL
    calendarPanel.visibility = View.GONE
    val bg = GradientDrawable()
    bg.setColor(0x66000000)
    bg.cornerRadius = dp(18).toFloat()
    calendarPanel.background = bg
    calendarPanel.setPadding(dp(22), dp(18), dp(22), dp(18))
    val lp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
    lp.setMargins(0, dp(40), dp(40), 0)
    root.addView(calendarPanel, lp)

    calendarHeader = text(15f, 0xCCFFFFFF.toInt(), false)
    calendarHeader.typeface = Typeface.DEFAULT_BOLD
    calendarHeader.letterSpacing = 0.08f
    calendarPanel.addView(calendarHeader)

    calendarRows = LinearLayout(context)
    calendarRows.orientation = LinearLayout.VERTICAL
    val rowsLp = LinearLayout.LayoutParams(WRAP, WRAP)
    rowsLp.topMargin = dp(10)
    calendarPanel.addView(calendarRows, rowsLp)
  }

  /** Rebuild the calendar panel from the cached events for the chosen range. Cheap
   *  (no network) — called when a fetch lands and once per minute from [tick]. */
  private fun renderCalendar() {
    if (!this::calendarPanel.isInitialized) return
    if (!settings.usesCalendar) {
      calendarPanel.visibility = View.GONE
      return
    }
    val now = System.currentTimeMillis()
    val shown = CalendarFeed.window(calendarEvents, settings.calendarRange, now)
    // Size + position can change at runtime (settings screen or a fleet push), so
    // (re)apply the chrome each render. Scale drives text/padding; side drives the edge.
    calScale =
        when (settings.calendarSize) {
          0 -> 0.82f
          2 -> 1.22f
          else -> 1f
        }
    applyCalendarChrome()
    calendarHeader.textSize = 15f * calScale
    calendarHeader.text = rangeLabel(settings.calendarRange).uppercase(Locale.getDefault())
    calendarRows.removeAllViews()
    calendarPanel.visibility = View.VISIBLE

    if (shown.isEmpty()) {
      val empty = text(17f * calScale, 0xCCFFFFFF.toInt(), false)
      empty.text = "Nothing scheduled"
      calendarRows.addView(empty)
      return
    }

    val agenda = settings.calendarRange == CalendarFeed.RANGE_AGENDA
    var lastDayKey = ""
    for (ev in shown) {
      // Day header whenever the day changes (and always in agenda mode, which spans
      // arbitrary dates). A single-day range needs no per-day header.
      val key = dayKey(ev.startMillis)
      if (key != lastDayKey && (agenda || settings.calendarRange != CalendarFeed.RANGE_DAY)) {
        val header = text(13f * calScale, 0x99FFFFFF.toInt(), false)
        header.typeface = Typeface.DEFAULT_BOLD
        header.text = dayHeader(ev.startMillis).uppercase(Locale.getDefault())
        val hlp = LinearLayout.LayoutParams(WRAP, WRAP)
        hlp.topMargin = if (calendarRows.childCount == 0) 0 else dp(12)
        calendarRows.addView(header, hlp)
      }
      lastDayKey = key
      calendarRows.addView(buildEventRow(ev))
    }
  }

  /** Position the panel against the chosen edge and scale its padding to the size. */
  private fun applyCalendarChrome() {
    val pad = (22 * calScale).toInt()
    val padV = (18 * calScale).toInt()
    calendarPanel.setPadding(dp(pad), dp(padV), dp(pad), dp(padV))
    val gravity =
        Gravity.TOP or
            (if (settings.calendarSide == ScreensaverConfig.CAL_SIDE_LEFT) Gravity.START
            else Gravity.END)
    val lp = FrameLayout.LayoutParams(WRAP, WRAP, gravity)
    val side = dp(40)
    if (settings.calendarSide == ScreensaverConfig.CAL_SIDE_LEFT) lp.setMargins(side, dp(40), 0, 0)
    else lp.setMargins(0, dp(40), side, 0)
    calendarPanel.layoutParams = lp
  }

  private fun buildEventRow(ev: CalendarFeed.Event): View {
    val row = LinearLayout(context)
    row.orientation = LinearLayout.HORIZONTAL
    row.gravity = Gravity.CENTER_VERTICAL
    val rowLp = LinearLayout.LayoutParams(WRAP, WRAP)
    rowLp.topMargin = dp(6)

    val time = text(17f * calScale, Color.WHITE, false)
    time.text = if (ev.allDay) "All day" else timeLabel(ev.startMillis)
    time.width = dp((96 * calScale).toInt())

    val title = text(17f * calScale, Color.WHITE, false)
    title.typeface = Typeface.DEFAULT_BOLD
    title.text = ev.title
    title.maxLines = 1
    title.ellipsize = TextUtils.TruncateAt.END
    title.maxWidth = dp((360 * calScale).toInt())

    row.addView(time)
    row.addView(title)
    row.layoutParams = rowLp
    return row
  }

  private fun rangeLabel(range: String): String =
      when (range) {
        CalendarFeed.RANGE_3DAY -> "Next 3 days"
        CalendarFeed.RANGE_WEEK -> "This week"
        CalendarFeed.RANGE_AGENDA -> "Upcoming"
        else -> "Today"
      }

  private fun timeLabel(millis: Long): String {
    val pattern = if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
  }

  /** A stable per-day key (yyyyDDD) so the renderer can tell when the day changes. */
  private fun dayKey(millis: Long): String =
      SimpleDateFormat("yyyyDDD", Locale.US).format(Date(millis))

  /** "Today" / "Tomorrow" / "Sat, Jun 21" relative to now. */
  private fun dayHeader(millis: Long): String {
    val today = dayKey(System.currentTimeMillis())
    val tomorrow = dayKey(System.currentTimeMillis() + 24L * 60 * 60 * 1000)
    return when (dayKey(millis)) {
      today -> "Today"
      tomorrow -> "Tomorrow"
      else -> DateFormatter.format(Date(millis), "EEEMMMd")
    }
  }

  private fun applyFit() {
    // Video gets the same choice via [applyVideoFit], sized per-clip once its dimensions are known.
    val scaleType =
        if (settings.fit == ScreensaverConfig.FIT_FIT) ImageView.ScaleType.FIT_CENTER
        else ImageView.ScaleType.CENTER_CROP
    photo.scaleType = scaleType
    if (this::photoBack.isInitialized) photoBack.scaleType = scaleType
  }

  /**
   * Honour the fit setting for the shared [videoView]. The video surface letterboxes inside its
   * own bounds (its onMeasure shrinks to the clip's aspect even with EXACTLY specs), so "fill"
   * can't be a scaleType like the photo layer: instead the view is sized to *cover* the screen
   * (aspect-preserving, the video CENTER_CROP) and the parent clips the overflow. "Fit" — or an
   * unknown clip/screen size — restores match-parent letterboxing.
   */
  private fun applyVideoFit(videoW: Int, videoH: Int) {
    val lp = videoView.layoutParams as FrameLayout.LayoutParams
    val host = videoView.parent as? View
    val cover =
        if (settings.fit == ScreensaverConfig.FIT_FILL)
            videoCoverSize(videoW, videoH, host?.width ?: 0, host?.height ?: 0)
        else null
    lp.width = cover?.first ?: MATCH
    lp.height = cover?.second ?: MATCH
    lp.gravity = Gravity.CENTER
    videoView.layoutParams = lp
  }

  // --- welcome-back overlay (fork) --------------------------------------------

  private fun buildWelcomeOverlay(): View {
    val welcomeCfg = WelcomeConfig.load(context)

    val overlay = FrameLayout(context)
    // Semi-opaque dark scrim so the photo is faintly visible behind the greeting.
    val bgAlpha = (welcomeCfg.backgroundOpacity * 255).toInt()
    overlay.setBackgroundColor((bgAlpha shl 24) or 0x000000)

    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = Gravity.CENTER_HORIZONTAL
    // Add horizontal padding to prevent text cutoff at screen edges
    col.setPadding(dp(40), dp(20), dp(40), dp(20))

    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when {
      hour < 5  -> welcomeCfg.greetingNight
      hour < 12 -> welcomeCfg.greetingMorning
      hour < 17 -> welcomeCfg.greetingAfternoon
      hour < 22 -> welcomeCfg.greetingEvening
      else      -> welcomeCfg.greetingNight
    }
    val fullGreeting = if (welcomeCfg.userName.isNotBlank()) {
      "$greeting, ${welcomeCfg.userName}"
    } else {
      greeting
    }

    if (welcomeCfg.showGreeting) {
      val greetingView = text(welcomeCfg.greetingSize, welcomeCfg.greetingColor, false)
      greetingView.text = fullGreeting
      greetingView.gravity = Gravity.CENTER
      greetingView.letterSpacing = welcomeCfg.greetingLetterSpacing
      greetingView.maxLines = 2
      val greetingLp = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT)
      col.addView(greetingView, greetingLp)
    }

    if (welcomeCfg.showClock) {
      val clockPattern = if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm"
      val clockView = text(welcomeCfg.clockSize, welcomeCfg.clockColor, true)
      clockView.text = SimpleDateFormat(clockPattern, Locale.getDefault()).format(Date())
      clockView.gravity = Gravity.CENTER
      clockView.maxLines = 1
      val clockLp = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT)
      col.addView(clockView, clockLp)
    }

    if (welcomeCfg.showDate) {
      val dateView = text(welcomeCfg.dateSize, welcomeCfg.dateColor, false)
      dateView.text = DateFormatter.format(Date(), "EEEEMMMd")
      dateView.gravity = Gravity.CENTER
      dateView.maxLines = 1
      val dateLp = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT)
      dateLp.topMargin = dp(4)
      col.addView(dateView, dateLp)
    }

    // Use MATCH_PARENT width with the padding above to ensure text stays visible
    overlay.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
    return overlay
  }

  private fun showWelcomeOverlay() {
    val welcomeCfg = WelcomeConfig.load(context)
    welcomeVisible = true
    welcomeOverlay.alpha = 0f
    welcomeOverlay.visibility = View.VISIBLE
    welcomeOverlay.animate().alpha(1f).setDuration(500).start()

    // Speak the greeting if TTS is enabled
    if (welcomeCfg.enableTts) {
      val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
      val greeting = when {
        hour < 5  -> welcomeCfg.greetingNight
        hour < 12 -> welcomeCfg.greetingMorning
        hour < 17 -> welcomeCfg.greetingAfternoon
        hour < 22 -> welcomeCfg.greetingEvening
        else      -> welcomeCfg.greetingNight
      }
      val fullGreeting = if (welcomeCfg.userName.isNotBlank()) {
        "$greeting, ${welcomeCfg.userName}"
      } else {
        greeting
      }
      // Speak immediately if TTS is ready, otherwise queue it
      if (ttsReady) {
        speakWelcome(fullGreeting, welcomeCfg)
      } else {
        pendingSpeech = fullGreeting
      }
    }

    // Auto-dismiss after configured duration.
    ui.postDelayed(dismissWelcomeRunnable, welcomeCfg.durationMs.toLong())
  }

  /** Dismiss immediately (e.g. on tap). */
  private fun dismissWelcome() {
    if (!welcomeVisible) return
    ui.removeCallbacks(dismissWelcomeRunnable)
    tts?.stop()
    pendingSpeech = null
    dismissWelcomeAnimated()
  }

  private fun dismissWelcomeAnimated() {
    welcomeVisible = false
    welcomeOverlay.animate()
        .alpha(0f)
        .setDuration(600)
        .withEndAction { welcomeOverlay.visibility = View.GONE }
        .start()
  }

  private fun speakWelcome(text: String, cfg: WelcomeConfig.Settings) {
    val params = welcomeTtsParams(cfg)
    runCatching {
      tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "welcome_greeting")
    }.onFailure { Log.w(TAG, "Welcome TTS speak failed", it) }
  }

  private fun welcomeTtsParams(cfg: WelcomeConfig.Settings): android.os.Bundle? =
      if (cfg.ttsVoice.isBlank()) {
        null
      } else {
        android.os.Bundle().apply { putString(SHERPA_VOICE_PARAM, cfg.ttsVoice) }
      }

  // --- ambient dashboard (fork) -----------------------------------------------

  private fun buildDashboardPanel(): View {
    val panel = FrameLayout(context)
    panel.setBackgroundColor(0xF20D0D12.toInt()) // near-opaque dark
    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = Gravity.CENTER
    panel.addView(col, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
    dashClock = text(120f, Color.WHITE, true).also { it.gravity = Gravity.CENTER; col.addView(it) }
    dashDate = text(28f, Color.WHITE, false).also { it.gravity = Gravity.CENTER; col.addView(it) }
    dashWeather = text(26f, Color.WHITE, false).also {
      it.gravity = Gravity.CENTER
      (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(18)
      col.addView(it)
    }
    dashEvent = text(22f, Color.WHITE, false).also {
      it.gravity = Gravity.CENTER
      (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10)
      col.addView(it)
    }
    // Almanac line(s): enabled calendar packs (Irish holidays, prayer times) + a quote of the day.
    dashAlmanac = text(20f, Color.WHITE, false).also {
      it.gravity = Gravity.CENTER
      (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(14)
      col.addView(it)
    }
    return panel
  }

  /** Shows the dashboard card for a few seconds, then returns to the photos. */
  private val dashboardCycle =
      object : Runnable {
        override fun run() {
          if (!settings.ambientDashboard) return
          showDashboard()
          ui.postDelayed({ hideDashboard() }, 9_000L)
          ui.postDelayed(this, 90_000L) // reappear roughly every 90s
        }
      }

  private fun showDashboard() {
    val panel = dashboardPanel ?: return
    val now = Date()
    val clockPattern = if (ImmortalSettings.use24HourClock(context)) "H:mm" else "h:mm"
    dashClock.text = SimpleDateFormat(clockPattern, Locale.getDefault()).format(now)
    dashDate.text = DateFormatter.format(now, "EEEEMMMMd")
    dashWeather.text = weatherText
    dashWeather.visibility = if (weatherText.isNotBlank()) View.VISIBLE else View.GONE
    dashEvent.visibility = View.GONE
    dashAlmanac.visibility = View.GONE
    io.execute {
      val lines = buildList {
        runCatching { CalendarPacks.headerLines(context) }.getOrDefault(emptyList()).forEach { add(it) }
        val q = DailyContent.quoteOfDay()
        add("“${q.text}”  — ${q.author}")
      }
      val txt = lines.joinToString("\n")
      ui.post {
        if (dashboardVisible && txt.isNotBlank()) {
          dashAlmanac.text = txt
          dashAlmanac.visibility = View.VISIBLE
        }
      }
    }
    io.execute {
      val ev = runCatching {
        if (CalendarHelper.hasPermission(context)) CalendarHelper.upcoming(context).firstOrNull() else null
      }.getOrNull()
      ui.post {
        if (ev != null) {
          val cal = Calendar.getInstance().apply { timeInMillis = ev.begin }
          val whenStr =
              if (ev.allDay) SimpleDateFormat("MMM d", Locale.getDefault()).format(cal.time)
              else SimpleDateFormat(if (ImmortalSettings.use24HourClock(context)) "HH:mm" else "h:mm a",
                  Locale.getDefault()).format(cal.time)
          dashEvent.text = "Next: $whenStr · ${ev.title}"
          if (dashboardVisible) dashEvent.visibility = View.VISIBLE
        }
      }
    }
    panel.alpha = 0f
    panel.visibility = View.VISIBLE
    panel.animate().alpha(1f).setDuration(600).start()
    dashboardVisible = true
  }

  private fun hideDashboard() {
    val panel = dashboardPanel ?: return
    panel.animate().alpha(0f).setDuration(600).withEndAction { panel.visibility = View.GONE }.start()
    dashboardVisible = false
  }

  private val refreshWeather =
      object : Runnable {
        override fun run() {
          fetchWeather()
          ui.postDelayed(this, weatherRefreshMs)
        }
      }

  private fun fetchWeather() {
    metaIo.execute {
      // Shared resilient fetch: cached location + multi-provider geolocation.
      val w = Weather.fetch(context)
      if (w.isNotBlank()) weatherText = w
    }
  }

  private fun text(sizeSp: Float, color: Int, light: Boolean): TextView {
    val t = TextView(context)
    t.textSize = sizeSp
    t.setTextColor(color)
    if (light) t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    t.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
    return t
  }

  private fun intervalMs(): Long = settings.intervalSec * 1000L
  private fun isGalleryStyle(): Boolean =
      settings.mode == ScreensaverConfig.MODE_GALLERY_PRINT ||
          settings.mode == ScreensaverConfig.MODE_GALLERY_SPREAD
  private fun analyzePeople(): Boolean =
      settings.keepPeopleInFrame && settings.mode != ScreensaverConfig.MODE_WHOLE &&
          !isGalleryStyle()

  private fun localKey(path: String): String {
    val file = java.io.File(path)
    return "local:$path:${file.length()}:${file.lastModified()}"
  }

  private fun localAsset(path: String, key: String = localKey(path)): SlideshowAsset =
      SlideshowAsset(key, { edge -> decodeBoundedFile(path, edge) }, {
        orientedImageDims(path)?.let { it.second > it.first } ?: false
      }, revisionVerified = true,
          identity = { bitmap -> synchronized(photoIdentities) { photoIdentities.remove(bitmap) } })

  private fun remoteKey(url: String): String = "remote:${settings.source}:$url"

  private fun remoteAsset(url: String): SlideshowAsset {
    val headers = remoteHeaders
    val fetch = remoteFetch
    return SlideshowAsset(remoteKey(url), { edge -> fetchRemoteImage(url, edge, headers, fetch) },
        contentIdentity = true,
        identity = { bitmap -> synchronized(photoIdentities) { photoIdentities.remove(bitmap) } })
  }

  private fun deferNativeStartup(action: () -> Unit): Boolean {
    if (!ScreensaverConfig.isNativeStage(settings.mode) || !nativeStarting) return false
    afterNativeStartup = action
    return true
  }

  private fun finishNativeStartup() {
    if (!nativeStarting || stopped) return
    nativeStarting = false
    val action = afterNativeStartup
    afterNativeStartup = null
    if (action != null) ui.post { if (!stopped) action() }
  }

  private fun cancelNativeRequest() {
    gen++
    afterNativeStartup = null
    nativeNavigation.cancelRequest()
    photoPause.cancel()
    if (nativeLoaderDelegate.isInitialized()) nativeLoader.cancel()
    // Editing an old photo must not allow a pending video to take over later.
    if (videoView.visibility == View.VISIBLE) restorePhotoAfterVideo()
  }

  private fun rememberNativePhoto(prepared: SlideshowPhoto) {
    if (nativeHistory.lastOrNull()?.key == prepared.key) nativeHistory[nativeHistory.lastIndex] = prepared
    else {
      while (nativeHistory.size >= 2) nativeHistory.removeAt(0)
      nativeHistory.add(prepared)
    }
    index = nativeHistory.lastIndex
    pixelBudget.setOwner("history", nativeHistory.map {
      SlideshowPixelBudget.Entry(it.bitmap, it.bitmap.allocationByteCount.toLong())
    })
  }

  private fun clearNativeHistory() {
    nativeHistory.clear()
    pixelBudget.setOwner("history", emptyList())
    if (ScreensaverConfig.isNativeStage(settings.mode)) index = -1
  }

  private fun measuredNativeOcclusions(): List<Rect> {
    val occupied = if (photosOnly) mutableListOf<Rect>() else faceRenderer.photoOcclusions().toMutableList()
    if (this::calendarPanel.isInitialized && calendarPanel.visibility == View.VISIBLE && calendarPanel.width > 0) {
      occupied.add(Rect(calendarPanel.left - dp(20), calendarPanel.top - dp(20),
          calendarPanel.right + dp(20), calendarPanel.bottom + dp(20)))
    }
    return occupied
  }

  private fun refreshGalleryOverlays() {
    if (stopped || !hostActive) return
    if (!photosOnly && faceRendererDelegate.isInitialized()) faceRenderer.refreshPaperPhotoSurface()
    webView?.let { wv ->
      val root = view
      if (root.width > 0 && root.height > 0) {
        val origin = IntArray(2)
        root.getLocationInWindow(origin)
        val bounds = org.json.JSONArray()
        val clocks = if (photosOnly) emptyList() else faceRenderer.photoOcclusions().map { area ->
          SlideshowPresentationPolicy.Bounds(area.left, area.top, area.right, area.bottom)
        }
        val calendar = if (this::calendarPanel.isInitialized &&
            calendarPanel.visibility == View.VISIBLE && calendarPanel.width > 0) {
          listOf(SlideshowPresentationPolicy.Bounds(calendarPanel.left - dp(20), calendarPanel.top - dp(20),
              calendarPanel.right + dp(20), calendarPanel.bottom + dp(20)))
        } else emptyList()
        SlideshowPresentationPolicy.paperOcclusions(origin[0], origin[1], clocks, calendar).forEach { area ->
          bounds.put(JSONObject().put("left", area.left).put("top", area.top)
              .put("right", area.right).put("bottom", area.bottom))
        }
        val snapshot = JSONObject().put("width", root.width).put("height", root.height)
            .put("occupied", bounds).toString()
        if (legacyPhotoLayoutBounds != snapshot) {
          legacyPhotoLayoutBounds = snapshot
          wv.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
        }
      }
    }
    if (!isGalleryStyle() ||
        !this::nativeStage.isInitialized || nativeStage.visibility != View.VISIBLE ||
        (this::viewerControls.isInitialized && viewerControls.isEditing)) return
    nativeStage.refreshGalleryOverlays(measuredNativeOcclusions())
  }

  private fun presentNative(photos: List<SlideshowPhoto>, automatic: Boolean = true) {
    if (stopped) return
    nativeStage.visibility = View.VISIBLE
    photo.visibility = View.GONE
    photoBack.visibility = View.GONE
    if (paused) nativeStage.pause() else nativeStage.resume()
    nativeStage.show(photos,
        settings.fit == ScreensaverConfig.FIT_FILL && settings.mode != ScreensaverConfig.MODE_WHOLE,
        settings.photoMotion && settings.mode != ScreensaverConfig.MODE_WHOLE,
        intervalMs(), measuredNativeOcclusions(),
        galleryPrint = settings.mode == ScreensaverConfig.MODE_GALLERY_PRINT,
        gallerySpread = settings.mode == ScreensaverConfig.MODE_GALLERY_SPREAD,
        automatic = automatic)
    viewerControls.photoChanged()
    Log.i("ImmortalSlideshow", "ready native scene: photos=${photos.size}, pixels=${photos.sumOf { it.bitmap.width.toLong() * it.bitmap.height }}")
  }

  private fun armHold(task: Runnable, delay: Long = intervalMs()) {
    ui.removeCallbacks(task)
    holdDeadline = playbackUptime() + delay
    remainingHold = delay
    if (!paused && !stopped) ui.postDelayed(task, delay)
  }

  private fun setPaused(value: Boolean) {
    if (paused == value) return
    paused = value
    val presentedHeldPhoto = photoPause.setPaused(value)
    legacyFaceStore?.setActive(hostActive && !value)
    nativeFilmstrip?.setPaused(value)
    hybridFilmstrip?.setPaused(value)
    if (hybridFilmstrip != null) webView?.let(::applyEnginePlayback)
    if (!value && hostActive) legacyWarmup?.resume()
    if (value) {
      remainingHold = (holdDeadline - playbackUptime()).coerceAtLeast(100L)
      ui.removeCallbacks(localTick); ui.removeCallbacks(remoteTick); ui.removeCallbacks(rotate)
      nativeStage.pause()
      if (videoView.visibility == View.VISIBLE) runCatching { videoView.pause() }
    } else {
      if (videoView.visibility != View.VISIBLE) nativeStage.resume()
      if (hostActive && videoView.visibility == View.VISIBLE) runCatching { videoView.start() }
      val tick = when { localMode -> localTick; remoteMode -> remoteTick; else -> rotate }
      // A still-decoding photo owns the next advance. Its delivery arms the full dwell.
      if (!presentedHeldPhoto && !photoPause.pending)
        armHold(tick, remainingHold.coerceAtLeast(100L))
    }
  }

  fun dispatchViewerControls(event: MotionEvent): Boolean =
      !isNightClockShowing && interactivePreview && nativePhotoMode &&
          viewerControls.dispatchViewerTouch(event)

  fun updatePreviewInteraction(enabled: Boolean) {
    interactivePreview = enabled
    if (!enabled && this::viewerControls.isInitialized) {
      viewerControls.resetInteraction()
      setPaused(false)
    }
  }

  // --- periodic loops ---------------------------------------------------------
  private val calendarTick =
      CalendarMinuteRefresh(
          clock = System::currentTimeMillis,
          cancel = ui::removeCallbacks,
          schedule = { task, delay -> ui.postDelayed(task, delay) },
          refresh = ::renderCalendar)

  private val rotate =
      object : Runnable {
        override fun run() {
          webNext()
          if (!ScreensaverConfig.isNativeStage(settings.mode)) ui.postDelayed(this, intervalMs())
        }
      }

  private val refreshCalendar =
      object : Runnable {
        override fun run() {
          // Pick up calendar changes pushed over the fleet API (or via in-app
          // settings) without restarting the dream: reload just the calendar fields,
          // leaving the live slideshow state untouched.
          val fresh = ScreensaverConfig.load(context)
          if (fresh.calendarUrl != settings.calendarUrl ||
              fresh.calendarRange != settings.calendarRange) {
            settings = settings.copy(calendarUrl = fresh.calendarUrl, calendarRange = fresh.calendarRange)
            renderCalendar()
          }
          val url = settings.calendarUrl
          if (settings.usesCalendar && !url.isNullOrBlank()) {
            metaIo.execute {
              val events = CalendarFeed.fetch(url)
              ui.post {
                calendarEvents = events
                renderCalendar()
              }
            }
          } else {
            calendarEvents = emptyList()
            renderCalendar()
          }
          ui.postDelayed(this, calendarRefreshMs)
        }
      }
  // --- navigation (branches on the active source) -----------------------------
  fun next() {
    if (stopped) return
    if (deferNativeStartup { next() }) return
    viewerControls.cancelEdit()
    if (viewerControls.isEditing) return
    photoPause.beforeManualNavigation(::cancelNativeRequest)
    when {
      nativeFilmstrip != null -> nativeFilmstrip?.next()
      engineActive -> webView?.evaluateJavascript("window.immortalSlideshow?.next()", null)
      localMode -> advanceLocal(+1, automatic = false)
      remoteMode -> advanceRemote(+1, automatic = false)
      else -> webNext(automatic = false)
    }
  }

  fun prev() {
    if (stopped) return
    if (deferNativeStartup { prev() }) return
    viewerControls.cancelEdit()
    if (viewerControls.isEditing) return
    photoPause.beforeManualNavigation(::cancelNativeRequest)
    when {
      nativeFilmstrip != null -> nativeFilmstrip?.previous()
      engineActive -> webView?.evaluateJavascript("window.immortalSlideshow?.previous()", null)
      localMode -> advanceLocal(-1, automatic = false)
      remoteMode -> advanceRemote(-1, automatic = false)
      else -> webPrev()
    }
  }

  // --- local folder playback --------------------------------------------------
  private val localTick =
      object : Runnable {
        override fun run() {
          advanceLocal(+1)
        }
      }

  private fun advanceLocal(dir: Int, automatic: Boolean = true) {
    if (stopped) return
    if (deferNativeStartup { advanceLocal(dir, automatic) }) return
    if (viewerControls.isEditing) return
    ui.removeCallbacks(localTick)
    if (playlist.isEmpty()) {
      startWeb(automatic)
      return
    }
    gen++
    if (ScreensaverConfig.isNativeStage(settings.mode)) {
      clearNativeHistory()
      localIndex = nativeNavigation.request(dir, localDeckKeys)
    } else localIndex = ((localIndex + dir) % playlist.size + playlist.size) % playlist.size
    val item = playlist[localIndex]
    if (item.isVideo) showVideo(item.path, gen, automatic) else showLocalImage(item.path, gen, automatic)
  }

  private fun showLocalImage(path: String, g: Int, automatic: Boolean) {
    if (ScreensaverConfig.isNativeStage(settings.mode)) {
      photoPause.requested(automatic)
      val anchor = localIndex
      val partners = (1 until minOf(playlist.size, 2)).map { offset ->
        (anchor + offset) % playlist.size
      }.filterNot { playlist[it].isVideo }.map { localAsset(playlist[it].path, localDeckKeys[it]) }
      val nextIndex = (anchor + 1) % playlist.size
      val next = playlist.getOrNull(nextIndex)?.takeUnless { it.isVideo }
          ?.let { localAsset(it.path, localDeckKeys[nextIndex]) }
      nativeLoader.load(localAsset(path, localDeckKeys[anchor]), partners, next,
          settings.mode == ScreensaverConfig.MODE_PORTRAITS ||
              settings.mode == ScreensaverConfig.MODE_GALLERY_SPREAD, analyzePeople(),
          { photos, partner ->
            if (g != gen || stopped) return@load
            photoPause.deliver(photos) { ready ->
              nativeFailures = 0
              stopVideo()
              nativeNavigation.commit(ready.first().sourceKey, partner)
              presentNative(ready, automatic)
              loadCaptionForLocal(path, g)
              armHold(localTick)
            }
          }, {
            nativeNavigation.skipFailed()
            if (++nativeFailures >= playlist.size) startWeb(automatic) else advanceLocal(+1, automatic)
          }, nextAfterPartner = { key ->
            val at = localDeckKeys.indexOf(key)
            val nextAt = (at + 1) % playlist.size
            playlist.getOrNull(nextAt)?.takeUnless { it.isVideo }
                ?.let { localAsset(it.path, localDeckKeys[nextAt]) }
          })
      return
    }
    stopVideo()
    io.execute {
      // Navigation can supersede requests while they wait behind another decode.
      if (g != gen || stopped) return@execute
      val bmp = runCatching { decodeCorrected(path) }.getOrNull()
      if (g != gen || stopped) { bmp?.recycle(); return@execute }
      ui.postOwned(Runnable {
        if (g != gen || stopped) { bmp?.recycle(); return@Runnable }
        if (bmp == null) {
          // A folder of unreadable images must settle on the existing fallback.
          if (++localImageFailStreak >= playlist.size) startWeb(automatic)
          else advanceLocal(+1, automatic)
          return@Runnable
        }
        photo.visibility = View.VISIBLE
        show(bmp)
        localImageFailStreak = 0
        loadCaptionForLocal(path, g)
        ui.postDelayed(localTick, intervalMs())
      }, onDiscard = { bmp?.let { if (!it.isRecycled) it.recycle() } })
    }
  }

  /**
   * Downsample any photo to a safe size before it ever reaches the [ImageView].
   *
   * A full-resolution camera original is enormous once decoded: a ~39MP shot becomes a ~157MB
   * ARGB_8888 bitmap, which is larger than the hardware Canvas can draw (~100MB). The draw then
   * throws "trying to draw too large bitmap" on the main thread and crashes the whole launcher —
   * and because Android responds to a *home app* crashing by clearing its preferred-activity (HOME)
   * association, the very next Home press pops the "Select Home app" chooser. Bounding the longest
   * edge to [MAX_EDGE] here (the same two-pass trick used by [Thumbnails]/[MediaArt]) keeps every
   * source — folder, SMB, Immich, WebDAV, bundled — well under the cap, so it can't happen.
   *
   * The bound never upscales (small images decode untouched) and uses a power-of-two
   * [BitmapFactory.Options.inSampleSize], the only value the decoder honours efficiently.
   */
  private fun decodeBoundedFile(path: String, maxEdge: Int = 2400): Bitmap? {
    if (!ScreensaverConfig.isNativeStage(settings.mode)) return SlideshowDecoder.file(path, maxEdge)
    return runCatching {
      java.io.File(path).inputStream().use { decodeBoundedStream(it, maxEdge) }
    }.getOrNull()
  }

  /** Stream variant: buffers to bytes so the bounds pass can run before the real decode. */
  private fun decodeBoundedStream(input: java.io.InputStream, maxEdge: Int = 2400): Bitmap? {
    if (!ScreensaverConfig.isNativeStage(settings.mode)) return SlideshowDecoder.stream(input, maxEdge)
    val bytes = SlideshowDecoder.readEncoded(input) ?: return null
    return decodeBoundedBytes(bytes, maxEdge)
  }

  /** Two-pass decode of in-memory image bytes, bounded to [MAX_EDGE] (see [sampleSizeFor]). */
  private fun decodeBoundedBytes(bytes: ByteArray, maxEdge: Int = 2400): Bitmap? {
    val bitmap = SlideshowDecoder.bytes(bytes, maxEdge) ?: return null
    if (ScreensaverConfig.isNativeStage(settings.mode)) {
      // Encoded identity survives sampling, presentation resizing, and changes of serving URL.
      val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
          .joinToString("") { "%02x".format(it.toInt() and 255) }
      synchronized(photoIdentities) { photoIdentities[bitmap] = "encoded:$hash" }
    }
    return bitmap
  }

  /**
   * Decode a local image and apply its EXIF orientation. Phone cameras save the
   * raw sensor buffer and record the intended rotation in an EXIF tag rather than
   * baking it into the pixels, so [BitmapFactory.decodeFile] alone shows portrait
   * shots sideways and some landscapes upside-down. The web feed is unaffected (its
   * images carry no rotation flag), so this only matters for the folder source.
   *
   * Reading the tag is best-effort: a missing or unreadable EXIF block falls back to
   * the upright orientation so a quirky file still shows (just unrotated) instead of
   * being skipped.
   */
  private fun decodeCorrected(path: String): Bitmap? = SlideshowDecoder.file(path)

  /**
   * Fast ORIENTED dimensions of a local image: a header-only bounds decode plus the EXIF
   * orientation tag, swapping W/H for the quarter-turn orientations so a portrait shot saved as a
   * landscape sensor buffer reports as portrait. Feeds the engine's orientation-aware mode
   * scheduler (modes.js buckets by W/H) without decoding pixels. Null if the header is unreadable.
   */
  private fun orientedImageDims(path: String): Pair<Int, Int>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { BitmapFactory.decodeFile(path, bounds) }
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    val orient =
        runCatching {
              ExifInterface(path)
                  .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val swap =
        orient == ExifInterface.ORIENTATION_ROTATE_90 ||
            orient == ExifInterface.ORIENTATION_ROTATE_270 ||
            orient == ExifInterface.ORIENTATION_TRANSPOSE ||
            orient == ExifInterface.ORIENTATION_TRANSVERSE
    return if (swap) h to w else w to h
  }

  private fun showVideo(path: String, g: Int, automatic: Boolean) {
    beginVideo(g, localDeckKeys.getOrNull(localIndex) ?: localKey(path)) {
      nativeFailures = 0
      localImageFailStreak = 0
    }
    fun failed() {
      if (g != gen || stopped) return
      restorePhotoAfterVideo()
      nativeNavigation.skipFailed()
      if (ScreensaverConfig.isNativeStage(settings.mode) && ++nativeFailures >= playlist.size) startWeb(automatic)
      else advanceLocal(+1, automatic)
    }
    runCatching {
          videoView.setOnPreparedListener { width, height ->
            // Some streams only report their real dimensions after prepare.
            videoView.setOnVideoSizeChangedListener { w, h -> if (g == gen) applyVideoFit(w, h) }
            if (g == gen) {
              applyVideoFit(width, height)
              if (hostActive && !paused && !stopped) {
                videoView.start()
              }
            }
          }
          videoView.setOnCompletionListener {
            ui.post {
              if (g == gen && !stopped) {
                if (!videoReady) nativeNavigation.skipFailed()
                advanceLocal(+1)
              }
            }
          }
          videoView.setOnErrorListener { _, _ ->
            ui.post { failed() }
          }
          videoView.setVideoPath(path)
          // Safety net: advance even if a clip is very long or never reports done.
          armHold(localTick, maxOf(intervalMs(), 5 * 60_000L))
        }
        .onFailure { failed() }
  }

  private fun beginVideo(g: Int, key: String, onRendered: () -> Unit) {
    stopVideo()
    val native = ScreensaverConfig.isNativeStage(settings.mode)
    if (native) {
      photoPause.cancel()
      nativeLoader.cancel()
      nativeStage.pause()
      if (nativeStage.currentPhoto != null) nativeStage.visibility = View.VISIBLE
    }
    cancelKenBurns()
    faceRenderer.setCaption(null, null)
    photo.setImageDrawable(null)
    photo.visibility = View.GONE
    photoBack.setImageDrawable(null)
    photoBack.visibility = View.GONE
    videoView.visibility = View.VISIBLE
    videoView.setOnInfoListener { what, _ ->
      if (g == gen && !stopped && what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
        videoReady = true
        if (native) {
          nativeNavigation.commit(key, null)
          nativeStage.visibility = View.GONE
          viewerControls.photoChanged()
        }
        onRendered()
      }
    }
    if (native) viewerControls.photoChanged()
  }

  private fun restorePhotoAfterVideo() {
    stopVideo()
    if (ScreensaverConfig.isNativeStage(settings.mode) && nativeStage.currentPhoto != null) {
      nativeStage.visibility = View.VISIBLE
      if (paused) nativeStage.pause() else nativeStage.resume()
      viewerControls.photoChanged()
    }
  }

  private fun stopVideo() {
    retainedVideoPosition = 0
    if (this::videoView.isInitialized && videoView.visibility != View.GONE) {
      runCatching { videoView.stopPlayback() }
      videoView.visibility = View.GONE
      // After video the photo layers are both hidden; reset the crossfade state to a
      // clean baseline so the next show() call starts a normal front=photo, back=photoBack
      // transition rather than an inverted one.
      if (this::photo.isInitialized && this::photoBack.isInitialized) {
        photo.alpha = 1f
        photoBack.alpha = 0f
        photoBackIsFront = false
      }
    }
    videoReady = false
  }

  // --- remote album playback (iCloud / Google Photos public shares) -----------
  private val remoteTick =
      object : Runnable {
        override fun run() {
          advanceRemote(+1)
        }
      }

  // Shuffle is applied once at start, not on refresh — re-shuffling every tick
  // would scramble the user's current position.
  private val remoteRefresh =
      object : Runnable {
        override fun run() {
          if (!remoteMode) return
          val shareUrl = settings.albumUrl
          if (shareUrl.isNullOrBlank()) return
          val m = context.resources.displayMetrics
          io.execute {
            val fresh = RemoteAlbum.fetch(shareUrl, m.widthPixels, m.heightPixels)
            val urls = fresh?.photoUrls.orEmpty()
            ui.post {
              if (stopped || !remoteMode) return@post
              if (remoteMode && urls.isNotEmpty()) {
                applyRemoteManifest(urls, fresh?.headers.orEmpty())
                remoteFailStreak = 0
              }
              scheduleRemoteRefresh()
            }
          }
        }
      }

  private fun scheduleRemoteRefresh() {
    if (stopped || !remoteMode) return
    ui.removeCallbacks(remoteRefresh)
    ui.postDelayed(remoteRefresh, settings.albumRefreshMin * 60_000L)
  }

  // --- engine-mode album auto-refresh ------------------------------------------
  // The engine snapshots its photo list at startEngine(); this re-fetches the share on the
  // user's refresh interval and restarts the engine ONLY when the set actually changed —
  // so albums stay live (new photos appear, removed ones drop) without ever interrupting
  // the show for a no-op. The restart itself is cheap: the engine rebuilds from cache-warm
  // URLs, and the shuffle-beat crossfade hides the swap.
  private var engineAlbumRefresh: Runnable? = null
  private data class EngineAlbumPlan(val shareUrl: String, val current: List<String>, val shuffle: Boolean,
      val headers: Map<String, String>)
  private var engineAlbumPlan: EngineAlbumPlan? = null

  private fun scheduleEngineAlbumRefresh(
      shareUrl: String, current: List<String>, shuffle: Boolean,
      currentHeaders: Map<String, String> = emptyMap(),
  ) {
    engineAlbumRefresh?.let { ui.removeCallbacks(it) }
    engineAlbumPlan = EngineAlbumPlan(shareUrl,current.toList(),shuffle,currentHeaders.toMap())
    val owner = legacyEngineRevision
    val task = object : Runnable {
      override fun run() {
        if (!engineActive || stopped || owner != legacyEngineRevision) return
        val m = context.resources.displayMetrics
        io.execute {
          val album = RemoteAlbum.fetch(shareUrl, m.widthPixels, m.heightPixels)
          val fresh = album?.photoUrls.orEmpty()
          val headers = album?.headers.orEmpty()
          ui.post {
            if (!engineActive || stopped || owner != legacyEngineRevision) return@post
            if (fresh.isNotEmpty() && (fresh.toSet() != current.toSet() || headers != currentHeaders)) {
              Log.i(TAG, "album changed (${current.size} -> ${fresh.size} photos); restarting engine")
              val ordered = if (shuffle) fresh.shuffled() else fresh
              startEngine(ordered, authHeaders = headers)
              scheduleEngineAlbumRefresh(shareUrl, ordered, shuffle, headers)
            } else {
              // Unchanged or transient failure: keep showing what we have, try again later.
              ui.postDelayed(this, settings.albumRefreshMin * 60_000L)
            }
          }
        }
      }
    }
    engineAlbumRefresh = task
    ui.postDelayed(task, settings.albumRefreshMin * 60_000L)
  }

  private fun applyRemoteManifest(urls: List<String>, headers: Map<String, String>) {
    if (urls == remoteUrls && headers == remoteHeaders) return
    val oldUrl = remoteUrls.getOrNull(remoteIndex)
    val keepVideo = videoView.visibility == View.VISIBLE && oldUrl != null && oldUrl in urls
    remoteUrls = urls
    remoteHeaders = headers
    if (ScreensaverConfig.isNativeStage(settings.mode)) {
      if (!keepVideo) cancelNativeRequest()
      nativeNavigation.refresh(urls.map(::remoteKey))
      remoteIndex = if (keepVideo) urls.indexOfFirst { it == oldUrl }
          else urls.indexOfFirst { remoteKey(it) == nativeNavigation.currentKey }
      if (!keepVideo && !viewerControls.isEditing)
        armHold(remoteTick, (holdDeadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(100L))
    } else remoteIndex = urls.indexOfFirst { it == oldUrl }
  }

  private fun advanceRemote(dir: Int, automatic: Boolean = true) {
    if (stopped) return
    if (deferNativeStartup { advanceRemote(dir, automatic) }) return
    if (viewerControls.isEditing) return
    ui.removeCallbacks(remoteTick)
    if (remoteUrls.isEmpty()) {
      startWeb(automatic)
      return
    }
    // One failure per URL = the whole album is unreachable. For a public-album share this
    // usually means its signed image URLs have simply expired, so try to re-mint fresh ones
    // before giving up to the built-in feed (see [reresolveOrFallback]).
    if (remoteFailStreak >= remoteUrls.size) {
      reresolveOrFallback(automatic)
      return
    }
    gen++
    if (ScreensaverConfig.isNativeStage(settings.mode)) {
      clearNativeHistory()
      remoteIndex = nativeNavigation.request(dir, remoteUrls.map(::remoteKey))
    } else remoteIndex = ((remoteIndex + dir) % remoteUrls.size + remoteUrls.size) % remoteUrls.size
    val url = remoteUrls[remoteIndex]
    val g = gen
    if (url in remoteVideos) {
      // Prefer a cached screen-sized derivative (plays from disk, no network). On a miss, stream
      // the original this once and warm the cache in the background for the next loop.
      val cached = mediaCache?.getIfPresent(url, isVideo = true)
      if (cached != null) {
        showRemoteVideo(android.net.Uri.fromFile(cached), emptyMap(), g, cachedFile = cached,
            automatic = automatic)
      } else {
        showRemoteVideo(android.net.Uri.parse(url), remoteHeaders, g, automatic = automatic)
        schedulePrefetch()
      }
      return
    }
    if (ScreensaverConfig.isNativeStage(settings.mode)) {
      photoPause.requested(automatic)
      val anchor = remoteIndex
      val deck = remoteUrls
      val partners = (1 until minOf(deck.size, 2)).map { offset -> deck[(anchor + offset) % deck.size] }
          .filterNot { it in remoteVideos }.map(::remoteAsset)
      val next = deck.getOrNull((anchor + 1) % deck.size)?.takeUnless { it in remoteVideos }
          ?.let(::remoteAsset)
      nativeLoader.load(remoteAsset(url), partners, next,
          settings.mode == ScreensaverConfig.MODE_PORTRAITS ||
              settings.mode == ScreensaverConfig.MODE_GALLERY_SPREAD, analyzePeople(),
          { photos, partner ->
            if (g != gen || stopped) return@load
            photoPause.deliver(photos) { ready ->
              stopVideo()
              remoteFailStreak = 0; remoteReresolveStreak = 0
              nativeNavigation.commit(ready.first().sourceKey, partner)
              presentNative(ready, automatic)
              if (smbSource != null) loadCaptionForSmb(url, g) else faceRenderer.setCaption(null, null)
              armHold(remoteTick)
            }
          }, { nativeNavigation.skipFailed(); remoteFailStreak++; advanceRemote(+1, automatic) }, nextAfterPartner = { key ->
            val at = deck.indexOfFirst { remoteKey(it) == key }
            deck.getOrNull((at + 1) % deck.size)?.takeUnless { it in remoteVideos }?.let(::remoteAsset)
          })
      return
    }
    stopVideo()
    io.execute {
      if (g != gen || stopped) return@execute
      val bmp = runCatching { fetchRemoteImage(url) }.getOrNull()
      if (g != gen || stopped) { bmp?.recycle(); return@execute }
      ui.postOwned(Runnable {
        if (g != gen || stopped) { bmp?.recycle(); return@Runnable }
        if (!remoteMode) { bmp?.recycle(); return@Runnable } // raced with startWeb() flipping us off
        if (bmp == null) {
          remoteFailStreak++
          advanceRemote(+1)
          return@Runnable
        }
        remoteFailStreak = 0
        remoteReresolveStreak = 0
        photo.visibility = View.VISIBLE
        show(bmp)
        // EXIF caption only for SMB here — it reads the user's own files. The HTTP remote sources
        // (iCloud/Google/Immich/DAV) serve EXIF-stripped images, so they carry no caption.
        if (smbSource != null) loadCaptionForSmb(url, g) else faceRenderer.setCaption(null, null)
        ui.postDelayed(remoteTick, intervalMs())
      }, onDiscard = { bmp?.let { if (!it.isRecycled) it.recycle() } })
    }
  }

  /**
   * Play a remote video through the shared [videoView] from [uri]: either an Immich/WebDAV
   * playback URL (with [headers] carrying the server's auth) streamed live, or a `file://` URI
   * for a cached screen-sized derivative (empty headers, [cachedFile] set). Mirrors the local
   * [showVideo] — muted, advance on completion, skip on error.
   *
   * A playback error on a *cached* file deletes it: a truncated or codec-incompatible derivative
   * would otherwise fail every loop forever — and because the cache-hit lookup touches the file's
   * LRU stamp, eviction would actually protect the corrupt entry. Deleting it makes the next
   * encounter stream the original and re-transcode, so the slot self-heals like the image path.
   */
  private fun showRemoteVideo(
      uri: android.net.Uri,
      headers: Map<String, String>,
      g: Int,
      cachedFile: java.io.File? = null,
      automatic: Boolean = true,
  ) {
    beginVideo(g, remoteKey(remoteUrls[remoteIndex])) {
      remoteFailStreak = 0
      remoteReresolveStreak = 0
    }
    fun dropCorruptCache() {
      cachedFile?.let { f ->
        Log.w(TAG, "cached video failed to play; deleting for re-fetch: ${f.name}")
        runCatching { f.delete() }
        prefetchFull = false // space freed; let the prefetch worker re-warm this slot
      }
    }
    fun failed() {
      if (g != gen || stopped) return
      dropCorruptCache()
      restorePhotoAfterVideo()
      nativeNavigation.skipFailed()
      remoteFailStreak++
      advanceRemote(+1, automatic)
    }
    runCatching {
          videoView.setOnPreparedListener { width, height ->
            // Some streams only report their real dimensions after prepare.
            videoView.setOnVideoSizeChangedListener { w, h -> if (g == gen) applyVideoFit(w, h) }
            if (g == gen) {
              applyVideoFit(width, height)
              if (hostActive && !paused && !stopped) {
                videoView.start()
              }
            }
          }
          videoView.setOnCompletionListener {
            ui.post {
              if (g == gen && !stopped) {
                if (!videoReady) nativeNavigation.skipFailed()
                advanceRemote(+1)
              }
            }
          }
          videoView.setOnErrorListener { _, _ ->
            ui.post { failed() }
          }
          videoView.setVideoURI(uri, headers)
          // Safety net: advance even if a clip is very long or never reports done.
          armHold(remoteTick, maxOf(intervalMs(), 5 * 60_000L))
        }
        .onFailure { failed() }
  }

  /**
   * A full loop of failed downloads on a public-album share almost always means its signed image
   * URLs have expired (iCloud/Google hand out short-lived links), not that the album is gone. So
   * re-fetch fresh URLs and keep playing rather than reverting to the built-in feed — the bug
   * where a "refresh" silently dropped a working shared album back to the stock screensaver.
   *
   * Only the public-album URL source mints expiring links; the others (Immich/DAV/SMB) use stable
   * endpoints, so a full failure loop there is a genuine outage and falls back as before.
   * [remoteReresolveStreak] caps consecutive re-resolves (reset on any successful photo) so a real,
   * sustained outage still settles on the fallback instead of re-fetching forever.
   */
  private fun reresolveOrFallback(automatic: Boolean = true) {
    if (stopped) return
    if (remoteReresolving) {
      remoteRecovery.request(automatic)
      return
    }
    val shareUrl = settings.albumUrl
    if (!settings.usesUrl || shareUrl.isNullOrBlank() || remoteReresolveStreak >= MAX_RERESOLVE) {
      startWeb(automatic)
      return
    }
    remoteRecovery.request(automatic)
    remoteReresolveStreak++
    val m = context.resources.displayMetrics
    io.execute {
      val fresh = RemoteAlbum.fetch(shareUrl, m.widthPixels, m.heightPixels)
      val urls = fresh?.photoUrls.orEmpty()
      ui.post {
        val recoveredAutomatic = remoteRecovery.finish()
        if (!remoteMode || stopped) return@post // raced with a source change / exit
        if (urls.isNotEmpty()) {
          applyRemoteManifest(if (settings.shuffle) urls.shuffled() else urls, fresh?.headers.orEmpty())
          remoteFailStreak = 0
          advanceRemote(+1, recoveredAutomatic)
        } else {
          // Album genuinely unreachable / unshared → don't leave the frame spinning.
          startWeb(recoveredAutomatic)
        }
      }
    }
  }

  // --- web feed (default + fallback) ------------------------------------------
  private fun startWeb(automatic: Boolean = true) {
    if (stopped) return
    if (engineActive || legacyFaceStore != null) retireLegacyEngine()
    if (deferNativeStartup { startWeb(automatic) }) return
    if (ScreensaverConfig.isNativeStage(settings.mode)) {
      cancelNativeRequest()
      nativeNavigation.reset()
      if (localMode || remoteMode) clearNativeHistory()
    }
    localMode = false
    remoteMode = false
    remoteHeaders = emptyMap()
    remoteVideos = emptySet()
    remoteFetch = null
    smbSource?.let(::closeSmbSourceAsync)
    smbSource = null
    ui.removeCallbacks(localTick)
    ui.removeCallbacks(remoteTick)
    ui.removeCallbacks(remoteRefresh)
    restorePhotoAfterVideo()
    webNext(automatic)
    if (!ScreensaverConfig.isNativeStage(settings.mode)) ui.postDelayed(rotate, intervalMs())
  }

  /** Forward through history, loading a fresh photo when at the end. */
  private fun webNext(automatic: Boolean = true) {
    if (stopped || viewerControls.isEditing) return
    if (deferNativeStartup { webNext(automatic) }) return
    if (ScreensaverConfig.isNativeStage(settings.mode)) {
      ui.removeCallbacks(rotate)
      cancelNativeRequest()
      if (index in 0 until nativeHistory.lastIndex) {
        index++
        presentNativeHistory(nativeHistory[index], automatic)
        armHold(rotate)
      } else loadFresh(automatic)
      return
    }
    if (index in 0 until history.size - 1) {
      index++
      show(history[index])
    } else {
      loadFresh(automatic)
    }
  }

  /** Back through history (no-op at the start). */
  private fun webPrev() {
    if (stopped || viewerControls.isEditing) return
    if (deferNativeStartup { webPrev() }) return
    if (ScreensaverConfig.isNativeStage(settings.mode)) {
      ui.removeCallbacks(rotate)
      cancelNativeRequest()
      if (index > 0) index--
      nativeHistory.getOrNull(index)?.let { presentNativeHistory(it, automatic = false) }
      armHold(rotate)
      return
    }
    if (index > 0) {
      index--
      show(history[index])
    }
  }

  private fun presentNativeHistory(photo: SlideshowPhoto, automatic: Boolean) {
    val regions = if (analyzePeople()) nativeLoader.cachedRegions(photo.key).orEmpty() else emptyList()
    presentNative(listOf(photo.copy(regions = regions)), automatic)
  }

  private fun loadFresh(automatic: Boolean = true) {
    if (ScreensaverConfig.isNativeStage(settings.mode)) {
      val g = ++gen
      photoPause.requested(automatic)
      nativeLoader.load(SlideshowAsset("feed", { fetchWebPhoto() }, contentIdentity = true,
              identity = { bitmap -> synchronized(photoIdentities) { photoIdentities.remove(bitmap) } }),
          emptyList(), null, false, analyzePeople(),
          { photos, _ ->
            if (g != gen || stopped) return@load
            photoPause.deliver(photos) { ready ->
              stopVideo()
              rememberNativePhoto(ready.first())
              presentNative(ready, automatic)
              armHold(rotate)
            }
          }, { if (g == gen && !stopped) { photoPause.cancel(); armHold(rotate) } })
      return
    }
    io.execute {
      val bmp = fetchWebPhoto() ?: return@execute
      ui.postOwned(Runnable {
        if (stopped) { bmp.recycle(); return@Runnable }
        photo.visibility = View.VISIBLE
        if (history.size >= 6) history.removeAt(0) // cap memory; GC reclaims
        history.add(bmp)
        index = history.size - 1
        show(bmp)
      }, onDiscard = { if (!bmp.isRecycled) bmp.recycle() })
    }
  }

  // The default feed (and the universal fallback for any other source that's unset,
  // empty, or unreachable) walks an ordered chain of keyless sources and returns the
  // first image it can fetch:
  //   1. Unsplash  — only when an API key was supplied (richest, but rate-limited)
  //   2. Picsum    — keyless; the historical default
  //   3. Wikimedia — Commons "Featured pictures of landscapes"; keyless, and on
  //                  infrastructure independent of Picsum (the point of having it)
  //   4. Bundled   — CC0/public-domain photos shipped in the APK; can't fail, so a
  //                  fresh device with no network yet — or a day every web source is
  //                  down (e.g. the Picsum outage that prompted this) — is never blank.
  private val webSources: List<Pair<String, (Long) -> Bitmap?>> by lazy {
    buildList {
      if (unsplashKey.isNotBlank()) add("unsplash" to ::unsplashBitmap)
      add("picsum" to ::picsumBitmap)
      add("wikimedia" to ::wikimediaBitmap)
      add("bundled" to { generation: Long -> if (photoRequestCurrent(generation)) bundledBitmap() else null })
    }
  }

  private fun fetchWebPhoto(): Bitmap? {
    val generation = photoTransportGeneration.get()
    val now = System.currentTimeMillis()
    for ((name, fetch) in webSources) {
      if (!photoRequestCurrent(generation)) return null
      if ((sourceCooldownUntil[name] ?: 0L) > now) continue // dead recently; skip until cooldown
      val bmp = runCatching { fetch(generation) }.getOrNull()
      if (!photoRequestCurrent(generation)) { bmp?.recycle(); return null }
      if (bmp != null) return bmp
      Log.w(TAG, "screensaver photo source '$name' unavailable; backing off")
      sourceCooldownUntil[name] = now + SOURCE_COOLDOWN_MS
    }
    // Everything failed or is cooling down (e.g. all remotes down *and* the bundled
    // assets are unreadable). Retry the whole chain ignoring cooldowns rather than
    // leave the frame blank.
    for ((_, fetch) in webSources) {
      if (!photoRequestCurrent(generation)) return null
      val bmp = runCatching { fetch(generation) }.getOrNull()
      if (!photoRequestCurrent(generation)) { bmp?.recycle(); return null }
      if (bmp != null) return bmp
    }
    Log.w(TAG, "screensaver: no photo source available")
    return null
  }

  private fun show(bmp: Bitmap) {
    // The caption belongs to whichever photo is incoming; hide it now and let the per-source
    // metadata load re-show it (web/CDN photos never re-show it — they carry no EXIF).
    faceRenderer.setCaption(null, null)

    // Determine which layer is currently front (outgoing) and which is back (incoming).
    // [photoBackIsFront] tracks whether photoBack is the currently visible layer:
    //   false → photo is front, photoBack is back (initial state)
    //   true  → photoBack is front, photo is back
    val frontView = if (photoBackIsFront) photoBack else photo
    val backView  = if (photoBackIsFront) photo     else photoBack
    // [storeFront] controls which AnimatorSet field receives the Ken Burns result:
    // it must match the incoming layer — photo ↔ kenBurns, photoBack ↔ kenBurnsBack.
    // When photoBackIsFront=false, backView=photoBack so storeFront=false (kenBurnsBack).
    // When photoBackIsFront=true,  backView=photo    so storeFront=true  (kenBurns).
    val storeFront = photoBackIsFront

    // Load the new bitmap into the back (incoming) layer and ensure it starts invisible.
    backView.setImageBitmap(bmp)
    backView.alpha = 0f
    backView.visibility = View.VISIBLE

    // Start Ken Burns on the incoming layer immediately so the motion is already underway
    // as the image fades in — matching the tvOS feel where zoom begins with the reveal.
    // startKenBurns cancels any previous animator for this slot internally.
    startKenBurns(backView, storeFront)

    // Crossfade: fade the incoming layer in while the outgoing layer fades out over 1 500 ms.
    val crossfadeDuration = 1_500L
    photoFade?.removeAllListeners()
    photoFade?.cancel()
    val fade = AnimatorSet().apply {
      playTogether(ObjectAnimator.ofFloat(backView, View.ALPHA, 1f),
          ObjectAnimator.ofFloat(frontView, View.ALPHA, 0f))
      duration = crossfadeDuration
      interpolator = AccelerateDecelerateInterpolator()
      addListener(object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
          if (stopped || photoFade !== animation) return
          frontView.setImageDrawable(null)
          photoBackIsFront = !photoBackIsFront
          photoFade = null
        }
      })
    }
    photoFade = fade
    fade.start()
    if (!hostActive) fade.pause()
  }

  /**
   * Apply a slow tvOS-style zoom/pan to [target] over the dwell time. Called on the *incoming*
   * photo layer at the start of each crossfade so the motion is already in progress as the image
   * fades in (matching the tvOS Ken Burns feel). [storeFront] controls which AnimatorSet field
   * receives the result: true → [kenBurns] (the "photo" layer), false → [kenBurnsBack].
   *
   * Only active in fill mode: in fit/letterbox mode the user wants to see the whole frame, and
   * zooming would crop into it. A little overscan (scale [BIG]) gives pan styles room to travel
   * without ever exposing the black background. Uses LinearInterpolator for constant-speed motion;
   * the AccelerateDecelerateInterpolator is reserved for the crossfade alpha animation.
   */
  private fun startKenBurns(target: ImageView = photo, storeFront: Boolean = true) {
    // Cancel the currently-running animator for this slot.
    if (storeFront) { kenBurns?.cancel(); kenBurns = null }
    else            { kenBurnsBack?.cancel(); kenBurnsBack = null }

    // Reset to identity so a cancelled mid-animation transform never lingers.
    target.scaleX = 1f
    target.scaleY = 1f
    target.translationX = 0f
    target.translationY = 0f
    if (settings.fit != ScreensaverConfig.FIT_FILL) return

    val w = (if (target.width > 0) target.width else context.resources.displayMetrics.widthPixels)
    val h = (if (target.height > 0) target.height else context.resources.displayMetrics.heightPixels)
    target.pivotX = w / 2f
    target.pivotY = h / 2f

    val pan = (BIG - 1f) / 2f // max translation fraction that stays within the overscan at scale BIG
    val set = AnimatorSet()
    when ((kenBurnsStyle++ % 4 + 4) % 4) {
      0 -> // slow zoom in from centre
      set.playTogether(
          ObjectAnimator.ofFloat(target, View.SCALE_X, 1f, BIG),
          ObjectAnimator.ofFloat(target, View.SCALE_Y, 1f, BIG))
      1 -> // slow zoom out from centre
      set.playTogether(
          ObjectAnimator.ofFloat(target, View.SCALE_X, BIG, 1f),
          ObjectAnimator.ofFloat(target, View.SCALE_Y, BIG, 1f))
      2 -> { // slow pan down (hold zoom so edges stay covered)
        target.scaleX = BIG
        target.scaleY = BIG
        set.playTogether(ObjectAnimator.ofFloat(target, View.TRANSLATION_Y, pan * h, -pan * h))
      }
      else -> { // slow pan up
        target.scaleX = BIG
        target.scaleY = BIG
        set.playTogether(ObjectAnimator.ofFloat(target, View.TRANSLATION_Y, -pan * h, pan * h))
      }
    }
    // Outlast the dwell so the motion never visibly stalls at the end; LinearInterpolator
    // gives constant-speed motion (the crossfade's AccelerateDecelerateInterpolator provides
    // the ease-in/ease-out feel during the reveal, so the Ken Burns itself should be linear).
    set.duration = intervalMs() + 1200L
    set.interpolator = LinearInterpolator()
    set.start()
    if (!hostActive) set.pause()
    if (storeFront) kenBurns = set else kenBurnsBack = set
  }

  private fun cancelKenBurns() {
    kenBurns?.cancel()
    kenBurns = null
    kenBurnsBack?.cancel()
    kenBurnsBack = null
    if (this::photo.isInitialized) {
      photo.scaleX = 1f
      photo.scaleY = 1f
      photo.translationX = 0f
      photo.translationY = 0f
    }
    if (this::photoBack.isInitialized) {
      photoBack.scaleX = 1f
      photoBack.scaleY = 1f
      photoBack.translationX = 0f
      photoBack.translationY = 0f
    }
  }

  /**
   * A batch of keyless Picsum URLs with deliberately varied aspect ratios, for the WebView engine
   * when the built-in feed is the source. Fixed seeds so orientation-aware modes (diptych, panorama,
   * hero+sidebar) reliably have portraits, landscapes, squares, and panoramas to compose with. The
   * engine measures true dimensions on load; the seeds just guarantee a good spread.
   */
  private fun defaultFeedUrls(): List<String> {
    // seed to (w,h) — portraits, landscapes, squares, panoramas.
    val specs = listOf(
        "ridge" to (1600 to 1067), "harbor" to (1600 to 1067), "desert" to (1600 to 1067),
        "forest7" to (1600 to 1067), "coast22" to (1600 to 1000), "city88" to (1600 to 1067),
        "portraitA" to (1000 to 1500), "portraitB" to (1000 to 1500), "portraitC" to (1000 to 1400),
        "portraitD" to (1000 to 1500), "waterfall" to (1000 to 1600), "tower55" to (1000 to 1500),
        "square1" to (1200 to 1200), "square2" to (1200 to 1200), "square3" to (1200 to 1200),
        "pano1" to (2400 to 900), "pano2" to (2400 to 800),
    )
    // Cap the long edge to the screen size so we don't pull needlessly huge images on the device.
    val m = context.resources.displayMetrics
    val cap = maxOf(m.widthPixels, m.heightPixels).coerceIn(1080, 2200)
    return specs.map { (seed, wh) ->
      val (w, h) = wh
      val scale = cap.toDouble() / maxOf(w, h)
      "https://picsum.photos/seed/$seed/${(w * scale).toInt()}/${(h * scale).toInt()}"
    }
  }

  // --- default-feed sources (tried in turn by [fetchWebPhoto]) ----------------
  private fun picsumBitmap(generation: Long): Bitmap? {
    val m = context.resources.displayMetrics
    return downloadBitmap(
        "https://picsum.photos/${m.widthPixels}/${m.heightPixels}?random=${System.currentTimeMillis()}",
        generation = generation)
  }

  private fun unsplashBitmap(generation: Long): Bitmap? {
    val m = context.resources.displayMetrics
    // Match the Unsplash crop to the screen aspect so portrait panels (e.g. Portal
    // Mini at 800x1280) don't get a letterboxed/upscaled landscape shot.
    val orientation = if (m.heightPixels > m.widthPixels) "portrait" else "landscape"
    val json =
        httpGet(
            "https://api.unsplash.com/photos/random?orientation=$orientation" +
                "&query=$unsplashQuery&client_id=$unsplashKey", generation)
    return downloadBitmap(JSONObject(json).getJSONObject("urls").getString("regular"), generation = generation)
  }

  /**
   * Keyless Wikimedia Commons feed: the curated "Featured pictures of landscapes"
   * category. The file list is fetched once per session (then shuffled and cycled),
   * so steady state is just a thumbnail download from Wikimedia's upload CDN.
   */
  private fun wikimediaBitmap(generation: Long): Bitmap? {
    if (wikimediaUrls.isEmpty()) wikimediaUrls = fetchWikimediaUrls(generation)
    if (!photoRequestCurrent(generation)) return null
    if (wikimediaUrls.isEmpty()) return null
    val url = wikimediaUrls[wikimediaIdx % wikimediaUrls.size]
    wikimediaIdx++
    return downloadBitmap(url, generation = generation)
  }

  private fun fetchWikimediaUrls(generation: Long): List<String> {
    val width = context.resources.displayMetrics.widthPixels.coerceIn(640, 1920)
    val json =
        httpGet(
            "https://commons.wikimedia.org/w/api.php?action=query&format=json" +
                "&generator=categorymembers&gcmtype=file&gcmlimit=200" +
                "&gcmtitle=Category:Featured_pictures_of_landscapes" +
                "&prop=imageinfo&iiprop=url&iiurlwidth=$width", generation)
    val pages =
        JSONObject(json).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
    val urls = ArrayList<String>()
    for (key in pages.keys()) {
      val info = pages.getJSONObject(key).optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
      val url = info.optString("thumburl").ifBlank { info.optString("url") }
      if (url.isNotBlank()) urls.add(url)
    }
    urls.shuffle()
    return urls
  }

  /** Terminal fallback: CC0/public-domain photos bundled in the APK — never fails. */
  private fun bundledBitmap(): Bitmap? {
    if (bundledNames.isEmpty()) {
      bundledNames =
          runCatching {
                context.assets.list(FALLBACK_DIR)?.filter { it.endsWith(".jpg", ignoreCase = true) }
              }
              .getOrNull()
              .orEmpty()
              .shuffled()
    }
    if (bundledNames.isEmpty()) return null
    val name = bundledNames[bundledIdx % bundledNames.size]
    bundledIdx++
    return runCatching {
          context.assets.open("$FALLBACK_DIR/$name").use { decodeBoundedStream(it) }
        }
        .getOrNull()
  }

  // --- data -------------------------------------------------------------------
  /** A rejected/retired callback must close even after the decode executor has shut down. */
  private fun closeSmbSourceAsync(source: SmbSource) {
    Thread({ runCatching { source.close() } }, "slideshow-close-smb").apply {
      isDaemon = true
    }.start()
  }

  private fun photoRequestCurrent(generation: Long): Boolean =
      !stopped && generation == photoTransportGeneration.get() && !Thread.currentThread().isInterrupted

  /** Epoch invalidation is synchronous; physical closes are serialized away from the UI. */
  private fun cancelPhotoTransports() {
    val closing = synchronized(photoTransportLock) {
      photoTransportGeneration.incrementAndGet()
      val snapshot = photoConnections.toList() to photoStreams.toList()
      photoConnections.clear()
      photoStreams.clear()
      snapshot
    }
    if (closing.first.isEmpty() && closing.second.isEmpty()) return
    photoCancelIo.execute {
      closing.first.forEach { runCatching { it.disconnect() } }
      closing.second.forEach { runCatching { it.close() } }
    }
  }

  private fun <T> withPhotoConnection(
      spec: String, headers: Map<String, String>, timeout: Int, generation: Long,
      read: (java.io.InputStream) -> T,
  ): T {
    if (!photoRequestCurrent(generation)) throw java.io.InterruptedIOException("Photo request canceled")
    val c = PhotoSourceRedirects.open(spec, headers, readTimeoutMs = timeout,
        configure = { it.setRequestProperty("User-Agent", USER_AGENT) },
        opened = { connection ->
          synchronized(photoTransportLock) {
            if (!photoRequestCurrent(generation))
              throw java.io.InterruptedIOException("Photo request canceled")
            photoConnections.add(connection)
          }
        },
        closed = { connection ->
          synchronized(photoTransportLock) { photoConnections.remove(connection) }
        })
    var input: java.io.InputStream? = null
    try {
      val stream = c.inputStream
      input = stream
      synchronized(photoTransportLock) {
        if (!photoRequestCurrent(generation)) throw java.io.InterruptedIOException("Photo request canceled")
        photoStreams.add(stream)
      }
      return read(stream)
    } finally {
      synchronized(photoTransportLock) {
        photoConnections.remove(c)
        input?.let { photoStreams.remove(it) }
      }
      runCatching { c.disconnect() }
      runCatching { input?.close() }
    }
  }

  private fun httpGet(spec: String, generation: Long): String =
      withPhotoConnection(spec, emptyMap(), 8000, generation) { input ->
        val bytes = SlideshowDecoder.readEncoded(input, 2 * 1024 * 1024)
            ?: throw java.io.IOException("Photo manifest unavailable")
        if (!photoRequestCurrent(generation)) throw java.io.InterruptedIOException("Photo request canceled")
        bytes.toString(Charsets.UTF_8)
      }

  private fun downloadBitmap(
      spec: String, headers: Map<String, String> = emptyMap(), maxEdge: Int = 2400,
      generation: Long = photoTransportGeneration.get(),
  ): Bitmap? = withPhotoConnection(spec, headers, 12000, generation) { input ->
    val bitmap = decodeBoundedStream(input, maxEdge)
    if (photoRequestCurrent(generation)) bitmap else { bitmap?.recycle(); null }
  }

  // --- on-device media cache (Immich / WebDAV) --------------------------------

  /**
   * Spin up the cache + transcoder for a stable-URL HTTP source, if the user has enabled it. The
   * budget is the user's chosen GB cap, still bounded by free space so a small Portal is never
   * pushed over. No-op (leaves [mediaCache] null → direct fetch) when the setting is off, or when
   * the disk is so full the budget is negligible — enabling then would just churn (every write
   * immediately evicted) without ever helping.
   */
  private fun enableMediaCache() {
    if (stopped || mediaCache != null || !settings.cacheEnabled) return
    val ceiling = settings.cacheBudgetGb.toLong() * 1024L * 1024L * 1024L
    val free = context.filesDir.usableSpace
    val budget = MediaCache.defaultBudget(free, ceiling)
    if (budget < MIN_CACHE_BUDGET_BYTES) {
      Log.w(TAG, "media cache enabled but disk too full (free=${free / MB}MB -> budget=${budget / MB}MB); not caching")
      return
    }
    val cache = MediaCache(context, budget).also { it.enforceBudget() } // worker-only scan/eviction
    if (stopped) return
    mediaCache = cache
    transcoder = VideoTranscoder(context)
    Log.i(TAG, "media cache on: budget=${budget / MB}MB (cap=${settings.cacheBudgetGb}GB, free=${free / MB}MB)")
  }

  /** Inspect encoded content once, on the worker, without changing any other slideshow mode. */
  private fun decodeNativeFilmstrip(
      url: String, maxEdge: Int, headers: Map<String, String>,
      fetch: ((String, Int) -> Bitmap?)?,
  ): Bitmap? {
    if (url.startsWith("file://")) {
      return SlideshowDecoder.nativeFilmstripFile(android.net.Uri.parse(url).path ?: "", maxEdge)
    }
    // SMB's bitmap-only transport cannot prove that its original was static. Keep its legacy path.
    if (fetch != null) throw NativeFilmstripUnsupportedFormat()
    val generation = photoTransportGeneration.get()
    if (!photoRequestCurrent(generation)) return null
    val cache = mediaCache
    cache?.getIfPresent(url, isVideo = false)?.let { file ->
      // Cache filenames may end in .jpg even when the stored bytes are GIF or WebP.
      SlideshowDecoder.nativeFilmstripFile(file.path, maxEdge)?.let { bitmap ->
        if (photoRequestCurrent(generation)) return bitmap
        bitmap.recycle()
        return null
      }
    }
    val encoded = runCatching { downloadBytes(url, headers, generation) }.getOrNull() ?: return null
    if (!photoRequestCurrent(generation)) return null
    cache?.putImage(url, encoded)
    if (!photoRequestCurrent(generation)) return null
    val bitmap = SlideshowDecoder.nativeFilmstripBytes(encoded, maxEdge)
    if (photoRequestCurrent(generation)) return bitmap
    bitmap?.recycle()
    return null
  }

  /**
   * Fetch a remote image, going through [mediaCache] when one is active (Immich/WebDAV): a hit
   * decodes straight from disk with no network; a miss downloads the bytes, caches them, and
   * decodes. SMB (a custom [remoteFetch]) and the uncached sources fall back to the direct path.
   */
  private fun fetchRemoteImage(
      url: String, maxEdge: Int = 2400, headers: Map<String, String> = remoteHeaders,
      fetch: ((String, Int) -> Bitmap?)? = remoteFetch,
  ): Bitmap? {
    val generation = photoTransportGeneration.get()
    if (!photoRequestCurrent(generation)) return null
    val cache = mediaCache
    if (cache != null && fetch == null) {
      cache.getIfPresent(url, isVideo = false)?.let { f ->
        runCatching { decodeBoundedFile(f.path, maxEdge) }.getOrNull()?.let { return it }
      }
      val bytes = runCatching { downloadBytes(url, headers, generation) }.getOrNull() ?: return null
      if (!photoRequestCurrent(generation)) return null
      cache.putImage(url, bytes)
      if (!photoRequestCurrent(generation)) return null
      return decodeBoundedBytes(bytes, maxEdge)
    }
    // An SMB miss is not an HTTP URL; let the source fail rather than retrying another transport.
    return if (fetch != null) fetch(url, maxEdge) else downloadBitmap(url, headers, maxEdge, generation)
  }

  /** Raw bytes of an authed HTTP GET (for caching before decode). Null on failure. */
  private fun downloadBytes(
      spec: String, headers: Map<String, String>, generation: Long,
  ): ByteArray? = withPhotoConnection(spec, headers, 12000, generation) { input ->
    SlideshowDecoder.readEncoded(input)?.takeIf { photoRequestCurrent(generation) }
  }

  /** Stream an authed HTTP GET straight to [dst] (for the video prefetch source). */
  private fun downloadToFile(spec: String, headers: Map<String, String>, dst: java.io.File): Boolean =
      runCatching {
            if (!hostActive || stopped) return@runCatching false
            val c = PhotoSourceRedirects.open(spec, headers, readTimeoutMs = 20000,
                configure = { it.setRequestProperty("User-Agent", USER_AGENT) },
                opened = { connection ->
                  videoPrefetchConnection = connection
                  if (!hostActive || stopped)
                    throw java.io.InterruptedIOException("Video prefetch canceled")
                },
                closed = { connection ->
                  if (videoPrefetchConnection === connection) videoPrefetchConnection = null
                })
            try {
              c.inputStream.use { input ->
                dst.outputStream().use { output ->
                  val buffer = ByteArray(32 * 1024)
                  while (hostActive && !stopped && !Thread.currentThread().isInterrupted) {
                    val count = input.read(buffer)
                    if (count < 0) return@runCatching dst.length() > 0L
                    output.write(buffer, 0, count)
                  }
                }
              }
              false
            } finally {
              if (videoPrefetchConnection === c) videoPrefetchConnection = null
              c.disconnect()
            }
          }
          .getOrDefault(false)

  /**
   * Background-warm the video cache: for each not-yet-cached video, pull the source once,
   * transcode it to a screen-sized derivative, and commit it. One clip at a time (single-thread
   * [transcodeIo]) so a wall of Portals doesn't thrash — the first loop still streams originals;
   * every loop after is local. Guarded by [prefetchRunning] so overlapping advances don't stack
   * workers.
   *
   * Warms in *playlist order from the current position* ([prefetchOrder]) so the clips about to
   * be shown are cached first, and **stops when the cache runs out of room** ([MediaCache.hasRoom])
   * — on an album bigger than the budget, pressing on would evict warm entries to add cold ones,
   * turning the cache into a treadmill that re-hits the server forever.
   */
  private fun schedulePrefetch() {
    val cache = mediaCache ?: return
    val tc = transcoder ?: return
    if (stopped || !hostActive || prefetchRunning || prefetchFull || !tc.isIdle) return
    // Snapshot playlist order + headers on the UI thread (both are mutated here only).
    val queue = prefetchOrder(remoteUrls, remoteVideos, remoteIndex)
    if (queue.isEmpty()) return
    val headers = remoteHeaders
    prefetchRunning = true
    // execute can only reject after stop() shut the executor down; reset the guard and move on.
    runCatching {
          transcodeIo.execute {
            var built = 0
            var failed = 0
            try {
              for (url in queue) {
                if (stopped || !hostActive || !remoteMode || !tc.isIdle ||
                    Thread.currentThread().isInterrupted) break
                if (!cache.hasRoom()) {
                  prefetchFull = true
                  Log.i(TAG, "prefetch: cache full at ${cache.sizeBytes() / MB}MB; stopping (album larger than budget)")
                  break
                }
                if (cache.isCached(url, isVideo = true)) continue
                val target = cache.videoFile(url)
                val srcTmp = cache.uniqueTempFor(java.io.File(target.path + ".src"))
                val outTmp = cache.uniqueTempFor(target)
                try {
                  if (!downloadToFile(url, headers, srcTmp)) {
                    if (!hostActive || stopped) break
                    failed++
                    Log.w(TAG, "prefetch: download failed for $url")
                    continue
                  }
                  if (runCatching { tc.transcode(srcTmp, outTmp) { hostActive && !stopped } }.getOrDefault(false) &&
                      hostActive && !stopped &&
                      cache.commit(outTmp, target)) {
                    built++
                    Log.i(
                        TAG,
                        "prefetch: cached ${target.name} (${srcTmp.length() / MB}MB -> ${target.length() / MB}MB)")
                  } else {
                    failed++ // transcode already logged its own reason under ImmortalTranscode
                  }
                } finally {
                  // A pending native cancel retains file ownership until its worker acknowledges.
                  if (!VideoExportOwnership.owns(srcTmp)) runCatching { srcTmp.delete() }
                  if (!VideoExportOwnership.owns(outTmp)) runCatching { outTmp.delete() }
                }
              }
            } finally {
              prefetchRunning = false
              if (built + failed > 0) {
                Log.i(TAG, "prefetch pass done: $built cached, $failed failed, cache=${cache.sizeBytes() / MB}MB")
              }
            }
          }
        }
        .onFailure { prefetchRunning = false }
  }

  private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
  private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
  // Ken Burns overscan: the photo is scaled to this at the zoomed end / throughout a pan, giving
  // pan styles room to travel without exposing the black background behind the image.
  private val BIG = 1.12f
  // Cap on consecutive shared-album re-resolves before yielding to the built-in feed (a real
  // sustained outage), reset on any successful photo. See [reresolveOrFallback].
  private val MAX_RERESOLVE = 3
  private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

  internal companion object {
    /**
     * The view size (w × h) that makes a [videoW]×[videoH] clip cover a [screenW]×[screenH]
     * screen with its aspect preserved — the geometry behind [applyVideoFit]'s fill mode. Null
     * when any dimension is unknown (callers fall back to match-parent letterboxing). Clamped to
     * at least the screen so rounding never leaves a hairline of background showing. Pure.
     */
    internal fun videoCoverSize(videoW: Int, videoH: Int, screenW: Int, screenH: Int): Pair<Int, Int>? {
      if (videoW <= 0 || videoH <= 0 || screenW <= 0 || screenH <= 0) return null
      val scale = maxOf(screenW.toDouble() / videoW, screenH.toDouble() / videoH)
      return Pair(
          Math.round(videoW * scale).toInt().coerceAtLeast(screenW),
          Math.round(videoH * scale).toInt().coerceAtLeast(screenH))
    }

    const val TAG = "ImmortalPhotoFrame"
    const val MB = 1024L * 1024L
    // Below this computed budget the cache isn't worth enabling: every write would evict
    // something at least as warm, so it would only add churn. See [enableMediaCache].
    const val MIN_CACHE_BUDGET_BYTES = 256L * 1024L * 1024L

    /**
     * The video URLs from [urls] in playlist order starting *after* [currentIndex] (wrapping),
     * i.e. the order the slideshow will actually want them — so the prefetch worker warms the
     * near future first, not a HashSet's arbitrary order. Skips the currently-playing index
     * (it's already streaming; downloading it again in parallel would double the bandwidth).
     * Tolerates currentIndex = -1 (nothing shown yet -> playlist order from the top). Pure.
     */
    internal fun prefetchOrder(urls: List<String>, videos: Set<String>, currentIndex: Int): List<String> {
      if (urls.isEmpty() || videos.isEmpty()) return emptyList()
      val start = if (currentIndex in urls.indices) currentIndex + 1 else 0
      return (0 until urls.size - if (currentIndex in urls.indices) 1 else 0)
          .map { urls[(start + it) % urls.size] }
          .filter { it in videos }
    }
    // Cap on the longest edge of any decoded photo. Full-res camera originals (tens of MP) decode
    // to bitmaps larger than the hardware Canvas can draw (~100MB), which crashes the launcher and
    // makes Android drop its default-home role. The largest Portal panel is 1920px; 2560 leaves
    // Ken-Burns overscan headroom (2560²·4 ≈ 26MB) while staying far under the cap. See
    // [decodeBoundedFile]/[decodeBoundedStream].
    const val MAX_EDGE = 2560
    // Local-folder curation: skip images whose short side is below this (thumbnails, icons,
    // tiny web junk) — they read as clutter on a 2160px wall display. Real photos clear it easily.
    const val MIN_PHOTO_EDGE = 512
    // Cap the engine's local-photo pool: enough for rich variety with no visible repetition, while
    // bounding the header-decode dimension pass at startup and the deck's memory. The pool is a
    // random sample when shuffle is on.
    const val ENGINE_MAX_PHOTOS = 300
    // Delay before the engine's scaled-copy cache starts warming (lets Chromium cold-start first).
    const val ENGINE_WARM_DELAY_MS = 20_000L
    // Oriented-dimension SEED: measure only this many photos up front (header decode + EXIF is the
    // single-threaded pre-pass that gates first paint AND the background face warm-up). The seed
    // gives the scheduler real orientation variety; the rest default via the engine's own measure /
    // NaN-safe bucket. Keeps cold start to a few seconds instead of ~15s for the whole pool.
    const val DIMS_SEED = 80
    // Bundled fallback photos live under app/src/main/assets/<FALLBACK_DIR>/.
    const val FALLBACK_DIR = "photoframe_fallback"
    // How long to skip a web source after it fails, so a dead host (e.g. a Picsum
    // outage) isn't re-hammered every tick. Expires on its own so recovery self-heals.
    const val SOURCE_COOLDOWN_MS = 5 * 60_000L
    // Descriptive UA — Wikimedia's API policy asks for an identifiable agent + contact.
    const val USER_AGENT = "Immortal/1.0 (+https://github.com/starbrightlab/immortal)"
  }
}
