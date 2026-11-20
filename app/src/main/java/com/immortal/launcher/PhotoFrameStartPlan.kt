/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

/** How the frame is hosted: the Dream/preview, music photos, or the scheduled night clock. */
internal data class PhotoFrameHost(
    /** In-page music photos: no clock, calendar, voice, soundscape or dream handoff. */
    val photosOnly: Boolean = false,
    /** Scheduled night clock: no photo, calendar, weather or audio pipeline at all. */
    val nightClockOnly: Boolean = false,
    /** Scheduled night clock in the "Night photo" colour: dim warm photos with a small clock. */
    val overnightNightPhoto: Boolean = false,
    /** Presence-triggered start: the welcome-back overlay may show. */
    val showWelcome: Boolean = false,
    /** A debug/overnight face replaces the household's (web pages can't host it). */
    val faceOverride: Boolean = false,
)

/** Which overlay draws over the photos. */
internal enum class PhotoFrameOverlay { NONE, FACE, SPLIT }

/** What the built-in feed becomes: the classic web feed, the engine on Picsum, or generated scenes. */
internal enum class PhotoFrameFeedRoute { WEB, ENGINE, TEST_SCENES }

/** Everything [PhotoFrameController.start] decides before it touches a view, in one place. */
internal data class PhotoFrameStart(
    val settings: ScreensaverConfig.Settings,
    val testSource: Boolean,
    val forcedNight: Boolean,
    val darkRoomSensor: Boolean,
    val source: PhotoFrameSource,
    val nativePhotoMode: Boolean,
    val engineMode: Boolean,
    val purgeCache: Boolean,
    val calendar: Boolean,
    val overlay: PhotoFrameOverlay,
    val nightPhoto: Boolean,
    val galleryObserver: Boolean,
    val speakWelcome: Boolean,
    val soundscape: Boolean,
    val gestureWave: Boolean,
    val weather: Boolean,
    val dashboard: Boolean,
    val welcomeOverlay: Boolean,
    val feedRoute: PhotoFrameFeedRoute,
)

internal object PhotoFrameStartPlan {
  /** First ambient-dashboard card after the frame starts. */
  const val DASHBOARD_FIRST_MS = 45_000L

  /**
   * The settings a session runs with: the household's (guest-filtered), projected for music
   * photos, or held to the operator's test-source pin. [pin] is read only when it can apply.
   */
  fun settings(household: ScreensaverConfig.Settings, host: PhotoFrameHost, pin: () -> ScreensaverTestSource.Pin?): ScreensaverConfig.Settings =
      if (host.photosOnly) musicPhotoSettings(household) else ScreensaverTestSource.pinned(household, pin())

  /** Split glance replaces the face with its own clock panel (never for music photos). */
  fun splitGlance(settings: ScreensaverConfig.Settings, host: PhotoFrameHost): Boolean =
      !host.photosOnly && settings.mode == ScreensaverConfig.MODE_SPLIT_GLANCE

  /**
   * The start decisions for [settings]. The test source, when on, replaces every configured
   * (household) source with generated scenes and keeps the household calendar and dashboard off.
   */
  fun plan(settings: ScreensaverConfig.Settings, host: PhotoFrameHost, prefs: PhotoFramePrefs, welcomeTts: Boolean): PhotoFrameStart {
    val photos = !host.photosOnly
    val testSource = photos && prefs.testSourceActive()
    // Operator verification: the dark room is forced on, so the light sensor must not undo it.
    val forcedNight = testSource && prefs.testSourceForcedNight()
    val source = if (testSource) PhotoFrameSource.DefaultFeed
        else PhotoFrameSource.from(settings, allowWebPage = photos && !host.faceOverride)
    val split = splitGlance(settings, host)
    val engineMode = ScreensaverConfig.usesEngine(settings.mode)
    return PhotoFrameStart(
        settings = settings,
        testSource = testSource,
        forcedNight = forcedNight,
        darkRoomSensor = photos && settings.darkRoomClock && !forcedNight,
        source = source,
        nativePhotoMode = ScreensaverConfig.isNativeStage(settings.mode) && source !is PhotoFrameSource.WebPage,
        engineMode = engineMode,
        purgeCache = !settings.cacheEnabled,
        calendar = photos,
        overlay = when {
          photos && split && !host.overnightNightPhoto -> PhotoFrameOverlay.SPLIT
          photos -> PhotoFrameOverlay.FACE
          else -> PhotoFrameOverlay.NONE
        },
        nightPhoto = host.overnightNightPhoto,
        galleryObserver = photos,
        speakWelcome = photos && host.showWelcome && settings.welcomeEnabled && welcomeTts,
        // The overnight Night photo is a bedside display: never start a soundscape.
        soundscape = photos && !host.overnightNightPhoto && settings.soundscape != ScreensaverConfig.SOUND_OFF,
        gestureWave = photos && settings.gestureWave,
        weather = photos,
        // A Classic-renderer card: under the engine it would only interrupt (the engine has its own
        // clock, and the card would sit behind the opaque WebView). It reads the household calendar,
        // so never on the verification source.
        dashboard = photos && !host.overnightNightPhoto && !split && !testSource && settings.ambientDashboard && !engineMode,
        welcomeOverlay = photos && host.showWelcome && settings.welcomeEnabled && !engineMode,
        feedRoute = when {
          !engineMode -> PhotoFrameFeedRoute.WEB
          testSource -> PhotoFrameFeedRoute.TEST_SCENES
          else -> PhotoFrameFeedRoute.ENGINE
        },
    )
  }
}
