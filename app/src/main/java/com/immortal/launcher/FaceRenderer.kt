/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs

/**
 * Builds and drives the screensaver **overlay** from a [Face] — clock, date, weather,
 * battery, and the now-playing card — laid out on the 9-point grid. The photo/video layer
 * and all source/power logic stay in [PhotoFrameController]; this owns only what's drawn on
 * top, plus the per-second tick, the weather fetch, and the now-playing subscription.
 *
 * This is the new render path that replaces the original hardcoded overlay. [Face.immortalClassic]
 * reproduces that original face through it; richer modes (flip / analog / word / gradient /
 * neon) extend the widget builders here.
 */
class FaceRenderer(
    private val context: Context,
    private val weatherRefreshMs: Long = 30 * 60_000L,
    private val weatherFetch: (Context) -> String = { Weather.fetch(it) },
) {
  private val io = Executors.newSingleThreadExecutor()
  private val ui = Handler(Looper.getMainLooper())
  private val assets = AssetResolver(context)
  private val work = FaceRendererWorkState()
  private var weatherRequest: Future<*>? = null
  private var artRequest: Future<*>? = null
  private var artRequestId = 0L

  private var face: Face = Face("immortal-classic")

  // Anti-burn-in drift radius (px). The always-on overlay is nudged within ±this by
  // [tick] so no pixel stays lit in one spot for long; the overlay is oversized by the
  // same amount (negative margins below) so the drift never bares a strip of the layer
  // beneath at a screen edge — matters most for the full-bleed flip clock.
  // Small radius: a few px is enough to spread the bright content over time, and keeps the slow
  // drift well below what the eye catches. (dp(6) earlier was large enough to read as motion.)
  private val burnInMaxPx: Int by lazy { dp(3) }
  // Whether the pixel-shift runs at all (user setting); read once per [start].
  private var antiBurnInOn: Boolean = true

  /** The overlay container, added above the photo layer by the host. */
  val view: FrameLayout by lazy {
    FrameLayout(context).apply {
      // MATCH_PARENT plus a negative margin on every edge makes the overlay slightly
      // larger than the screen, so the burn-in drift (±[burnInMaxPx]) always keeps it
      // fully covering the display.
      layoutParams =
          FrameLayout.LayoutParams(MATCH, MATCH).apply {
            setMargins(-burnInMaxPx, -burnInMaxPx, -burnInMaxPx, -burnInMaxPx)
          }
      // Let text shadows overflow their tight boxes instead of being clipped into hard rectangles.
      clipChildren = false
      clipToPadding = false
    }
  }

  // Position buckets (a vertical stack per used grid cell), created on demand.
  private val buckets = HashMap<GridPosition, LinearLayout>()
  private var paperPhotoSurface = false
  private var photoScrim: View? = null
  private val paperBackedBuckets = HashSet<LinearLayout>()

  /** Keep configured native clock colors readable on the Paper photo margin in every position. */
  fun setPaperPhotoSurface(enabled: Boolean) {
    if (paperPhotoSurface == enabled) return
    paperPhotoSurface = enabled
    buckets.values.forEach(::applyPhotoBucketBacking)
    if (enabled) {
      detachFramedScrimListener()
      photoScrim?.visibility = View.INVISIBLE
    } else {
      photoScrim?.visibility = View.VISIBLE
      if (ScreensaverConfig.load(context).mode == "framed") photoScrim?.let(::watchFramedScrim)
    }
  }

  private fun applyPhotoBucketBacking(bucket: LinearLayout) {
    val backed = paperPhotoSurface && (0 until bucket.childCount).any {
      bucket.getChildAt(it).visibility == View.VISIBLE
    }
    // Visibility can change on a retained timer/music/caption bucket. Change padding only
    // when its state changes, so the layout observer settles instead of causing a loop.
    if (backed == paperBackedBuckets.contains(bucket)) return
    if (backed) paperBackedBuckets.add(bucket) else paperBackedBuckets.remove(bucket)
    val inset = if (backed) dp(12) else 0
    bucket.setPadding(inset, inset, inset, inset)
    bucket.background = if (backed) GradientDrawable().apply {
      setColor(0xE6293029.toInt()); cornerRadius = dp(12).toFloat()
    } else null
  }

  fun refreshPaperPhotoSurface() {
    if (paperPhotoSurface) buckets.values.forEach(::applyPhotoBucketBacking)
  }
  private var framedScrimListener: ViewTreeObserver.OnGlobalLayoutListener? = null

  private fun detachFramedScrimListener() {
    framedScrimListener?.let { listener ->
      if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener)
    }
    framedScrimListener = null
  }

  private fun watchFramedScrim(scrim: View) {
    val display = context.resources.displayMetrics
    val listener = ViewTreeObserver.OnGlobalLayoutListener {
      // A visible but unmeasured bucket is unknown, not an empty reservation. INVISIBLE keeps
      // the scrim's own geometry stable; only a changed decision requests another layout.
      val unknown = view.width <= 0 || view.height <= 0 || buckets.values.any { bucket ->
        bucket.visibility == View.VISIBLE &&
            (0 until bucket.childCount).any { bucket.getChildAt(it).visibility == View.VISIBLE } &&
            (bucket.width <= 0 || bucket.height <= 0)
      }
      val occupied = if (unknown) null else photoOcclusions().map { area ->
        SlideshowPresentationPolicy.Bounds(area.left, area.top, area.right, area.bottom)
      }
      val shade = SlideshowPresentationPolicy.framedOverlaysNeedScrim(
          display.widthPixels, display.heightPixels, display.density, occupied)
      val visibility = if (shade) View.VISIBLE else View.INVISIBLE
      if (scrim.visibility != visibility) scrim.visibility = visibility
    }
    framedScrimListener = listener
    view.viewTreeObserver.addOnGlobalLayoutListener(listener)
  }

  /** Measured widget areas in window pixels, padded for the small burn-in drift. */
  fun photoOcclusions(): List<android.graphics.Rect> =
      if (view.visibility != View.VISIBLE) emptyList() else buckets.values.mapNotNull { bucket ->
    if (bucket.visibility != View.VISIBLE || bucket.width <= 0 || bucket.height <= 0 ||
        bucket.childCount == 0 || (paperPhotoSurface && (0 until bucket.childCount).none {
          bucket.getChildAt(it).visibility == View.VISIBLE
        })) null
    else {
      val position = IntArray(2)
      bucket.getLocationInWindow(position)
      val padding = dp(12)
      android.graphics.Rect(position[0] - padding, position[1] - padding,
          position[0] + bucket.width + padding, position[1] + bucket.height + padding)
    }
  }

  // Time-bearing views the tick refreshes; null when the face omits them.
  private var clockFace: ClockFaceView? = null
  private var dateView: TextView? = null
  private var batteryView: TextView? = null
  private var batteryDivider: View? = null
  private var batteryReceiver: BroadcastReceiver? = null
  private var batteryStatus: Pair<Int, Boolean> = -1 to false
  private var weatherView: TextView? = null
  private var weatherDivider: View? = null
  private var weatherText: String = ""
  private var blinkOn = true

  // A running countdown timer (started from the home-screen Timers widget), shown top-center so it
  // stays visible on every face; hidden whenever no timer is active.
  private var timerView: TextView? = null
  // Fullscreen "time's up" alarm overlay (swipe to stop), shown while the timer is ringing.
  private var timerAlarmOverlay: View? = null
  private var timerThumb: View? = null
  private var timerSlideText: View? = null
  private var alarmMaxX = 0f
  private var alarmTouchDownX = 0f
  private var alarmThumbStart = 0f

  // Now-playing card.
  private var nowPlayingCard: LinearLayout? = null
  private var npArt: ImageView? = null
  private var npTitle: TextView? = null
  private var npArtist: TextView? = null
  // null means unbound/retry; an empty URL means the current track has no artwork.
  private var lastArtUrl: String? = null
  private var lastArtBitmap: Bitmap? = null
  private var npListener: NowPlayingHub.Listener? = null

  // Photo caption (place / date + "on this day" badge). Grid element, fed via [setCaption].
  private var captionPanel: LinearLayout? = null
  private var captionMemory: TextView? = null
  private var captionPlace: TextView? = null
  private var captionDate: TextView? = null

  fun start(face: Face) {
    if (!work.start()) return
    this.face = face
    antiBurnInOn = ScreensaverConfig.load(context).antiBurnIn
    buildOverlay()
    if (work.isActive) resumeHostWork()
  }

  /**
   * Suspend owned work while the host is hidden, retaining every overlay view. Main thread only;
   * independent of the user's playback pause. May be set before [start]; [stop] remains terminal.
   */
  fun setHostActive(active: Boolean) {
    if (!work.setHostActive(active)) return
    if (work.isActive) resumeHostWork() else suspendHostWork()
  }

  /** Refresh visible time/date for a returning cover; media, listeners and timers stay paused. */
  fun refreshRetainedTime(now: Date) {
    if (work.isStopped) return
    clockFace?.refreshForReveal(now, blinkOn)
    if (face.clock.showDate) dateView?.setMetadataText(face.clock.dateFormat.format(now))
  }

  private fun resumeHostWork() {
    val token = work.token() ?: return
    clockFace?.setHostActive(true)
    tick.run()
    refreshWeather.run()
    if (nowPlayingCard != null && npListener == null) {
      npListener = NowPlayingHub.Listener {
        if (work.isCurrent(token)) ui.post {
          // A detached listener may still be in a publisher's snapshot. Read the latest track
          // here so a queued replay cannot overwrite newer state.
          if (work.isCurrent(token)) updateNowPlaying(NowPlayingHub.current)
        }
      }
      NowPlayingHub.addListener(npListener!!)
      updateNowPlaying(NowPlayingHub.current)
    }
  }

  private fun suspendHostWork() {
    val oldBatteryReceiver = batteryReceiver
    batteryReceiver = null
    batteryStatus = -1 to false
    if (oldBatteryReceiver != null) runCatching { context.unregisterReceiver(oldBatteryReceiver) }
    ui.removeCallbacksAndMessages(null)
    npListener?.let { NowPlayingHub.removeListener(it) }
    npListener = null
    weatherRequest?.cancel(true)
    weatherRequest = null
    // Retain the overlay views, but release hidden pixels and invalidate pending completions.
    clearNowPlayingArtwork()
    clockFace?.setHostActive(false)
    timerThumb?.animate()?.cancel()
  }

  /** Permanently release this renderer; neither [start] nor [setHostActive] can restart it. */
  fun stop() {
    if (!work.stop()) return
    detachFramedScrimListener()
    photoScrim = null
    suspendHostWork()
    clockFace?.dispose()
    io.shutdownNow()
  }

  // --- overlay assembly -------------------------------------------------------
  private fun buildOverlay() {
    detachFramedScrimListener()
    photoScrim = null
    view.removeAllViews()
    buckets.clear()
    paperBackedBuckets.clear()
    // Caption views are rebuilt below (or not, on a full-bleed face) — drop stale refs so a
    // late setCaption() can't poke a detached view from the previous face.
    captionPanel = null
    captionPlace = null
    captionDate = null

    buildClockCluster(face.clock)
    val fullBleed = clockFace?.fullBleed == true
    val screensaver = ScreensaverConfig.load(context)

    // Now-playing shows on EVERY face — including the full-bleed flip clock — whenever the user has
    // the setting on (it's high-value enough to be its own switch, not tied to face selection). It's
    // built BEFORE the caption so that when they share a cell (both default bottom-right) the
    // now-playing card sits on top and the smaller photo caption tucks beneath it. Self-hides until
    // music is playing.
    if (screensaver.showNowPlaying) buildNowPlaying(face.nowPlaying)

    // A full-bleed clock (e.g. the Fliqlo flip clock) owns the whole frame on its own near-black
    // background, so skip the scrim and the date/battery/weather widgets — none should overlay it.
    if (!fullBleed) {
      // Gallery places metadata in reserved matte space. Shading the whole lower screen would
      // needlessly alter its photo colors; other styles still need readability over photos.
      if (SlideshowPresentationPolicy.needsPhotoScrim(screensaver.mode)) {
        val scrimHeight = (context.resources.displayMetrics.heightPixels * 0.45f).toInt()
        val premiumScrim = View(context)
        premiumScrim.background =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(80, 0, 0, 0),
                    Color.argb(160, 0, 0, 0),
                    Color.argb(200, 0, 0, 0),
                ),
            )
        view.addView(premiumScrim, 0, FrameLayout.LayoutParams(MATCH, scrimHeight, Gravity.BOTTOM))
        photoScrim = premiumScrim
        if (paperPhotoSurface) premiumScrim.visibility = View.INVISIBLE
        else if (screensaver.mode == "framed") watchFramedScrim(premiumScrim)
      }
      buildStandaloneWidgets(face)
      // Photo caption is photo metadata, so (like the widgets) it's skipped on a full-bleed clock.
      if (face.caption.enabled) buildCaption(face.caption)
    }
    // A running countdown timer is face-independent: it surfaces top-center on every face and
    // self-hides when no timer is active (see [tick]).
    buildTimer()
  }

  /** Top-center countdown readout, hidden until a timer is running (driven by [tick]). */
  private fun buildTimer() {
    val t = textView(baseSp = 30f, spec = face.clock, light = false)
    t.visibility = View.GONE
    bucket(GridPosition.TOP_CENTER).addView(t)
    timerView = t

    // Fullscreen "time's up" alarm with an iOS-style slide-to-stop, shown while ringing.
    // It's intentionally NOT touch-consuming: on a DreamService the host owns the touch stream, so
    // the slide is driven by [handleAlarmTouch] (called from PhotoFrameController.onTouch) instead
    // of child touch listeners, which the dream's root listener would otherwise pre-empt.
    val overlay =
        FrameLayout(context).apply {
          setBackgroundColor(0xF2000000.toInt())
          visibility = View.GONE
        }
    val col =
        LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          gravity = Gravity.CENTER_HORIZONTAL
        }
    col.addView(
        TextView(context).apply {
          text = "⏰  Timer"
          setTextColor(0xFFDDDDDD.toInt())
          textSize = 20f
          gravity = Gravity.CENTER
        })
    col.addView(
        TextView(context).apply {
          text = "Time's up"
          setTextColor(Color.WHITE)
          textSize = 48f
          typeface = Typeface.DEFAULT_BOLD
          gravity = Gravity.CENTER
          setPadding(0, dp(10), 0, 0)
        })
    overlay.addView(
        col,
        FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(120) })

    // Slide-to-stop pill: a draggable thumb you slide to the right end to silence (iOS-style).
    val trackW = dp(520)
    val trackH = dp(72)
    val thumbSize = dp(64)
    val inset = dp(4)
    val maxX = (trackW - thumbSize - inset * 2).toFloat()
    val track =
        FrameLayout(context).apply {
          background =
              GradientDrawable().apply {
                cornerRadius = trackH / 2f
                setColor(0x33FFFFFF)
              }
        }
    val slideText =
        TextView(context).apply {
          text = "slide to stop"
          setTextColor(0xFFCFCFCF.toInt())
          textSize = 18f
          gravity = Gravity.CENTER
        }
    track.addView(slideText, FrameLayout.LayoutParams(MATCH, MATCH))
    val thumb =
        TextView(context).apply {
          text = "■"
          setTextColor(0xFF111111.toInt())
          textSize = 18f
          gravity = Gravity.CENTER
          background =
              GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
              }
        }
    track.addView(
        thumb,
        FrameLayout.LayoutParams(thumbSize, thumbSize, Gravity.START or Gravity.CENTER_VERTICAL).apply {
          marginStart = inset
        })
    overlay.addView(
        track,
        FrameLayout.LayoutParams(trackW, trackH, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
          bottomMargin = dp(80)
        })
    view.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
    timerAlarmOverlay = overlay
    timerThumb = thumb
    timerSlideText = slideText
    alarmMaxX = maxX
  }

  /**
   * Drives the screensaver slide-to-stop from the host's touch stream (the dream/preview forwards
   * every event to [PhotoFrameController.onTouch], which calls this first). Returns true while the
   * alarm is showing so the host doesn't also treat the gesture as a tap-to-exit / swipe-photo.
   * Forgiving by design: a horizontal drag anywhere moves the thumb, and sliding ~80% silences it.
   */
  fun handleAlarmTouch(ev: MotionEvent): Boolean {
    if (!work.isActive) return false
    val overlay = timerAlarmOverlay ?: return false
    if (overlay.visibility != View.VISIBLE) return false
    val thumb = timerThumb ?: return true
    when (ev.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        alarmTouchDownX = ev.rawX
        alarmThumbStart = thumb.translationX
      }
      MotionEvent.ACTION_MOVE -> {
        val x = (alarmThumbStart + (ev.rawX - alarmTouchDownX)).coerceIn(0f, alarmMaxX)
        thumb.translationX = x
        timerSlideText?.alpha = 1f - (if (alarmMaxX > 0f) x / alarmMaxX else 0f)
      }
      MotionEvent.ACTION_UP,
      MotionEvent.ACTION_CANCEL -> {
        if (alarmMaxX > 0f && thumb.translationX >= alarmMaxX * 0.8f) {
          TimerAlarm.stop(context)
          overlay.visibility = View.GONE
          thumb.translationX = 0f
          timerSlideText?.alpha = 1f
        } else {
          thumb.animate().translationX(0f).setDuration(160).start()
          timerSlideText?.alpha = 1f
        }
      }
    }
    return true
  }

  /**
   * The clock and any small meta fields (date / battery / weather) that share its grid
   * position: the time on top, then a single horizontal "date • battery • weather" row,
   * reproducing the original overlay. Each optional field owns the divider that precedes it,
   * so a dot never orphans when its field is hidden (e.g. a battery-less Portal).
   */
  private fun buildClockCluster(clock: ClockSpec) {
    val made = makeClockFace(context, clock, assets)
    clockFace = made
    made.setHostActive(work.hostActive)
    made.update(Date(), blinkOn)

    // A full-bleed clock (WebView) is its own full-screen layer that positions itself; it sits
    // below the native widgets and owns no grid bucket. The meta row is skipped for it (the
    // HTML clock owns the clock area; date/battery/weather handling moves into the face later).
    if (made.fullBleed) {
      view.addView(made.view, FrameLayout.LayoutParams(MATCH, MATCH))
      return
    }

    val col = bucket(clock.position)
    // Canvas clocks measure their chosen size within this grid bucket's available space.
    col.addView(
        made.view,
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    // Inner row: date + divider + weather grouped together, centered under the clock
    val row = LinearLayout(context)
    row.gravity = Gravity.CENTER_VERTICAL
    row.clipChildren = false

    if (clock.showDate) {
      dateView = textView(baseSp = 22f, spec = clock, light = false).also { row.addView(it) }
    }
    if (face.battery.enabled && face.battery.position == clock.position) {
      batteryDivider = divider().also { row.addView(it) }
      batteryView = textView(baseSp = 22f, spec = clock, light = false).also { row.addView(it) }
    }
    if (face.weather.enabled && face.weather.position == clock.position) {
      weatherDivider = divider().also { row.addView(it) }
      weatherView = textView(baseSp = 22f, spec = clock, light = false).also { row.addView(it) }
    }
    // MATCH_PARENT width so the row spans the clock width; CENTER_HORIZONTAL centers
    // the date+weather group within that width
    if (row.childCount > 0) {
      val wrapper = LinearLayout(context)
      wrapper.gravity = Gravity.CENTER_HORIZONTAL
      wrapper.addView(row, LinearLayout.LayoutParams(WRAP, LinearLayout.LayoutParams.WRAP_CONTENT))
      col.addView(wrapper, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT))
    }
  }

  /** Weather / battery that aren't co-located with the clock get their own line in their bucket. */
  private fun buildStandaloneWidgets(face: Face) {
    if (face.battery.enabled && face.battery.position != face.clock.position) {
      batteryView =
          textView(baseSp = 22f, spec = face.clock, light = false).also {
            bucket(face.battery.position).addView(it)
          }
    }
    if (face.weather.enabled && face.weather.position != face.clock.position) {
      weatherView =
          textView(baseSp = 22f, spec = face.clock, light = false).also {
            bucket(face.weather.position).addView(it)
          }
    }
  }

  /** Now-playing card (album art + track / artist), hidden until something is playing. */
  private fun buildNowPlaying(spec: NowPlayingSpec) {
    val scale = spec.sizeScale / 100f
    val card = LinearLayout(context)
    card.orientation = LinearLayout.HORIZONTAL
    card.gravity = Gravity.CENTER_VERTICAL
    card.visibility = View.GONE

    // Text first, art at the right edge: title/artist hug the cover (right-aligned).
    val npCol = LinearLayout(context)
    npCol.orientation = LinearLayout.VERTICAL
    npCol.gravity = Gravity.END
    card.addView(npCol, LinearLayout.LayoutParams(WRAP, WRAP))

    val title = text(26f * scale, Color.WHITE, false)
    title.typeface = Typeface.DEFAULT_BOLD
    title.maxLines = 1
    title.ellipsize = TextUtils.TruncateAt.END
    title.gravity = Gravity.END
    title.maxWidth = dp(560)
    npCol.addView(title, LinearLayout.LayoutParams(WRAP, WRAP))

    val artist = text(18f * scale, 0xCCFFFFFF.toInt(), false)
    artist.maxLines = 1
    artist.ellipsize = TextUtils.TruncateAt.END
    artist.gravity = Gravity.END
    artist.maxWidth = dp(560)
    npCol.addView(artist, LinearLayout.LayoutParams(WRAP, WRAP))

    val art = ImageView(context)
    art.scaleType = ImageView.ScaleType.CENTER_CROP
    art.clipToOutline = true
    art.outlineProvider =
        object : ViewOutlineProvider() {
          override fun getOutline(v: View, o: Outline) {
            o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
          }
        }
    val artLp = LinearLayout.LayoutParams((dp(72) * scale).toInt(), (dp(72) * scale).toInt())
    artLp.setMarginStart(dp(16))
    if (spec.showArt) card.addView(art, artLp)

    // Explicit WRAP width: a vertical LinearLayout (the bucket) otherwise hands children
    // MATCH_PARENT by default, which would squeeze the card to a narrower cell-mate's width
    // (e.g. the photo caption now sharing this cell) and truncate the track title.
    bucket(spec.position).addView(card, LinearLayout.LayoutParams(WRAP, WRAP))
    nowPlayingCard = card
    npTitle = title
    npArtist = artist
    npArt = art
  }

  /** Reflect the latest now-playing state (main thread). Mirrors the original behaviour. */
  private fun updateNowPlaying(s: NowPlayingState?) {
    val token = work.token() ?: return
    val card = nowPlayingCard ?: return
    if (s == null || !s.active) {
      clearNowPlayingArtwork()
      card.visibility = View.GONE
      return
    }
    card.visibility = View.VISIBLE
    npTitle?.text = s.title
    npArtist?.text = s.artist
    npArtist?.visibility = if (s.artist.isBlank()) View.GONE else View.VISIBLE
    val art = npArt ?: return
    val bitmap = s.artBitmap
    if (bitmap != null) {
      if (bitmap !== lastArtBitmap) {
        cancelArtRequest()
        lastArtBitmap = bitmap
        lastArtUrl = null
        art.visibility = View.VISIBLE
        art.setImageBitmap(bitmap)
      }
    } else if (s.artUrl != lastArtUrl || lastArtBitmap != null) {
      cancelArtRequest()
      lastArtBitmap = null
      lastArtUrl = s.artUrl
      art.setImageBitmap(null)
      val want = s.artUrl
      art.visibility = if (want.isNotBlank()) View.VISIBLE else View.GONE
      if (want.isNotBlank()) {
        val requestId = artRequestId
        artRequest = io.submit {
          if (!work.isCurrent(token) || Thread.currentThread().isInterrupted) return@submit
          val bmp = MediaArt.resolveUri(context, want)
          if (!work.isCurrent(token) || Thread.currentThread().isInterrupted) return@submit
          ui.post {
            if (work.isCurrent(token) && artRequestId == requestId && lastArtUrl == want) {
              artRequest = null
              art.setImageBitmap(bmp)
            }
          }
        }
      }
    }
  }

  private fun cancelArtRequest() {
    artRequestId++
    artRequest?.cancel(true)
    artRequest = null
  }

  /** ImageView holds its drawable even when hidden. Shared session/cache bitmaps are borrowed. */
  private fun clearNowPlayingArtwork() {
    cancelArtRequest()
    npArt?.setImageDrawable(null)
    npArt?.visibility = View.GONE
    lastArtUrl = null
    lastArtBitmap = null
  }

  /**
   * The photo caption ("place" bold over a lighter "date") as a grid element, so it stacks with
   * whatever else lands in the same cell (notably the now-playing card — both default to
   * bottom-right) instead of overlapping it. Hidden until [setCaption] gets real metadata.
   */
  private fun buildCaption(spec: CaptionSpec) {
    val align = horizontalGravity(spec.position)
    val col = LinearLayout(context)
    col.orientation = LinearLayout.VERTICAL
    col.gravity = align
    col.visibility = View.GONE
    // "On this day" memory badge — warm amber, above the place/date lines. Only shows for a
    // photo taken on today's month+day in a prior year (PhotoCaption.memoryYears).
    val memory = text(15f, 0xFFFFD66E.toInt(), false)
    memory.typeface = Typeface.DEFAULT_BOLD
    memory.letterSpacing = 0.04f
    memory.maxLines = 1
    memory.gravity = align
    memory.visibility = View.GONE
    val place = text(19f, Color.WHITE, false)
    place.typeface = Typeface.DEFAULT_BOLD
    place.maxLines = 1
    place.ellipsize = TextUtils.TruncateAt.END
    place.gravity = align
    val date = text(14f, 0xCCFFFFFF.toInt(), true)
    date.maxLines = 1
    date.gravity = align
    col.addView(memory, LinearLayout.LayoutParams(WRAP, WRAP))
    col.addView(place, LinearLayout.LayoutParams(WRAP, WRAP))
    col.addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
    // Top margin gives breathing room from whatever sits above in the same cell (the now-playing
    // card). A GONE sibling contributes no space, so a lone caption isn't pushed off the bottom.
    bucket(spec.position)
        .addView(col, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(18) })
    captionPanel = col
    captionMemory = memory
    captionPlace = place
    captionDate = date
  }

  /**
   * Push the latest photo caption (main thread). A blank/absent place AND date hides it; otherwise
   * each line shows only when it has content. [memory] is the "N years ago today" badge — shown
   * above the place when non-null. No-op when the caption isn't built (disabled, or a full-bleed
   * face).
   */
  fun setCaption(place: String?, date: String?, memory: String? = null) {
    if (work.isStopped) return
    val col = captionPanel ?: return
    val p = place?.takeIf { it.isNotBlank() }
    val d = date?.takeIf { it.isNotBlank() }
    val m = memory?.takeIf { it.isNotBlank() }
    if (p == null && d == null && m == null) {
      col.visibility = View.GONE
      return
    }
    captionMemory?.apply {
      visibility = if (m == null) View.GONE else View.VISIBLE
      text = m ?: ""
    }
    captionPlace?.apply {
      visibility = if (p == null) View.GONE else View.VISIBLE
      text = p ?: ""
    }
    captionDate?.apply {
      visibility = if (d == null) View.GONE else View.VISIBLE
      text = d ?: ""
    }
    col.visibility = View.VISIBLE
  }

  /** Metadata has plain text; unchanged content does not need another layout/invalidation. */
  private fun TextView.setMetadataText(value: String) {
    if (text?.toString() != value) text = value
  }

  // --- periodic loops ---------------------------------------------------------
  private val tick =
      object : Runnable {
        override fun run() {
          if (!work.isActive) return
          val now = Date()
          blinkOn = !blinkOn
          clockFace?.update(now, blinkOn)
          if (face.clock.showDate)
              dateView?.setMetadataText(face.clock.dateFormat.format(now))
          if (batteryView != null) {
            val (pct, charging) = batteryInfo()
            val hasBattery = pct >= 0
            batteryView?.setMetadataText(if (hasBattery) (if (charging) "$pct% ⚡" else "$pct%") else "")
            batteryView?.visibility = if (hasBattery) View.VISIBLE else View.GONE
            batteryDivider?.visibility = if (hasBattery) View.VISIBLE else View.GONE
          }
          val hasWeather = weatherText.isNotBlank()
          weatherView?.setMetadataText(weatherText)
          weatherView?.visibility = if (hasWeather) View.VISIBLE else View.GONE
          weatherDivider?.visibility = if (hasWeather) View.VISIBLE else View.GONE
          // Shared countdown timer: show "M:SS" while running, the alarm banner while ringing.
          val timerState = TimerStore.load(context)
          val timerRemaining = timerState.remaining(now.time)
          if (timerRemaining > 0L && !timerState.ringing) {
            val tm = (timerRemaining / 60_000).toInt()
            val ts = ((timerRemaining / 1000) % 60).toInt()
            timerView?.setMetadataText("⏱ %d:%02d".format(tm, ts))
            timerView?.visibility = View.VISIBLE
          } else {
            timerView?.visibility = View.GONE
          }
          if (timerState.ringing) {
            if (timerAlarmOverlay?.visibility != View.VISIBLE) {
              timerThumb?.translationX = 0f
              timerSlideText?.alpha = 1f
            }
            timerAlarmOverlay?.visibility = View.VISIBLE
          } else {
            timerAlarmOverlay?.visibility = View.GONE
          }
          // Burn-in: drift the whole overlay a few px so no pixel stays lit in place. Skipped
          // (overlay held still) when the user turns the pixel-shift off.
          val drift =
              if (antiBurnInOn) AntiBurnIn.shift(now.time, burnInMaxPx.toFloat())
              else AntiBurnIn.Shift(0f, 0f)
          view.translationX = drift.x
          view.translationY = drift.y
          ui.postDelayed(this, 1_000L)
        }
      }

  private val refreshWeather =
      object : Runnable {
        override fun run() {
          val token = work.token() ?: return
          // Omitted weather has no consumer; do not admit IO or its periodic callback.
          if (weatherView == null) return
          if (weatherRequest == null) {
            weatherRequest = io.submit {
              if (!work.isCurrent(token) || Thread.currentThread().isInterrupted) return@submit
              val w = runCatching { weatherFetch(context) }.getOrDefault("")
              if (!work.isCurrent(token) || Thread.currentThread().isInterrupted) return@submit
              ui.post {
                if (work.isCurrent(token)) {
                  weatherRequest = null
                  if (w.isNotBlank()) weatherText = w
                }
              }
            }
          }
          ui.postDelayed(this, weatherRefreshMs)
        }
      }

  // --- styling ----------------------------------------------------------------
  /** A styled text view (meta row) honouring the clock spec's font / colour / shadow / size. */
  private fun textView(baseSp: Float, spec: ClockSpec, light: Boolean = true): TextView {
    val t = TextView(context)
    t.textSize = baseSp * spec.sizeScale / 100f
    t.setTextColor(FaceStyle.colorWithOpacity(spec.color, spec.opacity))
    t.typeface = FaceStyle.typeface(assets, spec, light)
    FaceStyle.applyShadow(t, spec.shadow, spec.color)
    return t
  }

  /** Plain styled text (now-playing), matching the original helper. */
  private fun text(sizeSp: Float, color: Int, lightFont: Boolean): TextView {
    val t = TextView(context)
    t.textSize = sizeSp
    t.setTextColor(color)
    if (lightFont) t.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    t.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
    return t
  }

  private fun divider(): View {
    val v = TextView(context)
    v.text = "   •   "
    v.textSize = 22f
    v.setTextColor(0x88FFFFFF.toInt())
    return v
  }

  // --- grid -------------------------------------------------------------------
  /** Get (or create) the vertical stack for a grid position, placed with the right gravity. */
  private fun bucket(pos: GridPosition): LinearLayout =
      buckets.getOrPut(pos) {
        val col = LinearLayout(context)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = horizontalGravity(pos)
        col.clipChildren = false // shadows on the clock / meta text may overflow this column
        col.clipToPadding = false
        applyPhotoBucketBacking(col)
        val lp = FrameLayout.LayoutParams(WRAP, WRAP, gravityFor(pos))
        val bottomInset = when (pos) {
            GridPosition.BOTTOM_LEFT, GridPosition.BOTTOM_CENTER, GridPosition.BOTTOM_RIGHT -> dp(80)
            else -> dp(40)
        }
        lp.setMargins(dp(40), dp(40), dp(40), bottomInset)
        view.addView(col, lp)
        col
      }

  private fun gravityFor(pos: GridPosition): Int =
      when (pos) {
        GridPosition.TOP_LEFT -> Gravity.TOP or Gravity.START
        GridPosition.TOP_CENTER -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        GridPosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
        GridPosition.MIDDLE_LEFT -> Gravity.CENTER_VERTICAL or Gravity.START
        GridPosition.MIDDLE_CENTER -> Gravity.CENTER
        GridPosition.MIDDLE_RIGHT -> Gravity.CENTER_VERTICAL or Gravity.END
        GridPosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
        GridPosition.BOTTOM_CENTER -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        GridPosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
      }

  private fun horizontalGravity(pos: GridPosition): Int =
      when (pos) {
        GridPosition.TOP_RIGHT,
        GridPosition.MIDDLE_RIGHT,
        GridPosition.BOTTOM_RIGHT -> Gravity.END
        GridPosition.TOP_CENTER,
        GridPosition.MIDDLE_CENTER,
        GridPosition.BOTTOM_CENTER -> Gravity.CENTER_HORIZONTAL
        else -> Gravity.START
      }

  // --- data -------------------------------------------------------------------
  /** Battery percent (-1 = no battery / mains-only Portal) and whether it's charging. */
  private fun batteryInfo(): Pair<Int, Boolean> {
    if (!work.isActive) return -1 to false
    if (batteryReceiver == null) {
      // The tick and receiver callbacks run on Main. A retained hidden renderer owns no listener.
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
          if (batteryReceiver === this && work.isActive &&
              intent?.action == Intent.ACTION_BATTERY_CHANGED) {
            batteryStatus = readBatteryStatus(intent)
          }
        }
      }
      batteryReceiver = receiver
      try {
        batteryStatus = readBatteryStatus(
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
      } catch (error: RuntimeException) {
        batteryReceiver = null
        batteryStatus = -1 to false
        throw error
      }
    }
    return batteryStatus
  }

  private fun readBatteryStatus(i: Intent?): Pair<Int, Boolean> {
    if (i == null || !i.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) return -1 to false
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging =
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
    return pct to charging
  }

  private val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
  private val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
  private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
