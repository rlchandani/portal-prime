/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/**
 * Overlay and presentation decisions: how the native stage frames a photo for the mode, which
 * overlays a night photo hides, when the calendar re-fetches, and the engine page's commands.
 * Pure; [PhotoFrameController] applies them to its views.
 */
internal object PhotoFrameStagePolicy {
  /** How [SlideshowStage.show] presents the next scene. */
  data class Presentation(
      val fill: Boolean,
      val motion: Boolean,
      val galleryPrint: Boolean,
      val gallerySpread: Boolean,
      val feature: SlideshowStage.Feature,
      val keepPeople: Boolean,
  )

  fun isGallery(mode: String): Boolean =
      mode == ScreensaverConfig.MODE_GALLERY_PRINT || mode == ScreensaverConfig.MODE_GALLERY_SPREAD

  /** Face-aware framing: wanted, and the mode crops (whole photo and gallery prints don't). */
  fun keepPeople(settings: ScreensaverConfig.Settings): Boolean =
      settings.keepPeopleInFrame && settings.mode != ScreensaverConfig.MODE_WHOLE && !isGallery(settings.mode)

  /** Modes that show two photos side by side ask the loader for a partner. */
  fun pairs(mode: String): Boolean = mode == ScreensaverConfig.MODE_PORTRAITS || mode == ScreensaverConfig.MODE_GALLERY_SPREAD

  fun presentation(settings: ScreensaverConfig.Settings, nightPhoto: Boolean, splitGlance: Boolean): Presentation =
      Presentation(
          fill = settings.fit == ScreensaverConfig.FIT_FILL && settings.mode != ScreensaverConfig.MODE_WHOLE,
          motion = settings.photoMotion && settings.mode != ScreensaverConfig.MODE_WHOLE,
          galleryPrint = settings.mode == ScreensaverConfig.MODE_GALLERY_PRINT,
          gallerySpread = settings.mode == ScreensaverConfig.MODE_GALLERY_SPREAD,
          feature = when {
            nightPhoto -> SlideshowStage.Feature.NIGHT_PHOTO
            splitGlance -> SlideshowStage.Feature.SPLIT
            else -> SlideshowStage.Feature.NONE
          },
          keepPeople = keepPeople(settings),
      )

  /** The finite style a presented native scene reports to a bounded capture, if any. */
  fun reportedStyle(mode: String): SlideshowStyle? = when (mode) {
    ScreensaverConfig.MODE_GALLERY_PRINT -> SlideshowStyle.GALLERY_PRINT
    ScreensaverConfig.MODE_GALLERY_SPREAD -> SlideshowStyle.GALLERY_SPREAD
    else -> null
  }

  /** Under a night photo the day overlays hide; the face only when it is the overlay in use. */
  data class NightPhotoOverlays(val dayVisible: Boolean, val faceFollows: Boolean, val rerenderCalendar: Boolean)

  fun nightPhotoOverlays(night: Boolean, splitGlance: Boolean): NightPhotoOverlays =
      NightPhotoOverlays(dayVisible = !night, faceFollows = !splitGlance, rerenderCalendar = !night)

  /** The night photo re-presents a single on-screen photo so it warms (or cools) now. */
  fun representForNightPhoto(nativePhotoMode: Boolean, stopped: Boolean, photoCount: Int): Boolean =
      nativePhotoMode && !stopped && photoCount == 1

  /** A calendar change pushed over the fleet API (or in-app) without restarting the dream. */
  fun calendarChanged(current: ScreensaverConfig.Settings, fresh: ScreensaverConfig.Settings): Boolean =
      fresh.calendarUrl != current.calendarUrl || fresh.calendarRange != current.calendarRange

  /** The household calendar is fetched only when it is in use, linked, and not under the test source. */
  fun fetchCalendar(settings: ScreensaverConfig.Settings, testSource: Boolean): String? =
      settings.calendarUrl.takeIf { !testSource && settings.usesCalendar && !it.isNullOrBlank() }

  /** Occlusion margin around the calendar panel, in dp. */
  const val CALENDAR_MARGIN_DP = 20
}

/** The WebView engine page's commands (assets/screensaver). */
internal object PhotoFrameEngineScript {
  const val NEXT = "window.immortalSlideshow?.next()"
  const val PREVIOUS = "window.immortalSlideshow?.previous()"
  const val RESIZE = "window.dispatchEvent(new Event('resize'))"

  /** The engine plays only while the host is active and the viewer isn't paused. */
  fun plays(hostActive: Boolean, paused: Boolean): Boolean = hostActive && !paused

  fun playback(hostActive: Boolean, paused: Boolean): String =
      "window.immortalSlideshow?.${if (plays(hostActive, paused)) "resume" else "pause"}()"

  /** The engine page URL: native clock, cache-busting asset version, initial pause. */
  fun pageUrl(assetVersion: Int, hostActive: Boolean, paused: Boolean): String =
      "file:///android_asset/screensaver/index.html?nativeClock=1&v=$assetVersion&hostPaused=${if (plays(hostActive, paused)) 0 else 1}"
}

/** A finished touch on the frame, from the raw down/up deltas (robust to input without MOVE events). */
internal object PhotoFrameGesture {
  enum class Kind { NEXT, PREVIOUS, TAP, NONE }

  private const val TAP_PX = 48
  private const val SWIPE_PX = 120
  private const val SWIPE_RATIO = 1.5f

  /** Within the tap slop on both axes. */
  fun isTap(dx: Float, dy: Float): Boolean = kotlin.math.abs(dx) < TAP_PX && kotlin.math.abs(dy) < TAP_PX

  /** A clear horizontal swipe navigates (left = next), a clear tap exits, anything else is ignored. */
  fun classify(dx: Float, dy: Float): Kind = when {
    kotlin.math.abs(dx) > SWIPE_PX && kotlin.math.abs(dx) > kotlin.math.abs(dy) * SWIPE_RATIO ->
      if (dx < 0) Kind.NEXT else Kind.PREVIOUS
    isTap(dx, dy) -> Kind.TAP
    else -> Kind.NONE
  }
}
